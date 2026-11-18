from pathlib import Path

# Original source is audited in fixed-size chunks before edits.
for name in (
    "app/src/main/assets/imagepack-folder-picker.js",
    "app/src/main/java/com/wayward/launcher/ImagePackDirectoryAccess.java",
):
    with open(name, "rb") as stream:
        offset = 0
        while True:
            chunk = stream.read(4096)
            if not chunk:
                break
            offset += len(chunk)

jp = Path("app/src/main/java/com/wayward/launcher/ImagePackDirectoryAccess.java")
jt = jp.with_suffix(".java.v21tmp")
imports = {
    "import android.os.Build;": "import android.os.CancellationSignal;\nimport android.os.OperationCanceledException;",
    "import java.util.UUID;": "import java.util.concurrent.CountDownLatch;\nimport java.util.concurrent.ConcurrentHashMap;\nimport java.util.concurrent.ExecutorService;\nimport java.util.concurrent.Executors;\nimport java.util.concurrent.Future;\nimport java.util.concurrent.TimeUnit;",
}
fields = """    private static final int DIRECTORY_BATCH_SIZE = 32;
    private static final long DIRECTORY_BATCH_ACK_TIMEOUT_SECONDS = 30L;
    private static final String DIRECTORY_START_PREFIX = "__WAYWARD_DIR_START__:";
    private static final String DIRECTORY_ACK_PREFIX = "__WAYWARD_DIR_ACK__:";
    private static final String DIRECTORY_CANCEL_PREFIX = "__WAYWARD_DIR_CANCEL__:";
    private static final String DIRECTORY_RELEASE_PREFIX = "__WAYWARD_DIR_RELEASE__:";
    private final ExecutorService directoryExecutor = Executors.newFixedThreadPool(2);
    private final Map<String, DirectoryTask> directoryTasks = new ConcurrentHashMap<>();
    private final Map<String, Uri> directoryTreeByRootRequestId = new ConcurrentHashMap<>();
"""
methods = r'''
    String startListDirectory(String documentUri, String requestId) {
        if (requestId == null || requestId.trim().isEmpty()) {
            return failure("TypeError", "A directory request ID is required.").toString();
        }
        try {
            requireTree();
            String rootRequestId = rootDirectoryRequestId(requestId);
            Uri currentTree = treeUri;
            Uri selectedTree = directoryTreeByRootRequestId.get(rootRequestId);
            if (selectedTree == null) {
                Uri previousTree = directoryTreeByRootRequestId.putIfAbsent(rootRequestId, currentTree);
                selectedTree = previousTree == null ? currentTree : previousTree;
            }
            final Uri requestTree = selectedTree;
            Uri directory = requireDocumentUriReference(documentUri, requestTree);
            DirectoryTask task = new DirectoryTask(requestId);
            if (directoryTasks.putIfAbsent(requestId, task) != null) {
                return failure("InvalidStateError", "The directory request ID is already active.").toString();
            }
            try {
                task.future = directoryExecutor.submit(() -> runDirectoryList(task, requestTree, directory));
            } catch (RuntimeException rejected) {
                directoryTasks.remove(requestId, task);
                return failure("AbortError", "The SAF directory worker is unavailable.").toString();
            }
            JSONObject result = new JSONObject();
            result.put("ok", true);
            result.put("requestId", requestId);
            return result.toString();
        } catch (Exception error) {
            return failure(error instanceof SecurityException ? "SecurityError" : "NotReadableError",
                    error.getMessage() == null ? "The SAF directory request could not start." : error.getMessage()).toString();
        }
    }

    boolean acknowledgeDirectoryBatch(String requestId, int batchIndex) {
        DirectoryTask task = directoryTasks.get(requestId);
        return task != null && task.acknowledge(batchIndex);
    }

    boolean cancelDirectoryList(String requestId) {
        DirectoryTask task = directoryTasks.get(requestId);
        if (task == null) return false;
        task.cancel();
        return true;
    }

    private void cancelAllDirectoryLists() {
        for (DirectoryTask task : directoryTasks.values()) task.cancel();
        directoryTasks.clear();
        directoryExecutor.shutdownNow();
        directoryTreeByRootRequestId.clear();
    }

    private static String rootDirectoryRequestId(String requestId) {
        int separator = requestId.lastIndexOf("-dir-");
        return separator > 0 ? requestId.substring(0, separator) : requestId;
    }

    private void releaseDirectoryTree(String rootRequestId) {
        if (rootRequestId != null && !rootRequestId.isEmpty()) {
            directoryTreeByRootRequestId.remove(rootRequestId);
        }
    }

    private void runDirectoryList(DirectoryTask task, Uri selectedTree, Uri directoryUri) {
        int batchIndex = 0;
        try {
            Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                    selectedTree, DocumentsContract.getDocumentId(directoryUri));
            String[] projection = {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED
            };
            JSONArray batch = new JSONArray();
            String sortOrder = DocumentsContract.Document.COLUMN_DISPLAY_NAME + " ASC";
            Cursor providerCursor;
            try {
                providerCursor = activity.getContentResolver().query(
                        childrenUri, projection, null, null, sortOrder, task.cancellationSignal);
            } catch (IllegalArgumentException unsupportedSortOrder) {
                if (task.cancelled || task.cancellationSignal.isCanceled()) {
                    throw new OperationCanceledException("Directory listing cancelled.");
                }
                providerCursor = activity.getContentResolver().query(
                        childrenUri, projection, null, null, null, task.cancellationSignal);
            }
            try (Cursor cursor = providerCursor) {
                if (cursor == null) throw new IllegalStateException("Document provider returned no directory cursor.");
                int idColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
                int nameColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                int mimeColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
                int sizeColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE);
                int modifiedColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED);
                while (cursor.moveToNext()) {
                    if (task.cancelled || Thread.currentThread().isInterrupted()) {
                        throw new OperationCanceledException("Directory listing cancelled.");
                    }
                    String id = idColumn >= 0 ? cursor.getString(idColumn) : null;
                    String name = nameColumn >= 0 ? cursor.getString(nameColumn) : null;
                    if (id == null || name == null) continue;
                    String mime = mimeColumn >= 0 ? cursor.getString(mimeColumn) : null;
                    long size = sizeColumn >= 0 && !cursor.isNull(sizeColumn) ? cursor.getLong(sizeColumn) : -1L;
                    long modified = modifiedColumn >= 0 && !cursor.isNull(modifiedColumn) ? cursor.getLong(modifiedColumn) : -1L;
                    JSONObject item = new JSONObject();
                    item.put("name", name);
                    item.put("kind", DocumentsContract.Document.MIME_TYPE_DIR.equals(mime) ? "directory" : "file");
                    item.put("uri", DocumentsContract.buildDocumentUriUsingTree(selectedTree, id).toString());
                    item.put("size", size);
                    item.put("mimeType", mime);
                    item.put("lastModified", modified);
                    batch.put(item);
                    if (batch.length() >= DIRECTORY_BATCH_SIZE) {
                        if (!dispatchDirectoryBatchAndAwaitAck(task, batch, batchIndex, false)) return;
                        batchIndex++;
                        batch = new JSONArray();
                    }
                }
            }
            if (!task.cancelled) dispatchDirectoryBatchAndAwaitAck(task, batch, batchIndex, true);
        } catch (OperationCanceledException cancelled) {
            if (!task.cancelled) dispatchDirectoryError(task, batchIndex, "AbortError", "The SAF directory provider cancelled the query.");
        } catch (Exception error) {
            if (!task.cancelled) {
                String name = error instanceof SecurityException ? "SecurityError" : "NotReadableError";
                dispatchDirectoryError(task, batchIndex, name,
                        error.getMessage() == null ? "The selected SAF directory could not be listed." : error.getMessage());
            }
        } finally {
            directoryTasks.remove(task.requestId, task);
        }
    }

    private boolean dispatchDirectoryBatchAndAwaitAck(
            DirectoryTask task, JSONArray entries, int batchIndex, boolean done) {
        if (task.cancelled) return false;
        try {
            JSONObject packet = new JSONObject();
            packet.put("ok", true);
            packet.put("batchIndex", batchIndex);
            packet.put("done", done);
            packet.put("entries", entries);
            CountDownLatch acknowledgement = task.beginBatch(batchIndex);
            dispatchDirectoryPacket(task, packet);
            boolean acknowledged = acknowledgement.await(
                    DIRECTORY_BATCH_ACK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!acknowledged || task.cancelled) {
                task.cancel();
                return false;
            }
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            task.cancel();
            return false;
        } catch (Exception error) {
            task.cancel();
            return false;
        }
    }

    private void dispatchDirectoryError(DirectoryTask task, int batchIndex, String name, String message) {
        try {
            JSONObject packet = new JSONObject();
            packet.put("ok", false);
            packet.put("batchIndex", batchIndex);
            packet.put("done", true);
            packet.put("entries", new JSONArray());
            packet.put("name", name);
            packet.put("message", message);
            dispatchDirectoryPacket(task, packet);
        } catch (Exception ignored) {}
    }

    private void dispatchDirectoryPacket(DirectoryTask task, JSONObject packet) {
        String script = "(function(){if(typeof window.__waywardNativeImagePackDirectoryBatch==='function')"
                + "window.__waywardNativeImagePackDirectoryBatch("
                + JSONObject.quote(task.requestId) + "," + packet.toString() + ");})()";
        activity.runOnUiThread(() -> {
            if (task.cancelled || activity.isFinishing()) {
                task.cancel();
                return;
            }
            webView.evaluateJavascript(script, null);
        });
    }
'''
uri_validator = r'''
    private Uri requireDocumentUriReference(String documentUri, Uri selectedTree) {
        if (selectedTree == null) throw new SecurityException("No SAF tree is selected.");
        Uri candidate = Uri.parse(documentUri == null ? "" : documentUri);
        if (!"content".equals(candidate.getScheme()) || !DocumentsContract.isDocumentUri(activity, candidate)) {
            throw new SecurityException("The requested SAF document URI is invalid.");
        }
        String requestedTreeId = DocumentsContract.getTreeDocumentId(candidate);
        String activeTreeId = DocumentsContract.getTreeDocumentId(selectedTree);
        if (requestedTreeId == null || !requestedTreeId.equals(activeTreeId)) {
            throw new SecurityException("The document URI is outside the selected SAF tree.");
        }
        return DocumentsContract.buildDocumentUriUsingTree(
                selectedTree, DocumentsContract.getDocumentId(candidate));
    }
'''
task_class = r'''
    private static final class DirectoryTask {
        final String requestId;
        final CancellationSignal cancellationSignal = new CancellationSignal();
        volatile Future<?> future;
        volatile boolean cancelled;
        private int awaitingBatchIndex = -1;
        private CountDownLatch acknowledgement;

        DirectoryTask(String requestId) { this.requestId = requestId; }

        synchronized CountDownLatch beginBatch(int batchIndex) {
            awaitingBatchIndex = batchIndex;
            acknowledgement = new CountDownLatch(1);
            return acknowledgement;
        }

        synchronized boolean acknowledge(int batchIndex) {
            if (cancelled || acknowledgement == null || awaitingBatchIndex != batchIndex) return false;
            acknowledgement.countDown();
            return true;
        }

        void cancel() {
            cancelled = true;
            try { cancellationSignal.cancel(); } catch (RuntimeException ignored) {}
            Future<?> current = future;
            if (current != null) current.cancel(true);
            synchronized (this) {
                if (acknowledgement != null) acknowledgement.countDown();
            }
        }
    }
'''
seen_imports = set()
fields_done = cancel_done = methods_done = uri_done = task_done = False
with jp.open("r", encoding="utf-8") as src, jt.open("w", encoding="utf-8", newline="") as dst:
    for line in src:
        matched = False
        for anchor, extra in imports.items():
            if anchor in line and anchor not in seen_imports:
                dst.write(line)
                dst.write(extra + "\n")
                seen_imports.add(anchor)
                matched = True
                break
        if matched:
            continue
        if "private final Map<String, NativeFileStream> openFileStreams" in line and not fields_done:
            dst.write(line)
            dst.write(fields + "\n")
            fields_done = True
            continue
        if line.strip() == "void cancelPending() {" and not cancel_done:
            dst.write(line)
            dst.write("        cancelAllDirectoryLists();\n")
            cancel_done = True
            continue
        if line.strip() == "String listDirectoryJson(String documentUri, String requestId) {":
            dst.write(methods + "\n")
            dst.write(line)
            dst.write("""        if (documentUri != null && documentUri.startsWith(DIRECTORY_START_PREFIX)) {
            return startListDirectory(documentUri.substring(DIRECTORY_START_PREFIX.length()), requestId);
        }
        if (documentUri != null && documentUri.startsWith(DIRECTORY_ACK_PREFIX)) {
            try {
                boolean accepted = acknowledgeDirectoryBatch(requestId,
                        Integer.parseInt(documentUri.substring(DIRECTORY_ACK_PREFIX.length())));
                return accepted ? "{\\\\"ok\\\\":true}" : failure("AbortError", "The SAF batch is no longer active.").toString();
            } catch (Exception error) {
                return failure("TypeError", "The SAF batch acknowledgement is invalid.").toString();
            }
        }
        if (documentUri != null && documentUri.equals(DIRECTORY_CANCEL_PREFIX)) {
            cancelDirectoryList(requestId);
            return "{\\\\"ok\\\\":true}";
        }
        if (documentUri != null && documentUri.equals(DIRECTORY_RELEASE_PREFIX)) {
            releaseDirectoryTree(rootDirectoryRequestId(requestId));
            return "{\\\\"ok\\\\":true}";
        }
""")
            methods_done = True
            continue
        if line.strip() == "private Node queryDocument(Uri documentUri) throws Exception {" and not uri_done:
            dst.write(uri_validator + "\n")
            uri_done = True
        if line.strip() == "private static JSONObject failure(String name, String message) {" and not task_done:
            dst.write(task_class + "\n")
            task_done = True
        dst.write(line)
if not all([fields_done, cancel_done, methods_done, uri_done, task_done]):
    raise SystemExit(f"Java anchors missing: {fields_done=} {cancel_done=} {methods_done=} {uri_done=} {task_done=}")
jt.replace(jp)

js = Path("app/src/main/assets/imagepack-folder-picker.js")
jt = js.with_suffix(".js.v21tmp")
iterator = r'''
  async function* iterateDirectoryEntries(directoryUri, requestId, control) {
    const directoryRequestId = String(requestId || "saf") + "-dir-" + (++nextDirectoryListId);
    const state = { packet: null, waiter: null, expectedBatch: 0 };
    pendingDirectoryLists.set(directoryRequestId, state);
    if (control && control.nativeDirectoryIds) control.nativeDirectoryIds.add(directoryRequestId);
    let completed = false;
    try {
      parseResult(host.listImagePackDirectoryForRequest(
        nativeCapability, "__WAYWARD_DIR_START__:" + directoryUri, directoryRequestId));
      while (true) {
        checkUploadCancelled(control);
        const packet = state.packet || await new Promise((resolve, reject) => {
          const timeout = setTimeout(() => {
            if (state.waiter === receivePacket) state.waiter = null;
            reject(domError("TimeoutError", "The native SAF directory batch timed out."));
          }, 35000);
          const receivePacket = value => {
            clearTimeout(timeout);
            resolve(value);
          };
          state.waiter = receivePacket;
        });
        state.packet = null;
        if (!packet || packet.batchIndex !== state.expectedBatch) {
          throw domError("NotReadableError", "The native SAF directory batch sequence is invalid.");
        }
        if (packet.ok !== true) {
          throw domError(packet.name || "NotReadableError",
            packet.message || "The SAF provider could not list this directory.");
        }
        const entries = Array.isArray(packet.entries) ? packet.entries : [];
        for (const entry of entries) {
          checkUploadCancelled(control);
          yield entry;
        }
        const done = packet.done === true;
        parseResult(host.listImagePackDirectoryForRequest(
          nativeCapability, "__WAYWARD_DIR_ACK__:" + String(packet.batchIndex), directoryRequestId));
        state.expectedBatch++;
        if (done) {
          completed = true;
          return;
        }
      }
    } finally {
      pendingDirectoryLists.delete(directoryRequestId);
      if (control && control.nativeDirectoryIds) control.nativeDirectoryIds.delete(directoryRequestId);
      if (!completed) {
        try { host.listImagePackDirectoryForRequest(nativeCapability, "__WAYWARD_DIR_CANCEL__:", directoryRequestId); } catch (_) {}
      }
    }
  }
'''
callback = r'''
  window.__waywardNativeImagePackDirectoryBatch = function(requestId, packet) {
    const state = pendingDirectoryLists.get(String(requestId || ""));
    if (!state) {
      try { host.listImagePackDirectoryForRequest(nativeCapability, "__WAYWARD_DIR_CANCEL__:", String(requestId || "")); } catch (_) {}
      return false;
    }
    if (state.waiter) {
      const resolve = state.waiter;
      state.waiter = null;
      resolve(packet);
    } else if (!state.packet) {
      state.packet = packet;
    } else {
      try { host.listImagePackDirectoryForRequest(nativeCapability, "__WAYWARD_DIR_CANCEL__:", String(requestId || "")); } catch (_) {}
      return false;
    }
    return true;
  };
'''
maps_done = callback_done = cancel_done = control_done = iterator_done = False
skip_sort = False
in_enum = False
with js.open("r", encoding="utf-8") as src, jt.open("w", encoding="utf-8", newline="") as dst:
    for line in src:
        if "const pending = new Map();" in line and not maps_done:
            dst.write(line)
            dst.write("  const pendingDirectoryLists = new Map();\n  let nextDirectoryListId = 0;\n")
            maps_done = True
            continue
        if "const rootByHandle = new WeakMap();" in line and not callback_done:
            dst.write(line)
            dst.write(callback + "\n")
            callback_done = True
            continue
        if "async function enumerateSafTree(" in line and not iterator_done:
            dst.write(iterator + "\n")
            dst.write(line)
            iterator_done = True
            in_enum = True
            continue
        if in_enum and "const children = listEntries(directoryUri, requestId);" in line:
            continue
        if in_enum and "children.sort((left, right) =>" in line:
            skip_sort = True
            continue
        if skip_sort:
            if 'localeCompare(String(right.name || "")));' in line:
                skip_sort = False
            continue
        if in_enum and "for (const child of children) {" in line:
            dst.write("      for await (const child of iterateDirectoryEntries(directoryUri, requestId, control)) {\n")
            continue
        if in_enum and "await visit(rootUri, rootSegment, 0);" in line:
            dst.write("    try {\n")
            dst.write(line)
            continue
        if in_enum and "return files;" in line:
            dst.write(line)
            dst.write('''    } finally {
      try { host.listImagePackDirectoryForRequest(nativeCapability, "__WAYWARD_DIR_RELEASE__:", requestId); } catch (_) {}
    }
''')
            in_enum = False
            continue
        if 'const control = { requestId: "", cancelled: false };' in line and not control_done:
            dst.write('    const control = { requestId: "", cancelled: false, nativeDirectoryIds: new Set() };\n')
            control_done = True
            continue
        if "active.cancelled = true;" in line and not cancel_done:
            dst.write(line)
            dst.write('''    if (active.nativeDirectoryIds && typeof pendingDirectoryLists !== "undefined") {
      for (const directoryRequestId of active.nativeDirectoryIds) {
        const state = pendingDirectoryLists.get(directoryRequestId);
        if (state && state.waiter) {
          const resolve = state.waiter;
          state.waiter = null;
          resolve({ ok: false, name: "AbortError", message: "The folder processing request was cancelled.",
            batchIndex: state.expectedBatch, done: true, entries: [] });
        }
        try { host.listImagePackDirectoryForRequest(nativeCapability, "__WAYWARD_DIR_CANCEL__:", directoryRequestId); } catch (_) {}
      }
    }
''')
            cancel_done = True
            continue
        dst.write(line)
if not all([maps_done, callback_done, cancel_done, control_done, iterator_done]) or skip_sort or in_enum:
    raise SystemExit(f"JS anchors missing: {maps_done=} {callback_done=} {cancel_done=} {control_done=} {iterator_done=} {skip_sort=} {in_enum=}")
jt.replace(js)

# Update the existing browser-contract fake bridge to speak the batch protocol.
p = Path("tests/faith-imagepack-upload-contract.test.mjs")
s = p.read_text()
anchor = """let fakeLargestChunk = 0;
const host = {"""
replacement = """let fakeLargestChunk = 0;
const fakeDirectoryRequests = new Map();
function sendFakeDirectoryBatch(requestId, state) {
  const entries = state.entries.slice(state.offset, state.offset + 32);
  const done = state.offset + entries.length >= state.entries.length;
  const batchIndex = state.batchIndex++;
  state.offset += entries.length;
  state.waitingBatch = batchIndex;
  state.done = done;
  setTimeout(() => windowObject.__waywardNativeImagePackDirectoryBatch(requestId, {
    ok: true, batchIndex, done, entries
  }), 0);
}
const host = {"""
if anchor not in s: raise SystemExit("FAITH test host insertion anchor missing")
s = s.replace(anchor, replacement, 1)
old = """  listImagePackDirectoryForRequest(capability, uri) {
    assert.equal(capability, "test-native-capability");
    return JSON.stringify({ ok: true, uri, entries: directoryEntries.get(uri) || [] });
  },"""
new = """  listImagePackDirectoryForRequest(capability, uri, requestId) {
    assert.equal(capability, "test-native-capability");
    if (uri.startsWith("__WAYWARD_DIR_START__:")) {
      const directoryUri = uri.slice("__WAYWARD_DIR_START__:".length);
      const state = { entries: directoryEntries.get(directoryUri) || [], offset: 0, batchIndex: 0,
        waitingBatch: -1, done: false };
      fakeDirectoryRequests.set(requestId, state);
      sendFakeDirectoryBatch(requestId, state);
      return JSON.stringify({ ok: true });
    }
    if (uri.startsWith("__WAYWARD_DIR_ACK__:")) {
      const state = fakeDirectoryRequests.get(requestId);
      const batchIndex = Number(uri.slice("__WAYWARD_DIR_ACK__:".length));
      if (!state || state.waitingBatch !== batchIndex) {
        return JSON.stringify({ ok: false, name: "AbortError", message: "Bad batch ack" });
      }
      if (state.done) fakeDirectoryRequests.delete(requestId);
      else sendFakeDirectoryBatch(requestId, state);
      return JSON.stringify({ ok: true });
    }
    if (uri === "__WAYWARD_DIR_CANCEL__:") {
      fakeDirectoryRequests.delete(requestId);
      return JSON.stringify({ ok: true });
    }
    if (uri === "__WAYWARD_DIR_RELEASE__:") return JSON.stringify({ ok: true });
    return JSON.stringify({ ok: true, uri, entries: directoryEntries.get(uri) || [] });
  },"""
if old not in s: raise SystemExit("FAITH test native bridge anchor missing")
s = s.replace(old, new, 1)
p.write_text(s)
