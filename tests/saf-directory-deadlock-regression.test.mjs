import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";

const source = readFileSync(new URL("../app/src/main/assets/imagepack-folder-picker.js", import.meta.url), "utf8");
const fixture = JSON.parse(readFileSync(new URL("./fixtures/wayward-file-picker-source-contract.json", import.meta.url), "utf8"));
const iteratorStart = source.indexOf("  async function* iterateDirectoryEntries(");
const enumerateStart = source.indexOf("  async function enumerateSafTree(", iteratorStart);
const makeListStart = source.indexOf("  function makeFileList(", enumerateStart);
assert.ok(iteratorStart >= 0 && enumerateStart > iteratorStart && makeListStart > enumerateStart);
const iteratorSource = source.slice(iteratorStart, enumerateStart);
const enumerateSource = source.slice(enumerateStart, makeListStart);

class TwoWorkerNativeSaf {
  constructor(tree, { ackTimeoutMs = 30, queryDelayMs = 0, failUri = "", failName = "SecurityError" } = {}) {
    this.tree = tree;
    this.ackTimeoutMs = ackTimeoutMs;
    this.queryDelayMs = queryDelayMs;
    this.optionsFailUri = failUri;
    this.optionsFailName = failName;
    this.active = new Map();
    this.queue = [];
    this.ackTimeouts = 0;
    this.maxActive = 0;
    this.acknowledgements = 0;
    this.startedUris = [];
    this.cancelled = 0;
    this.released = 0;
    this.deliver = null;
  }

  call(_capability, command, requestId) {
    if (command.startsWith("__WAYWARD_DIR_START__:")) {
      const uri = command.slice("__WAYWARD_DIR_START__:".length);
      if (this.optionsFailUri === uri) return JSON.stringify({
        ok: false, name: this.optionsFailName || "SecurityError", message: "provider access denied"
      });
      if (!this.tree.has(uri)) return JSON.stringify({ ok: false, name: "NotFoundError" });
      const task = {
        requestId, uri, entries: this.tree.get(uri), offset: 0, batchIndex: 0,
        waiting: false, currentDone: false, timer: null, queryTimer: null, cancelled: false
      };
      this.startedUris.push(uri);
      this.queue.push(task);
      this.pump();
      return JSON.stringify({ ok: true });
    }
    if (command.startsWith("__WAYWARD_DIR_ACK__:")) {
      const batchIndex = Number(command.slice("__WAYWARD_DIR_ACK__:".length));
      const task = this.active.get(requestId);
      if (!task || !task.waiting || task.batchIndex !== batchIndex) {
        return JSON.stringify({ ok: false, name: "AbortError" });
      }
      clearTimeout(task.timer);
      task.timer = null;
      task.waiting = false;
      this.acknowledgements++;
      if (task.currentDone) {
        this.finish(task);
        this.pump();
      } else {
        task.batchIndex++;
        this.sendBatch(task);
      }
      return JSON.stringify({ ok: true });
    }
    if (command === "__WAYWARD_DIR_CANCEL__:") {
      const queuedIndex = this.queue.findIndex(task => task.requestId === requestId);
      if (queuedIndex >= 0) this.queue.splice(queuedIndex, 1);
      const task = this.active.get(requestId);
      if (task) {
        task.cancelled = true;
        clearTimeout(task.timer);
        clearTimeout(task.queryTimer);
        this.active.delete(requestId);
      }
      this.cancelled++;
      this.pump();
      return JSON.stringify({ ok: true });
    }
    if (command === "__WAYWARD_DIR_RELEASE__:") {
      this.released++;
      return JSON.stringify({ ok: true });
    }
    return JSON.stringify({ ok: true });
  }

  pump() {
    while (this.active.size < 2 && this.queue.length) {
      const task = this.queue.shift();
      if (task.cancelled) continue;
      this.active.set(task.requestId, task);
      this.maxActive = Math.max(this.maxActive, this.active.size);
      this.sendBatch(task);
    }
  }

  sendBatch(task) {
    if (task.cancelled || this.active.get(task.requestId) !== task) return;
    const deliverBatch = () => {
      if (task.cancelled || this.active.get(task.requestId) !== task) return;
      const entries = task.entries.slice(task.offset, task.offset + 32);
      task.offset += entries.length;
      task.currentDone = task.offset >= task.entries.length;
      task.waiting = true;
      task.timer = setTimeout(() => {
        if (task.cancelled || this.active.get(task.requestId) !== task || !task.waiting) return;
        this.ackTimeouts++;
        task.cancelled = true;
        this.active.delete(task.requestId);
        this.pump();
      }, this.ackTimeoutMs);
      setTimeout(() => this.deliver(task.requestId, {
        ok: true, batchIndex: task.batchIndex, done: task.currentDone, entries
      }), 0);
    };
    if (task.offset === 0 && this.queryDelayMs > 0) {
      task.queryTimer = setTimeout(deliverBatch, this.queryDelayMs);
    } else deliverBatch();
  }

  finish(task) {
    clearTimeout(task.timer);
    this.active.delete(task.requestId);
  }
}

function makeHarness(tree, options = {}) {
  const pendingDirectoryLists = new Map();
  const diagnostics = [];
  const window = {};
  const native = new TwoWorkerNativeSaf(tree, options);
  const host = { listImagePackDirectoryForRequest: (...args) => native.call(...args) };
  native.deliver = (requestId, packet) => {
    const state = pendingDirectoryLists.get(String(requestId || ""));
    if (!state) return false;
    if (state.waiter) {
      const waiter = state.waiter;
      state.waiter = null;
      waiter(packet);
    } else state.packet = packet;
    return true;
  };
  window.__waywardNativeImagePackDirectoryBatch = native.deliver;
  const browserSetTimeout = (callback, delay = 0, ...args) =>
    setTimeout(callback, delay >= 30000 ? (options.jsWaitMs ?? 45) : delay, ...args);
  const context = vm.createContext({
    window, host, nativeCapability: "test-capability", pendingDirectoryLists,
    nextDirectoryListId: 0, Promise, Map, Set, String, Number, Array, Date, Error, Math,
    JSON, setTimeout: browserSetTimeout, clearTimeout, diagnostics,
    MAX_UPLOAD_DEPTH: 128, MAX_UPLOAD_ENTRIES: 200000, MAX_UPLOAD_FILES: 100000,
    ENUMERATION_YIELD_INTERVAL: 32,
    diagnose: (stage, requestId, details) => diagnostics.push({ stage, requestId, details }),
    noteUploadProgressActivity() {},
    showUploadProgress() {},
    safePathSegment(value) {
      const name = String(value || "");
      if (!name || name === "." || name === ".." || name.includes("/") || name.includes("\\") || name.includes("\0")) {
        const error = new Error("invalid path segment"); error.name = "NotReadableError"; throw error;
      }
      return name;
    },
    domError(name, message) { const error = new Error(message); error.name = name; return error; },
    parseResult(raw) {
      const value = typeof raw === "string" ? JSON.parse(raw) : raw;
      if (!value || value.ok !== true) {
        const error = new Error(value?.message || "native request failed");
        error.name = value?.name || "NotReadableError"; throw error;
      }
      return value;
    },
    checkUploadCancelled(control) {
      if (control?.cancelled) { const error = new Error("cancelled"); error.name = "AbortError"; throw error; }
    },
    async yieldUploadWork(control) {
      if (control?.cancelAfterYield && !control.cancelTriggered) {
        control.cancelTriggered = true;
        control.cancelled = true;
      }
      if (control?.cancelled) { const error = new Error("cancelled"); error.name = "AbortError"; throw error; }
      await Promise.resolve();
    },
    matchesFilePolicy() { return true; },
    makeSafBackedFile(child, relativePath) {
      return {
        name: child.name, size: child.size ?? 1, type: child.mimeType || "application/javascript",
        lastModified: child.lastModified || 0, webkitRelativePath: relativePath,
        async text() { return "/* WAYWARD_FAITH_IMAGE_PACK */\n// fixture"; }
      };
    }
  });
  vm.runInContext(iteratorSource + enumerateSource +
    "this.enumerateSafTree = enumerateSafTree; this.iterateDirectoryEntries = iterateDirectoryEntries;", context);
  vm.runInContext(`
    this.legacyRecursiveTraversal = async function(rootUri, rootName, requestId, control) {
      const files = [];
      async function visit(uri, prefix) {
        for await (const child of iterateDirectoryEntries(uri, requestId, control)) {
          const relativePath = prefix + "/" + child.name;
          if (child.kind === "directory") await visit(child.uri, relativePath);
          else files.push({ name: child.name, webkitRelativePath: relativePath });
        }
      }
      await visit(rootUri, rootName);
      return files;
    };
  `, context);
  return { context, native, diagnostics, enumerate: context.enumerateSafTree,
    legacy: context.legacyRecursiveTraversal };
}

function file(name, id = name) {
  return { name, kind: "file", uri: "content://tree/file/" + id, size: 1,
    mimeType: "application/javascript", lastModified: 0 };
}
function directory(name, id) {
  return { name, kind: "directory", uri: "content://tree/dir/" + id };
}
function chain(depth) {
  const tree = new Map();
  for (let index = 0; index < depth; index++) {
    const uri = "content://tree/dir/level-" + index;
    const children = index + 1 < depth
      ? [directory("level-" + (index + 1), "level-" + (index + 1))]
      : [file("deep.js", "deep")];
    tree.set(uri, children);
  }
  return { tree, root: "content://tree/dir/level-0" };
}

// Reproduce the old failure: two native workers wait for ACK while recursive JS
// traversal submits a third directory query. The simulated native ACK deadline is
// the production 30-second deadline, accelerated to milliseconds for the test.
{
  const { tree, root } = chain(3);
  const harness = makeHarness(tree);
  let legacyError;
  try {
    await harness.legacy(root, "root", "legacy-request", { cancelled: false, nativeDirectoryIds: new Set() });
  } catch (error) { legacyError = error; }
  assert.equal(legacyError?.name, "AbortError");
  assert.equal(harness.native.maxActive, 2, "legacy recursion must occupy both native workers");
  assert.equal(harness.native.startedUris.length, 3, "the third nested directory must be submitted behind the two blocked workers");
  assert.ok(harness.native.ackTimeouts >= 1, "the blocked ACK must reach the simulated 30-second timeout");
  assert.equal(harness.native.active.size, 0, "legacy failure cleanup must release active native tasks");
  assert.equal(harness.native.queue.length, 0, "legacy failure cleanup must not leave queued child tasks");
}

// Two nested levels do not exhaust both workers and remain supported.
{
  const { tree, root } = chain(2);
  const harness = makeHarness(tree);
  const files = await harness.enumerate(root, "root", "two-level", null,
    { cancelled: false, nativeDirectoryIds: new Set() });
  assert.deepEqual(Array.from(files, item => item.webkitRelativePath), ["root/level-1/deep.js"]);
  assert.equal(harness.native.ackTimeouts, 0);
}

// The exact three-level deadlock shape now completes with strictly sequential directory queries.
{
  const { tree, root } = chain(3);
  const harness = makeHarness(tree);
  const files = await harness.enumerate(root, "root", "three-level-fixed", null,
    { cancelled: false, nativeDirectoryIds: new Set() });
  assert.equal(files.length, 1);
  assert.equal(harness.native.ackTimeouts, 0);
  assert.equal(harness.native.maxActive, 1);
}

// Ten levels must finish without any worker waiting for a child task's ACK.
{
  const { tree, root } = chain(10);
  const harness = makeHarness(tree);
  const files = await harness.enumerate(root, "root", "ten-level", null,
    { cancelled: false, nativeDirectoryIds: new Set() });
  assert.equal(files.length, 1);
  assert.equal(files[0].webkitRelativePath, "root/level-1/level-2/level-3/level-4/level-5/level-6/level-7/level-8/level-9/deep.js");
  assert.equal(harness.native.ackTimeouts, 0);
  assert.equal(harness.native.active.size, 0);
}

// Empty, file-only and mixed trees preserve the old depth-first FileList order.
{
  const empty = new Map([["content://tree/dir/root", []]]);
  const emptyHarness = makeHarness(empty);
  assert.equal((await emptyHarness.enumerate("content://tree/dir/root", "root", "empty", null,
    { cancelled: false, nativeDirectoryIds: new Set() })).length, 0);

  const fileOnly = new Map([["content://tree/dir/root", [file("a.js", "a"), file("b.js", "b")]]]);
  const fileHarness = makeHarness(fileOnly);
  const fileOnlyResult = await fileHarness.enumerate("content://tree/dir/root", "root", "files", null,
    { cancelled: false, nativeDirectoryIds: new Set() });
  assert.deepEqual(Array.from(fileOnlyResult, item => item.name), ["a.js", "b.js"]);

  const mixed = new Map([
    ["content://tree/dir/root", [directory("a", "a"), file("root.js", "root"), directory("b", "b")]],
    ["content://tree/dir/a", [file("a-before.js", "a-before"), directory("nested", "nested"), file("a-after.js", "a-after")]],
    ["content://tree/dir/nested", [file("nested.js", "nested-file")]],
    ["content://tree/dir/b", [file("b.js", "b-file")]]
  ]);
  const mixedHarness = makeHarness(mixed);
  const ordered = await mixedHarness.enumerate("content://tree/dir/root", "root", "mixed", null,
    { cancelled: false, nativeDirectoryIds: new Set() });
  assert.deepEqual(Array.from(ordered, item => item.webkitRelativePath), [
    "root/a/a-before.js", "root/a/nested/nested.js", "root/a/a-after.js", "root/root.js", "root/b/b.js"
  ]);
}

// Provider permission errors and malformed child URIs fail closed without changing grants.
{
  const denied = makeHarness(new Map(), { failUri: "content://tree/dir/denied", failName: "SecurityError" });
  await assert.rejects(denied.enumerate("content://tree/dir/denied", "root", "denied", null,
    { cancelled: false, nativeDirectoryIds: new Set() }), error => error.name === "SecurityError");

  const invalidTree = new Map([["content://tree/dir/root", [{
    name: "bad", kind: "directory", uri: "file:///not-a-content-uri"
  }]]]);
  const invalid = makeHarness(invalidTree);
  await assert.rejects(invalid.enumerate("content://tree/dir/root", "root", "invalid-uri", null,
    { cancelled: false, nativeDirectoryIds: new Set() }), error => error.name === "SecurityError");
}

// Cancellation while a native batch is awaiting ACK clears the task; the same
// native bridge can immediately service a fresh request afterwards.
{
  const entries = Array.from({ length: 64 }, (_, index) => file("cancel-" + index + ".js", "cancel-" + index));
  const tree = new Map([["content://tree/dir/root", entries]]);
  const harness = makeHarness(tree);
  const cancelledControl = { cancelled: false, cancelAfterYield: true, nativeDirectoryIds: new Set() };
  await assert.rejects(harness.enumerate("content://tree/dir/root", "root", "cancel-me", null,
    cancelledControl), error => error.name === "AbortError");
  assert.equal(harness.native.active.size, 0);
  assert.ok(harness.native.cancelled > 0);
  const retry = await harness.enumerate("content://tree/dir/root", "root", "retry-after-cancel", null,
    { cancelled: false, nativeDirectoryIds: new Set() });
  assert.equal(retry.length, 64);
  assert.equal(harness.native.ackTimeouts, 0);
}

// 10,000 files are transferred in bounded batches with no loss or duplication.
{
  const entries = Array.from({ length: 10000 }, (_, index) =>
    file("pack-" + String(index).padStart(5, "0") + ".js", "large-" + index));
  const tree = new Map([["content://tree/dir/root", entries]]);
  const harness = makeHarness(tree);
  const files = await harness.enumerate("content://tree/dir/root", "root", "large", null,
    { cancelled: false, nativeDirectoryIds: new Set() });
  assert.equal(files.length, 10000);
  assert.equal(new Set(Array.from(files, item => item.webkitRelativePath)).size, 10000);
  assert.equal(files[0].name, "pack-00000.js");
  assert.equal(files.at(-1).name, "pack-09999.js");
  assert.equal(harness.native.ackTimeouts, 0);
}

// A slow DocumentsProvider delays the first query response without creating a
// nested ACK wait; the directory is still consumed once the provider responds.
{
  const { tree, root } = chain(3);
  const harness = makeHarness(tree, { queryDelayMs: 60, jsWaitMs: 150 });
  const files = await harness.enumerate(root, "root", "slow-provider", null,
    { cancelled: false, nativeDirectoryIds: new Set() });
  assert.equal(files.length, 1);
  assert.equal(harness.native.ackTimeouts, 0);
}

// The pinned FAITH 3.0.0 scanner function is invoked with the produced FileList
// entries; this confirms the change-event consumer's actual scanner contract.
{
  const tree = new Map([[
    "content://tree/dir/root", [directory("image-packs", "image-packs")]
  ], [
    "content://tree/dir/image-packs", [file("one.js", "one"), file("two.js", "two")]
  ]]);
  const harness = makeHarness(tree);
  const files = await harness.enumerate("content://tree/dir/root", "root", "faith-scan", null,
    { cancelled: false, nativeDirectoryIds: new Set() });
  const scannerState = { value: null };
  const scannerContext = vm.createContext({
    Object, Array, String, Set, Date, JSON,
    wfPackParseScript: () => ({}),
    wfPackValidate: (_parsed, cataloguePath) => ({
      packId: cataloguePath, wfImagePack: { character: cataloguePath, basic: true, part: "", outfits: new Set() },
      entryCount: 1, generatedAt: "", cataloguePath, entries: []
    }),
    wfPackInstalled: () => [], wfPackCatalogues: {}, wfPackFreezeSelections() {},
    wfPackLinkExtensions() {}, wfPackDropOrphans() {}, ND() {}, wfPackRefreshClothes() {},
    Rt: { getState: () => ({ set(value) { scannerState.value = value; } }) }
  });
  vm.runInContext(fixture.sourceFunctions.wfPackScanFolder.code + "; this.scan = wfPackScanFolder;", scannerContext);
  const registered = await scannerContext.scan(files);
  assert.equal(registered, 2);
  assert.equal(scannerState.value.wfImagePacks.length, 2);
}

// The UI exposes real counters, elapsed time, provider delay and cancellation.
assert.match(source, /경과 /);
assert.match(source, /응답 지연 중/);
assert.match(source, /cancelWaywardDirectoryUpload\(active\.requestId\)/);
assert.match(source, /fileCount: scannedFileCount, directoryCount: discoveredDirectoryCount, entryCount/);
assert.match(source, /queued_directory_walk_preserved_depth_first_order/);

console.log("SAF queued traversal deadlock regression: PASS");
