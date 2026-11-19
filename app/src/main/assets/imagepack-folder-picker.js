(() => {
  "use strict";
  const host = window.WMLHost;
  const nativeCapability = "__WAYWARD_FOLDER_PICKER_CAPABILITY__";
  if (!host || typeof host.chooseImagePackFolder !== "function" || !nativeCapability) return;
  if (window.__waywardNativeImagePackPickerInstalled &&
      typeof window.showDirectoryPicker === "function") return;

  function diagnose(stage, requestId, details) {
    try {
      if (typeof host.recordImagePackDiagnostic === "function") {
        host.recordImagePackDiagnostic(requestId || "", stage, JSON.stringify(details || {}));
      }
    } catch (_) {}
  }

  let progressNode = null;
  let progressHideTimer = null;
  function showUploadProgress(message, state, requestId, details) {
    diagnose("imagepack_progress_" + state, requestId || "", details || {});
    try {
      const doc = document;
      const root = doc.body || doc.documentElement;
      if (!root) return;
      if (!progressNode || !progressNode.isConnected) {
        progressNode = doc.createElement("div");
        progressNode.setAttribute("role", "status");
        progressNode.setAttribute("aria-live", "polite");
        progressNode.style.cssText = "position:fixed;z-index:2147483647;left:12px;right:12px;bottom:calc(12px + env(safe-area-inset-bottom));padding:11px 14px;border:1px solid rgba(255,255,255,.2);border-radius:10px;background:rgba(20,20,24,.94);color:#fff;font:600 14px/1.4 sans-serif;box-shadow:0 4px 18px rgba(0,0,0,.35);pointer-events:none;text-align:center";
        root.appendChild(progressNode);
      }
      progressNode.textContent = String(message || "");
      if (progressHideTimer !== null) clearTimeout(progressHideTimer);
      if (state === "complete" || state === "failed" || state === "cancelled") {
        progressHideTimer = setTimeout(() => {
          if (progressNode && progressNode.parentNode) progressNode.parentNode.removeChild(progressNode);
          progressNode = null;
          progressHideTimer = null;
        }, state === "complete" ? 3500 : 9000);
      }
    } catch (_) {}
  }

  let nextId = 0;
  const pending = new Map();
  const pendingDirectoryLists = new Map();
  let nextDirectoryListId = 0;
  const rootByHandle = new WeakMap();

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


  function domError(name, message) {
    try { return new DOMException(message || name, name); }
    catch (_) {
      const error = new Error(message || name);
      error.name = name;
      return error;
    }
  }

  function parseResult(raw) {
    let result;
    try { result = typeof raw === "string" ? JSON.parse(raw) : raw; }
    catch (_) { throw domError("NotReadableError", "The native SAF bridge returned invalid data."); }
    if (!result || result.ok !== true) {
      throw domError(result && result.name || "NotReadableError",
        result && result.message || "The native SAF operation failed.");
    }
    return result;
  }

  function nativeRequest(mode, initialDocumentUri) {
    const id = "saf-" + Date.now().toString(36) + "-" +
      (++nextId) + "-" + Math.floor(Math.random() * 1000000000);
    const startedAt = Date.now();
    const frameOrigin = String(location.origin || "");
    const topFrame = window === window.top;
    let topOrigin = "";
    try { topOrigin = String(window.top.location.origin || ""); } catch (_) {}
    let frameDepth = 0;
    try {
      let parent = window;
      while (parent !== parent.parent) { frameDepth++; parent = parent.parent; }
    } catch (_) {}
    diagnose("show_directory_picker_called", id, {
      origin: frameOrigin, frameOrigin, topOrigin, pagePath: String(location.pathname || ""),
      frameDepth, isTopFrame: topFrame, mode
    });
    const requestPromise = new Promise((resolve, reject) => {
      const timeout = setTimeout(() => {
        pending.delete(id);
        diagnose("show_directory_picker_timeout", id, {
          timedOut: true, durationMs: Date.now() - startedAt, isTopFrame: topFrame
        });
        reject(domError("AbortError", "SAF folder selection timed out."));
      }, 120000);
      pending.set(id, {
        resolve(value) {
          clearTimeout(timeout);
          diagnose("show_directory_picker_promise_resolved", id, {
            durationMs: Date.now() - startedAt, writable: value && value.writable === true
          });
          resolve(value);
        },
        reject(error) {
          clearTimeout(timeout);
          diagnose("show_directory_picker_promise_rejected", id, {
            durationMs: Date.now() - startedAt, errorName: error && error.name || "Error"
          });
          reject(error);
        }
      });
      try {
        diagnose("wmlhost_choose_folder_call_start", id, { bridgeAvailable: true, isTopFrame: topFrame });
        host.chooseImagePackFolder(id, nativeCapability, mode, initialDocumentUri || "");
        diagnose("wmlhost_choose_folder_call_returned", id, { durationMs: Date.now() - startedAt });
      } catch (error) {
        pending.delete(id);
        clearTimeout(timeout);
        diagnose("wmlhost_choose_folder_call_failed", id, {
          errorName: error && error.name || "Error",
          errorMessage: String(error && error.message || error)
        });
        reject(domError("NotAllowedError", String(error && error.message || error)));
      }
    });
    return requestPromise.then(value => {
      diagnose("native_request_promise_continuation_entered", id, {
        durationMs: Date.now() - startedAt, resultOk: !!(value && value.ok)
      });
      return value;
    }, error => {
      diagnose("native_request_rejection_continuation_entered", id, {
        durationMs: Date.now() - startedAt, errorName: error && error.name || "Error"
      });
      throw error;
    });
  }

  Object.defineProperty(window, "__waywardNativeImagePackPickerResolve", {
    configurable: false,
    enumerable: false,
    value(id, result) {
      const request = pending.get(String(id));
      if (!request) return false;
      pending.delete(String(id));
      if (result && result.ok === true) {
        result.requestId = String(id);
        request.resolve(result);
      } else request.reject(domError(result && result.name || "AbortError",
        result && result.message || "SAF folder selection was cancelled."));
      return true;
    }
  });

  Object.defineProperty(window, "__waywardNativeImagePackPickerDispatch", {
    configurable: false,
    enumerable: false,
    value(id, result) {
      let delivered = 0;
      let visited = 0;
      const visit = frame => {
        let frameOrigin;
        try { frameOrigin = frame.location.origin; } catch (_) { return; }
        if (frameOrigin !== location.origin) return;
        visited++;
        try {
          if (typeof frame.__waywardNativeImagePackPickerResolve === "function" &&
              frame.__waywardNativeImagePackPickerResolve(id, result)) delivered++;
        } catch (_) {}
        try {
          for (let index = 0; index < frame.frames.length; index++) visit(frame.frames[index]);
        } catch (_) {}
      };
      visit(window);
      diagnose("native_result_frame_dispatch", id, {
        dispatchCount: delivered, sameOriginFrameCount: visited
      });
      return delivered;
    }
  });

  function childName(value) {
    const name = String(value || "");
    if (!name || name === "." || name === ".." || name.indexOf("/") >= 0 ||
        name.indexOf("\\") >= 0 || name.indexOf("\0") >= 0) {
      throw domError("TypeError", "A single valid file or directory name is required.");
    }
    return name;
  }

  function handleUri(handle) {
    const uri = handle && handle.__waywardSafDocumentUri;
    if (typeof uri !== "string" || !uri.startsWith("content://")) {
      throw domError("NotFoundError", "This handle has no valid SAF document URI.");
    }
    return uri;
  }

  function nativeJson(method, requestId, ...args) {
    try {
      let raw;
      switch (method) {
        case "listImagePackDirectory":
          raw = host.listImagePackDirectoryForRequest(nativeCapability, args[0], requestId || "");
          break;
        case "readImagePackFile":
          raw = host.readImagePackFileForRequest(nativeCapability, args[0], requestId || "");
          break;
        case "openImagePackFileStream":
          raw = host.openImagePackFileStreamForRequest(nativeCapability, args[0],
            String(args[1]), String(args[2]), requestId || "");
          break;
        case "readImagePackFileStreamChunk":
          raw = host.readImagePackFileStreamChunkForRequest(nativeCapability, args[0],
            String(args[1]), requestId || "");
          break;
        case "closeImagePackFileStream":
          raw = host.closeImagePackFileStreamForRequest(nativeCapability, args[0], requestId || "");
          break;
        case "createImagePackFile":
          raw = host.createImagePackFile(nativeCapability, args[0], args[1], args[2], requestId || "");
          break;
        case "writeImagePackFile":
          raw = host.writeImagePackFile(nativeCapability, args[0], args[1], args[2], requestId || "");
          break;
        case "createImagePackDirectory":
          raw = host.createImagePackDirectory(nativeCapability, args[0], args[1], requestId || "");
          break;
        case "deleteImagePackEntry":
          raw = host.deleteImagePackEntry(nativeCapability, args[0], requestId || "");
          break;
        case "getImagePackFolderInfo":
          raw = host.getImagePackFolderInfo(nativeCapability);
          break;
        case "resolveImagePackEntry":
          raw = host.resolveImagePackEntry(nativeCapability, args[0], args[1], requestId || "");
          break;
        default:
          throw domError("NotSupportedError", "The native SAF operation is unavailable.");
      }
      const result = parseResult(raw);
      diagnose("native_" + method + "_success", requestId, {
        method, uriBased: true,
        entryCount: result.entries ? result.entries.length : undefined,
        fileSize: typeof result.size === "number" ? result.size : undefined
      });
      return result;
    } catch (error) {
      diagnose("native_" + method + "_failure", requestId, {
        method, uriBased: true, errorName: error && error.name || "Error",
        errorMessage: String(error && error.message || error)
      });
      throw error;
    }
  }

  function listEntries(directoryUri, requestId) {
    const startedAt = Date.now();
    diagnose("directory_entries_native_call", requestId, { method: "listImagePackDirectory", uriBased: true });
    const entries = nativeJson("listImagePackDirectory", requestId, directoryUri).entries || [];
    diagnose("directory_entries_returned", requestId, {
      entryCount: entries.length, bridgeElapsedMs: Date.now() - startedAt
    });
    return entries;
  }

  // FAITH 3.0.0 uses <input type="file" webkitdirectory multiple>.
  // This is deliberately a FileList adapter, not a DirectoryHandle alias.
  // Hard safety ceilings are deliberately independent of any caller policy.
  // A request without data-wayward-file-policy always receives every file.
  const MAX_UPLOAD_FILES = 100000;
  const MAX_UPLOAD_ENTRIES = 200000;
  const MAX_UPLOAD_DEPTH = 128;
  const ENUMERATION_YIELD_INTERVAL = 256;
  // The OS picker itself is single-flight, but completed selections may be
  // enumerated concurrently. Each enumeration owns an independent control.
  let nativePickerBusy = false;
  const activeUploadControls = new Set();

  function readRequestFilePolicy(input) {
    const raw = input && input.getAttribute
      ? input.getAttribute("data-wayward-file-policy") : null;
    if (raw == null || raw === "") return null;
    let value;
    try { value = JSON.parse(raw); }
    catch (_) { throw domError("TypeError", "The requested file policy is not valid JSON."); }
    if (!value || typeof value !== "object" || Array.isArray(value)) {
      throw domError("TypeError", "The requested file policy must be an object.");
    }
    const allowed = new Set(["includeExtensions", "excludeExtensions", "includePathPrefixes", "excludePathPrefixes", "maxFiles"]);
    for (const key of Object.keys(value)) {
      if (!allowed.has(key)) throw domError("TypeError", "The requested file policy contains an unsupported condition.");
    }
    function stringList(key, validate) {
      if (value[key] == null) return null;
      if (!Array.isArray(value[key]) || value[key].length < 1 || value[key].length > 128) {
        throw domError("TypeError", "The requested file policy contains an invalid list.");
      }
      return value[key].map(item => {
        if (typeof item !== "string" || item.length > 128 || !validate(item)) {
          throw domError("TypeError", "The requested file policy contains an invalid value.");
        }
        return item;
      });
    }
    const validExtension = item => /^[a-zA-Z0-9][a-zA-Z0-9._+-]{0,31}$/.test(item.replace(/^\./, ""));
    const validPathPrefix = item => item.length > 0 && !item.startsWith("/") &&
      !item.includes("\\") && !/[\0-\x1f]/.test(item) &&
      item.split("/").every(segment => segment && segment !== "." && segment !== "..");
    const policy = {
      includeExtensions: stringList("includeExtensions", validExtension),
      excludeExtensions: stringList("excludeExtensions", validExtension),
      includePathPrefixes: stringList("includePathPrefixes", validPathPrefix),
      excludePathPrefixes: stringList("excludePathPrefixes", validPathPrefix),
      maxFiles: value.maxFiles == null ? MAX_UPLOAD_FILES : value.maxFiles
    };
    if (!Number.isInteger(policy.maxFiles) || policy.maxFiles < 1 || policy.maxFiles > MAX_UPLOAD_FILES) {
      throw domError("TypeError", "The requested file policy maxFiles value is outside the supported range.");
    }
    for (const key of ["includeExtensions", "excludeExtensions"]) {
      if (policy[key]) policy[key] = policy[key].map(item => item.toLowerCase().replace(/^\./, ""));
    }
    return Object.freeze(policy);
  }

  function matchesFilePolicy(relativePath, name, policy) {
    if (!policy) return true;
    const path = String(relativePath || "");
    const lowerName = String(name || "").toLowerCase();
    const dot = lowerName.lastIndexOf(".");
    const extension = dot > 0 ? lowerName.slice(dot + 1) : "";
    if (policy.includeExtensions && !policy.includeExtensions.includes(extension)) return false;
    if (policy.excludeExtensions && policy.excludeExtensions.includes(extension)) return false;
    if (policy.includePathPrefixes && !policy.includePathPrefixes.some(prefix =>
      path === prefix || path.startsWith(prefix + "/"))) return false;
    if (policy.excludePathPrefixes && policy.excludePathPrefixes.some(prefix =>
      path === prefix || path.startsWith(prefix + "/"))) return false;
    return true;
  }

  function checkUploadCancelled(control) {
    if (control && control.cancelled) throw domError("AbortError", "The folder processing request was cancelled.");
  }

  async function yieldUploadWork(control) {
    checkUploadCancelled(control);
    await new Promise(resolve => setTimeout(resolve, 0));
    checkUploadCancelled(control);
  }

  // Optional cancellation API. The request ID is scoped to the active picker
  // operation; cancellation never changes policy or state for a later request.
  window.cancelWaywardDirectoryUpload = function(requestId) {
    let active = null;
    if (requestId) {
      for (const candidate of activeUploadControls) {
        if (candidate.requestId && String(requestId) === candidate.requestId) {
          active = candidate;
          break;
        }
      }
    } else if (activeUploadControls.size === 1) {
      active = activeUploadControls.values().next().value;
    }
    if (!active) return false;
    active.cancelled = true;
    if (active.nativeDirectoryIds && typeof pendingDirectoryLists !== "undefined") {
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
    return true;
  };

  function safePathSegment(value) {
    const name = String(value || "");
    if (!name || name === "." || name === ".." || /[\/\\\0]/.test(name)) {
      throw domError("NotReadableError", "The selected SAF provider returned an invalid path segment.");
    }
    return name;
  }

  function mimeType(value) {
    const type = String(value || "").trim().toLowerCase();
    return /^[a-z0-9!#$&^_.+-]+\/[a-z0-9!#$&^_.+-]+$/.test(type) ? type : "";
  }

  function makeSafBackedFile(entry, relativePath, requestId) {
    const metadata = {
      name: safePathSegment(entry.name),
      size: Number.isFinite(Number(entry.size)) && Number(entry.size) >= 0
        ? Number(entry.size) : null,
      type: mimeType(entry.mimeType),
      lastModified: Number.isFinite(Number(entry.lastModified)) && Number(entry.lastModified) >= 0
        ? Number(entry.lastModified) : 0,
      webkitRelativePath: relativePath
    };
    const documentUri = String(entry.uri || "");
    if (!documentUri.startsWith("content://")) {
      throw domError("SecurityError", "The SAF provider returned a non-content file URI.");
    }

    function decodeChunk(encoded) {
      let binary;
      try { binary = atob(String(encoded || "")); }
      catch (_) { throw domError("NotReadableError", "The SAF file contents were not valid Base64."); }
      const bytes = new Uint8Array(binary.length);
      for (let index = 0; index < binary.length; index++) bytes[index] = binary.charCodeAt(index);
      return bytes;
    }

    async function readAllBytes(file) {
      const reader = file.stream().getReader();
      const chunks = [];
      let total = 0;
      try {
        while (true) {
          const result = await reader.read();
          if (result.done) break;
          const chunk = result.value instanceof Uint8Array
            ? result.value : new Uint8Array(result.value);
          total += chunk.byteLength;
          if (total > 32 * 1024 * 1024) {
            await reader.cancel();
            throw domError("QuotaExceededError", "The image file exceeds the 32 MiB bridge limit.");
          }
          chunks.push(chunk);
        }
      } finally {
        try { reader.releaseLock(); } catch (_) {}
      }
      const bytes = new Uint8Array(total);
      let offset = 0;
      for (const chunk of chunks) {
        bytes.set(chunk, offset);
        offset += chunk.byteLength;
      }
      return bytes;
    }

    class SafBackedFile extends Blob {
      constructor(start, end, contentType) {
        super([], { type: contentType === undefined ? metadata.type : contentType });
        this._start = start;
        this._end = end;
        Object.defineProperties(this, {
          name: { value: metadata.name, enumerable: true },
          size: {
            get: () => Math.max(0, (end === null ? (metadata.size === null ? 0 : metadata.size) : end) - start),
            enumerable: true
          },
          lastModified: { value: metadata.lastModified, enumerable: true },
          webkitRelativePath: { value: metadata.webkitRelativePath, enumerable: true }
        });
      }
      async arrayBuffer() {
        const bytes = await readAllBytes(this);
        return bytes.buffer;
      }
      async text() {
        const reader = this.stream().getReader();
        const decoder = new TextDecoder();
        let output = "";
        try {
          while (true) {
            const result = await reader.read();
            if (result.done) break;
            output += decoder.decode(result.value, { stream: true });
          }
          output += decoder.decode();
          return output;
        } finally {
          try { reader.releaseLock(); } catch (_) {}
        }
      }
      async bytes() {
        return readAllBytes(this);
      }
      stream() {
        const file = this;
        let streamId = null;
        let finished = false;
        const requestedLength = file._end === null ? -1 : Math.max(0, file._end - file._start);
        return new ReadableStream({
          start(controller) {
            try {
              const opened = nativeJson("openImagePackFileStream", requestId,
                documentUri, file._start, requestedLength);
              streamId = String(opened.streamId || "");
              if (!streamId) throw domError("NotReadableError", "The SAF provider did not open a file stream.");
              if (opened.size >= 0 && metadata.size === null && file._start === 0) {
                metadata.size = Number(opened.size);
              }
            } catch (error) {
              finished = true;
              controller.error(error);
            }
          },
          pull(controller) {
            if (finished) return;
            try {
              if (!streamId) throw domError("NotReadableError", "The SAF file stream is unavailable.");
              const chunk = nativeJson("readImagePackFileStreamChunk", requestId, streamId, 65536);
              const bytes = decodeChunk(chunk.data);
              if (bytes.byteLength > 0) controller.enqueue(bytes);
              if (chunk.done === true) {
                finished = true;
                streamId = null;
                controller.close();
              } else if (bytes.byteLength === 0) {
                throw domError("NotReadableError", "The SAF provider returned an empty non-final file chunk.");
              }
            } catch (error) {
              if (streamId) {
                try { nativeJson("closeImagePackFileStream", requestId, streamId); } catch (_) {}
              }
              streamId = null;
              finished = true;
              controller.error(error);
            }
          },
          cancel() {
            finished = true;
            if (streamId) {
              try { nativeJson("closeImagePackFileStream", requestId, streamId); } catch (_) {}
            }
            streamId = null;
          }
        });
      }
      slice(start, end, contentType) {
        const size = this.size;
        const relativeStart = start === undefined ? 0 : Number(start);
        const relativeEnd = end === undefined ? size : Number(end);
        const from = relativeStart < 0
          ? Math.max(size + relativeStart, 0) : Math.min(relativeStart, size);
        const to = relativeEnd < 0
          ? Math.max(size + relativeEnd, 0) : Math.min(relativeEnd, size);
        const absoluteStart = this._start + Math.trunc(Number.isFinite(from) ? from : 0);
        const absoluteEnd = end === undefined && this._end === null
          ? null
          : this._start + Math.trunc(Math.max(from, Number.isFinite(to) ? to : size));
        return new SafBackedFile(absoluteStart, absoluteEnd,
          contentType === undefined ? "" : String(contentType));
      }
    }
    Object.setPrototypeOf(SafBackedFile.prototype, File.prototype);
    return new SafBackedFile(0, metadata.size, metadata.type);
  }

  async function* iterateDirectoryEntries(directoryUri, requestId, control) {
    const directoryRequestId = String(requestId || "saf") + "-dir-" + (++nextDirectoryListId);
    const state = { packet: null, waiter: null, expectedBatch: 0 };
    pendingDirectoryLists.set(directoryRequestId, state);
    if (control && control.nativeDirectoryIds) control.nativeDirectoryIds.add(directoryRequestId);
    let completed = false;
    try {
      if (typeof diagnose === "function") diagnose("directory_entries_native_call", requestId, {
        directoryRequestId, method: "listImagePackDirectoryForRequest", uriPresent: !!directoryUri
      });
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
        if (state.expectedBatch === 0) {
          if (typeof diagnose === "function") diagnose("directory_entries_first_batch_received", requestId, {
            directoryRequestId, batchIndex: packet.batchIndex, entryCount: entries.length,
            done: packet.done === true
          });
        }
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

  async function enumerateSafTree(rootUri, rootName, requestId, policy, control) {
    const rootSegment = safePathSegment(rootName);
    const visited = new Set();
    const visitedFileUris = new Set();
    const files = [];
    let entryCount = 0;
    let directoryCount = 0;
    let scannedFileCount = 0;
    let fileObjectBuildMs = 0;
    let excludedCount = 0;
    let sinceYield = 0;
    const startedAt = Date.now();
    if (typeof diagnose === "function") diagnose("directory_enumeration_started", requestId, {
      rootUriPresent: !!rootUri, rootNamePresent: !!rootName
    });

    async function visit(directoryUri, prefix, depth) {
      checkUploadCancelled(control);
      if (depth > MAX_UPLOAD_DEPTH) {
        throw domError("QuotaExceededError", "The selected folder is nested too deeply.");
      }
      if (visited.has(directoryUri)) {
        throw domError("NotReadableError", "The SAF provider returned a repeated directory URI.");
      }
      visited.add(directoryUri);
      directoryCount++;
      // Sort the parsed result in place; cloning the provider's entire entry
      // array doubled peak references for large directories.
      for await (const child of iterateDirectoryEntries(directoryUri, requestId, control)) {
        checkUploadCancelled(control);
        entryCount++;
        sinceYield++;
        if (entryCount > MAX_UPLOAD_ENTRIES) {
          throw domError("QuotaExceededError", "The selected folder contains too many entries.");
        }
        const name = safePathSegment(child.name);
        const relativePath = prefix + "/" + name;
        if (child.kind === "directory") {
          if (!child.uri || !String(child.uri).startsWith("content://")) {
            throw domError("SecurityError", "The SAF provider returned an invalid directory URI.");
          }
          await visit(String(child.uri), relativePath, depth + 1);
        } else if (child.kind === "file") {
          scannedFileCount++;
          if (scannedFileCount > MAX_UPLOAD_FILES) {
            throw domError("QuotaExceededError", "The selected folder contains too many files.");
          }
          const fileUri = String(child.uri || "");
          if (!fileUri.startsWith("content://")) {
            throw domError("SecurityError", "The SAF provider returned an invalid file URI.");
          }
          if (visitedFileUris.has(fileUri)) {
            throw domError("NotReadableError", "The SAF provider returned a repeated file URI.");
          }
          visitedFileUris.add(fileUri);
          if (matchesFilePolicy(relativePath, name, policy)) {
            const limit = policy ? policy.maxFiles : MAX_UPLOAD_FILES;
            if (files.length >= limit) {
              throw domError("QuotaExceededError", "The selected folder exceeds the requested file-count limit.");
            }
            const fileBuildStartedAt = Date.now();
            files.push(makeSafBackedFile(child, relativePath, requestId));
            fileObjectBuildMs += Date.now() - fileBuildStartedAt;
          } else {
            excludedCount++;
          }
        } else {
          throw domError("NotReadableError", "The SAF provider returned an unknown entry kind.");
        }
        if (sinceYield >= ENUMERATION_YIELD_INTERVAL) {
          sinceYield = 0;
          await yieldUploadWork(control);
        }
      }
      // Let WebView input, paint, and cancellation tasks run between provider
      // directory queries, even when each directory is small.
      await yieldUploadWork(control);
    }

    try {
    await visit(rootUri, rootSegment, 0);
    checkUploadCancelled(control);
    diagnose("directory_enumeration_completed", requestId, {
      directoryCount, entryCount, scannedFileCount, fileCount: files.length,
      fileObjectBuildMs, excludedCount, policyApplied: !!policy,
      elapsedMs: Date.now() - startedAt
    });
    return files;
    } finally {
      try { host.listImagePackDirectoryForRequest(nativeCapability, "__WAYWARD_DIR_RELEASE__:", requestId); } catch (_) {}
    }
  }

  function makeFileList(files) {
    const prototype = typeof FileList === "function" ? FileList.prototype : Object.prototype;
    const list = Object.create(prototype);
    Object.defineProperty(list, "length", {
      configurable: false, enumerable: true, get: () => files.length
    });
    Object.defineProperty(list, "item", {
      configurable: false, enumerable: false,
      value(index) {
        const position = Number(index);
        return Number.isInteger(position) && position >= 0 && position < files.length
          ? files[position] : null;
      }
    });
    Object.defineProperty(list, Symbol.iterator, {
      configurable: false, enumerable: false,
      value: function* () { yield* files; }
    });
    Object.defineProperty(list, Symbol.toStringTag, { value: "FileList" });
    files.forEach((file, index) => {
      Object.defineProperty(list, index, {
        configurable: false, enumerable: true, value: file
      });
    });
    return list;
  }

  function isDirectoryUploadInput(input) {
    if (!input || String(input.type || "").toLowerCase() !== "file") return false;
    try {
      return input.webkitdirectory === true ||
        (typeof input.hasAttribute === "function" && input.hasAttribute("webkitdirectory"));
    } catch (_) {
      return false;
    }
  }

  function findDirectoryUploadInput(event) {
    const path = event && typeof event.composedPath === "function"
      ? event.composedPath() : [event && event.target];
    for (const candidate of path) {
      if (!candidate || candidate.nodeType !== 1) continue;
      if (isDirectoryUploadInput(candidate)) return candidate;
      if (String(candidate.tagName || "").toUpperCase() === "LABEL") {
        try {
          if (isDirectoryUploadInput(candidate.control)) return candidate.control;
          const nested = candidate.querySelector &&
            candidate.querySelector('input[type="file"][webkitdirectory]');
          if (isDirectoryUploadInput(nested)) return nested;
        } catch (_) {}
      }
    }
    return null;
  }

  function dispatchInputEvent(input, name, bubbles) {
    const view = input.ownerDocument && input.ownerDocument.defaultView;
    const EventType = view && view.Event || Event;
    input.dispatchEvent(new EventType(name, { bubbles: !!bubbles, composed: true }));
  }

  // Retain the successful selection after dispatch; FAITH scans asynchronously.
  const activeFileLists = new WeakMap();

  function dispatchDirectoryFileList(input, files, requestId) {
    const fileList = makeFileList(files);
    const previous = activeFileLists.get(input);
    const previousDescriptor = Object.getOwnPropertyDescriptor(input, "files");
    try {
      Object.defineProperty(input, "files", { configurable: true, enumerable: true, get: () => fileList });
      activeFileLists.set(input, fileList);
      diagnose("webkitdirectory_filelist_constructed", requestId, { fileCount: files.length });
      // Match a successful native file selection: input first, then change.
      dispatchInputEvent(input, "input", true);
      dispatchInputEvent(input, "change", true);
      diagnose("webkitdirectory_input_event_dispatched", requestId, { fileCount: files.length });
      diagnose("webkitdirectory_change_event_dispatched", requestId, { fileCount: files.length,
        faithScannerTrigger: "change" });
      diagnose("faith_scanner_change_trigger_dispatched", requestId, { fileCount: files.length,
        listenerDispatchCompleted: true });
    } catch (error) {
      if (previous) {
        Object.defineProperty(input, "files", { configurable: true, enumerable: true, get: () => previous });
        activeFileLists.set(input, previous);
      } else {
        if (previousDescriptor) Object.defineProperty(input, "files", previousDescriptor); else delete input.files;
        activeFileLists.delete(input);
      }
      throw error;
    }
  }

  function dispatchDirectoryCancel(input) {
    try { dispatchInputEvent(input, "cancel", false); } catch (_) {}
  }

  async function chooseDirectoryForFileInput(input) {
    if (nativePickerBusy) {
      diagnose("webkitdirectory_upload_rejected_busy", "", { result: "invalid_state" });
      return;
    }
    nativePickerBusy = true;
    let requestId = "";
    const control = { requestId: "", cancelled: false, nativeDirectoryIds: new Set() };
    activeUploadControls.add(control);
    showUploadProgress("폴더 선택 창을 여는 중…", "picker", "", {});
    try {
      const policy = readRequestFilePolicy(input);
      diagnose("webkitdirectory_upload_started", "", {
        multiple: input.multiple === true,
        frameOrigin: String(location.origin || ""),
        isTopFrame: window === window.top
      });
      const selection = await nativeRequest("read", "");
      requestId = String(selection.requestId || "");
      control.requestId = requestId;
      diagnose("webkitdirectory_picker_await_continued", requestId, {
        selectionOk: !!(selection && selection.ok),
        documentUriPresent: !!(selection && selection.documentUri),
        treeUriPresent: !!(selection && selection.uri)
      });
      showUploadProgress("폴더 선택 완료 · 접근 권한 확인 중…", "selected", requestId, {
        documentUriPresent: !!selection.documentUri, treeUriPresent: !!selection.uri
      });
      // The OS picker is closed. The independent SAF tree scans can now overlap.
      nativePickerBusy = false;
      checkUploadCancelled(control);
      if (!selection.documentUri || !selection.uri) {
        throw domError("NotReadableError", "Android did not return a SAF tree and root document URI.");
      }
      showUploadProgress("파일 탐색 중…", "scanning", requestId, {});
      const files = await enumerateSafTree(
        String(selection.documentUri), String(selection.name || "Image packs"), requestId, policy, control);
      diagnose("webkitdirectory_enumeration_result", requestId, { fileCount: files.length });
      if (!files.length) {
        throw domError("NotFoundError", "The selected folder contains no files.");
      }
      showUploadProgress("파일 목록 구성 중…", "building", requestId, { fileCount: files.length });
      dispatchDirectoryFileList(input, files, requestId);
      diagnose("webkitdirectory_upload_dispatched", requestId, {
        fileCount: files.length, rootName: String(selection.name || ""),
        policyApplied: !!policy, multiple: input.multiple === true
      });
      showUploadProgress("게임 전달 완료 · 이미지팩 스캔을 시작했습니다.", "complete", requestId,
        { fileCount: files.length });
    } catch (error) {
      const name = error && error.name || "NotReadableError";
      const message = String(error && error.message || error).slice(0, 240);
      diagnose("webkitdirectory_upload_failed", requestId, { errorName: name, message });
      showUploadProgress(name === "AbortError" ? "폴더 선택이 취소되었습니다." : "폴더 탐색 실패: " + message,
        name === "AbortError" ? "cancelled" : "failed", requestId, { errorName: name });
      if (name === "AbortError") dispatchDirectoryCancel(input);
      try {
        const view = input.ownerDocument && input.ownerDocument.defaultView || window;
        view.dispatchEvent(new view.CustomEvent("waywardfoldererror", {
          detail: { name, message }, bubbles: false
        }));
      } catch (_) {}
    } finally {
      activeUploadControls.delete(control);
      nativePickerBusy = false;
    }
  }

  function installWebkitDirectoryAdapter() {
    if (!window.document || window.__waywardWebkitDirectoryAdapterInstalled) return;
    const onClick = event => {
      const input = findDirectoryUploadInput(event);
      if (!input || input.disabled) return;
      event.preventDefault();
      event.stopImmediatePropagation();
      chooseDirectoryForFileInput(input);
    };
    document.addEventListener("click", onClick, true);

    // Some applications invoke showPicker() rather than click() or a label.
    try {
      const prototype = window.HTMLInputElement && window.HTMLInputElement.prototype;
      const original = prototype && prototype.showPicker;
      if (prototype && !prototype.__waywardDirectoryShowPickerPatched) {
        Object.defineProperty(prototype, "__waywardDirectoryShowPickerPatched", { value: true });
        Object.defineProperty(prototype, "showPicker", {
          configurable: true, writable: true,
          value: function() {
            if (isDirectoryUploadInput(this)) {
              if (!this.disabled) chooseDirectoryForFileInput(this);
              return;
            }
            if (typeof original === "function") return original.call(this);
            throw domError("NotSupportedError", "The native input picker is unavailable.");
          }
        });
      }
    } catch (error) {
      diagnose("webkitdirectory_show_picker_patch_unavailable", "", {
        errorName: error && error.name || "Error"
      });
    }

    window.__waywardWebkitDirectoryAdapterInstalled = true;
    diagnose("webkitdirectory_adapter_installed", "", {
      installed: true, documentStart: document.readyState === "loading",
      sameOriginFrame: true
    });
  }

  function queryHandlePermission(permissions, options) {
    const mode = options && options.mode !== undefined ? options.mode : "read";
    if (mode !== "read" && mode !== "readwrite") throw new TypeError("Invalid permission mode.");
    try {
      const info = nativeJson("getImagePackFolderInfo", permissions.requestId || "");
      if (info.uri !== permissions.treeUri) return "denied";
      return mode === "readwrite" && info.writable !== true ? "denied" : "granted";
    } catch (_) {
      return "denied";
    }
  }

  function makeDirectory(uri, name, permissions) {
    const handle = {
      kind: "directory",
      name,
      async isSameEntry(other) {
        return !!other && other.kind === "directory" &&
          rootByHandle.get(other) === rootByHandle.get(handle) &&
          other.__waywardSafDocumentUri === uri;
      },
      async queryPermission(options) {
        return queryHandlePermission(permissions, options);
      },
      async requestPermission(options) {
        const mode = options && options.mode !== undefined ? options.mode : "read";
        if (mode !== "read" && mode !== "readwrite") throw new TypeError("Invalid permission mode.");
        const current = await this.queryPermission({ mode });
        if (current === "granted") return "granted";
        try {
          const selection = await nativeRequest(mode, uri);
          if (selection.uri !== permissions.treeUri) return "denied";
          if (mode === "readwrite" && selection.writable !== true) return "denied";
          if (mode === "readwrite") permissions.writable = true;
          return await this.queryPermission({ mode });
        } catch (error) { if (error && error.name === "AbortError") return "denied"; throw error; }
      },
      async getDirectoryHandle(child, options) {
        diagnose("directory_handle_get_directory_started", permissions.requestId, { method: "getDirectoryHandle" });
        child = childName(child);
        const entry = listEntries(uri, permissions.requestId).find(item => item.name === child);
        if (!entry) {
          if (!options || options.create !== true) throw domError("NotFoundError", "Directory not found: " + child);
          if (!permissions.writable) throw domError("NotAllowedError", "The selected SAF tree is read-only.");
          const created = nativeJson("createImagePackDirectory", permissions.requestId, uri, child);
          return makeDirectory(created.uri, child, permissions);
        }
        if (entry.kind !== "directory") throw domError("TypeMismatchError", "The selected entry is not a directory.");
        return makeDirectory(entry.uri, child, permissions);
      },
      async getFileHandle(child, options) {
        diagnose("directory_handle_get_file_started", permissions.requestId, { method: "getFileHandle" });
        child = childName(child);
        const entry = listEntries(uri, permissions.requestId).find(item => item.name === child);
        if (!entry) {
          if (!options || options.create !== true) throw domError("NotFoundError", "File not found: " + child);
          if (!permissions.writable) throw domError("NotAllowedError", "The selected SAF tree is read-only.");
          const created = nativeJson("createImagePackFile", permissions.requestId,
            uri, child, "application/octet-stream");
          return makeFile(created.uri, child, { mimeType: "application/octet-stream" }, permissions);
        }
        if (entry.kind !== "file") throw domError("TypeMismatchError", "The selected entry is not a file.");
        return makeFile(entry.uri, child, entry, permissions);
      },
      async removeEntry(child, options) {
        child = childName(child);
        if (!permissions.writable) throw domError("NotAllowedError", "The selected SAF tree is read-only.");
        const entry = listEntries(uri, permissions.requestId).find(item => item.name === child);
        if (!entry) throw domError("NotFoundError", "Entry not found: " + child);
        if (entry.kind === "directory" && (!options || options.recursive !== true) &&
            listEntries(entry.uri, permissions.requestId).length > 0) {
          throw domError("InvalidModificationError", "A non-empty directory requires recursive removal.");
        }
        nativeJson("deleteImagePackEntry", permissions.requestId, entry.uri);
      },
      async *entries() {
        diagnose("directory_handle_entries_started", permissions.requestId, { uriBased: true });
        try {
          const children = listEntries(uri, permissions.requestId);
          diagnose("directory_handle_entries_succeeded", permissions.requestId, { entryCount: children.length });
          for (const entry of children) {
            const child = entry.kind === "directory"
              ? makeDirectory(entry.uri, entry.name, permissions)
              : makeFile(entry.uri, entry.name, entry, permissions);
            yield [entry.name, child];
          }
        } catch (error) {
          diagnose("directory_handle_entries_failed", permissions.requestId, {
            errorName: error && error.name || "Error"
          });
          throw error;
        }
      },
      [Symbol.asyncIterator]() {
        return this.entries();
      },
      async *values() {
        for await (const pair of this.entries()) yield pair[1];
      },
      async *keys() {
        for await (const pair of this.entries()) yield pair[0];
      },
      async resolve(other) {
        if (!other || rootByHandle.get(other) !== rootByHandle.get(handle)) return null;
        const result = nativeJson("resolveImagePackEntry", permissions.requestId,
          uri, handleUri(other));
        return Array.isArray(result.path) ? result.path : null;
      }
    };
    Object.defineProperty(handle, "__waywardSafDocumentUri", { value: uri });
    rootByHandle.set(handle, permissions.root);
    return handle;
  }

  function makeFile(uri, name, metadata, permissions) {
    const handle = {
      kind: "file",
      name,
      async isSameEntry(other) {
        return !!other && other.kind === "file" &&
          rootByHandle.get(other) === rootByHandle.get(handle) &&
          other.__waywardSafDocumentUri === uri;
      },
      async queryPermission(options) {
        return queryHandlePermission(permissions, options);
      },
      async requestPermission(options) {
        return this.queryPermission(options);
      },
      async getFile() {
        diagnose("file_handle_get_file_started", permissions.requestId, { uriBased: true });
        const payload = nativeJson("readImagePackFile", permissions.requestId, uri);
        const binary = atob(payload.data || "");
        const bytes = new Uint8Array(binary.length);
        for (let index = 0; index < binary.length; index++) bytes[index] = binary.charCodeAt(index);
        const file = new File([bytes], name, {
          type: payload.mimeType || metadata.mimeType || "application/octet-stream",
          lastModified: Number(payload.lastModified || metadata.lastModified || 0)
        });
        diagnose("file_handle_get_file_succeeded", permissions.requestId, { fileSize: payload.size || 0 });
        return file;
      },
      async createWritable(options) {
        if (!permissions.writable) throw domError("NotAllowedError", "The selected SAF tree is read-only.");
        let buffer = new Uint8Array(0);
        if (options && options.keepExistingData === true) { const existing = await this.getFile(); buffer = new Uint8Array(await existing.arrayBuffer()); }
        let position = 0, closed = false, aborted = false;
        const ensureOpen = () => { if (closed || aborted) throw domError("InvalidStateError", "The writable stream is no longer open."); };
        const seek = async value => { ensureOpen(); const next = Number(value); if (!Number.isFinite(next) || next < 0) throw new TypeError("The seek position must be a non-negative finite number."); position = Math.floor(next); };
        const truncate = async value => {
          ensureOpen(); const size = Number(value);
          if (!Number.isFinite(size) || size < 0) throw new TypeError("The truncate size must be a non-negative finite number.");
          const length = Math.floor(size); if (length > 32 * 1024 * 1024) throw domError("QuotaExceededError", "The image file exceeds 32 MiB.");
          const resized = new Uint8Array(length); resized.set(buffer.subarray(0, length)); buffer = resized; position = Math.min(position, length);
        };
        const write = async value => {
          ensureOpen();
          if (value && typeof value === "object" && value.type === "seek") return seek(value.position);
          if (value && typeof value === "object" && value.type === "truncate") return truncate(value.size);
          if (value && typeof value === "object" && value.type === "write") { if (value.position !== undefined) await seek(value.position); value = value.data; }
          let bytes;
          if (value instanceof Blob) bytes = new Uint8Array(await value.arrayBuffer());
          else if (typeof value === "string") bytes = new TextEncoder().encode(value);
          else if (value instanceof ArrayBuffer) bytes = new Uint8Array(value);
          else if (ArrayBuffer.isView(value)) bytes = new Uint8Array(value.buffer, value.byteOffset, value.byteLength);
          else throw new TypeError("Unsupported SAF writable data.");
          const required = position + bytes.length; if (required > 32 * 1024 * 1024) throw domError("QuotaExceededError", "The image file exceeds 32 MiB.");
          const next = new Uint8Array(Math.max(buffer.length, required)); next.set(buffer); next.set(bytes, position); buffer = next; position = required;
        };
        return { write, seek, truncate,
          async close() {
            ensureOpen(); let binary = "";
            for (let offset = 0; offset < buffer.length; offset += 0x8000) binary += String.fromCharCode(...buffer.subarray(offset, offset + 0x8000));
            nativeJson("writeImagePackFile", permissions.requestId, uri, metadata.mimeType || "application/octet-stream", btoa(binary));
            closed = true;
          },
          async abort() { if (closed || aborted) return; aborted = true; buffer = new Uint8Array(0); }
        };
      }
    };
    Object.defineProperty(handle, "__waywardSafDocumentUri", { value: uri });
    rootByHandle.set(handle, permissions.root);
    return handle;
  }

  const showDirectoryPicker = async function(options) {
    options = options || {};
    const mode = options.mode === undefined ? "readwrite" : options.mode;
    if (mode !== "read" && mode !== "readwrite") throw new TypeError("Invalid directory picker mode.");
    if (options.id !== undefined && options.id !== null && String(options.id).length > 0) {
      throw domError("NotSupportedError", "Android SAF cannot preserve the File System Access picker id.");
    }
    let initialDocumentUri = "";
    if (options.startIn !== undefined && options.startIn !== null) {
      if (typeof options.startIn === "string" || !options.startIn.__waywardSafDocumentUri ||
          options.startIn.kind !== "directory") {
        throw domError("NotSupportedError", "Android SAF can only honor startIn from a previously selected SAF directory handle.");
      }
      initialDocumentUri = handleUri(options.startIn);
    }
    const selection = await nativeRequest(mode, initialDocumentUri);
    if (mode === "readwrite" && selection.writable !== true) {
      throw domError("NotAllowedError", "Android did not grant write access to the selected folder.");
    }
    if (!selection.documentUri || !selection.uri) {
      throw domError("NotReadableError", "Android did not return the selected SAF tree and root document URIs.");
    }
    const permissions = {
      root: selection.uri, treeUri: selection.uri,
      writable: selection.writable === true, requestId: selection.requestId || ""
    };
    diagnose("directory_handle_created", permissions.requestId, {
      directoryHandle: true, handleKind: "directory", writable: permissions.writable,
      documentUriPresent: true
    });
    return makeDirectory(selection.documentUri, selection.name || "Image packs", permissions);
  };

  try {
    Object.defineProperty(window, "showDirectoryPicker", {
      configurable: true, enumerable: true, writable: true, value: showDirectoryPicker
    });
  } catch (error) {
    try { window.showDirectoryPicker = showDirectoryPicker; }
    catch (assignmentError) {
      diagnose("show_directory_picker_registration_failed", "", {
        errorName: assignmentError && assignmentError.name || "Error"
      });
      return;
    }
  }

  if (typeof window.showDirectoryPicker !== "function") {
    diagnose("show_directory_picker_registration_failed", "", { result: "missing_function" });
    return;
  }
  window.__waywardNativeImagePackPickerInstalled = true;
  installWebkitDirectoryAdapter();
  diagnose("imagepack_adapter_installed", "", {
    installed: true, bridgeAvailable: true, origin: String(location.origin || ""),
    isTopFrame: window === window.top, storageIdentity: "content-uri"
  });

  window.__waywardReadPersistedImagePackRoot = function() {
    const info = nativeJson("getImagePackFolderInfo", "");
    if (!info.documentUri || !info.uri) throw domError("NotFoundError", "No persisted SAF root is available.");
    const permissions = {
      root: info.uri, treeUri: info.uri, writable: info.writable === true, requestId: ""
    };
    return makeDirectory(info.documentUri, info.name || "Image packs", permissions);
  };
})();