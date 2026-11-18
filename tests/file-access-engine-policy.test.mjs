import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";

const bridge = readFileSync(new URL("../app/src/main/assets/imagepack-folder-picker.js", import.meta.url), "utf8");
const start = bridge.indexOf("  // Hard safety ceilings are deliberately independent");
const end = bridge.indexOf("  function safePathSegment", start);
assert.ok(start >= 0 && end > start, "file policy implementation remains in the bridge");
const source = bridge.slice(start, end) + `
 globalThis.policyTest = {
   read: readRequestFilePolicy,
   matches: matchesFilePolicy,
   cancel: window.cancelWaywardDirectoryUpload,
   limits: { maxFiles: MAX_UPLOAD_FILES, maxEntries: MAX_UPLOAD_ENTRIES, yieldInterval: ENUMERATION_YIELD_INTERVAL },
   setActive(value) { if (value) activeUploadControls.add(value); else activeUploadControls.clear(); },
   removeActive(value) { activeUploadControls.delete(value); }
 };
`;
function makeContext() {
  const context = {
    window: {},
    domError(name, message) { const error = new Error(message); error.name = name; return error; },
    setTimeout,
    Promise,
    Object,
    Number,
    String,
    Array,
    Set,
    JSON,
    Date
  };
  vm.runInNewContext(source, context);
  return context.policyTest;
}

const api = makeContext();
assert.equal(api.read({ getAttribute: () => null }), null, "no policy preserves full-file behavior");
const imagesOnly = api.read({ getAttribute: () => JSON.stringify({ includeExtensions: ["PNG", ".webp"], maxFiles: 1200 }) });
assert.equal(imagesOnly.maxFiles, 1200);
assert.equal(api.matches("root/a.png", "a.png", imagesOnly), true);
assert.equal(api.matches("root/a.txt", "a.txt", imagesOnly), false);
const pathPolicy = api.read({ getAttribute: () => JSON.stringify({ includePathPrefixes: ["root/keep"], excludePathPrefixes: ["root/keep/private"] }) });
assert.equal(api.matches("root/keep/a.bin", "a.bin", pathPolicy), true);
assert.equal(api.matches("root/keep/private/a.bin", "a.bin", pathPolicy), false);
assert.equal(api.matches("root/keep/A.bin", "A.bin", pathPolicy), true);
assert.equal(api.matches("Root/keep/a.bin", "a.bin", pathPolicy), false, "path-prefix matching preserves case-sensitive relative paths");
assert.equal(api.matches("root/drop/a.bin", "a.bin", pathPolicy), false);
const requestA = api.read({ getAttribute: () => JSON.stringify({ excludeExtensions: ["tmp"] }) });
const requestB = api.read({ getAttribute: () => null });
assert.equal(api.matches("root/a.tmp", "a.tmp", requestA), false);
assert.equal(api.matches("root/a.tmp", "a.tmp", requestB), true, "request policy does not leak into the next request");
assert.equal(api.limits.maxFiles, 100000);
assert.equal(api.limits.maxEntries, 200000);
assert.equal(api.limits.yieldInterval, 256);
assert.match(bridge, /scannedFileCount\+\+;\s*if \(scannedFileCount > MAX_UPLOAD_FILES\)/,
  "the hard 100k file ceiling applies before request-specific filtering");
let eligibleCount = 0;
const syntheticStart = performance.now();
for (let index = 0; index < 51985; index++) {
  const name = "asset-" + index + ".dat";
  if (api.matches("root/" + name, name, null)) eligibleCount++;
}
const syntheticElapsedMs = performance.now() - syntheticStart;
assert.equal(eligibleCount, 51985, "unfiltered requests retain every file in the observed 51,985-item scale");
console.log("Synthetic 51,985-file policy pass: " + syntheticElapsedMs.toFixed(2) + " ms (predicate only; excludes SAF and File creation).");
assert.throws(() => api.read({ getAttribute: () => "{" }), { name: "TypeError" });
assert.throws(() => api.read({ getAttribute: () => JSON.stringify({ unknown: true }) }), { name: "TypeError" });
assert.throws(() => api.read({ getAttribute: () => JSON.stringify({ maxFiles: 100001 }) }), { name: "TypeError" });
assert.throws(() => api.read({ getAttribute: () => JSON.stringify({ includePathPrefixes: ["../escape"] }) }), { name: "TypeError" });
const controlA = { requestId: "request-a", cancelled: false };
const controlB = { requestId: "request-b", cancelled: false };
api.setActive(controlA);
api.setActive(controlB);
assert.equal(api.cancel(), false, "an unscoped cancellation is rejected when requests are concurrent");
assert.equal(api.cancel("request-b"), true);
assert.equal(controlA.cancelled, false, "cancelling one request cannot cancel its sibling");
assert.equal(controlB.cancelled, true);
assert.equal(api.cancel("request-a"), true);
assert.equal(controlA.cancelled, true);
api.removeActive(controlA);
api.removeActive(controlB);
assert.equal(api.cancel("request-a"), false, "completed request state is not retained");

assert.match(bridge, /MAX_UPLOAD_FILES = 100000/);
assert.match(bridge, /MAX_UPLOAD_ENTRIES = 200000/);
assert.match(bridge, /ENUMERATION_YIELD_INTERVAL = 256/);
assert.match(bridge, /await yieldUploadWork\(control\)/);
assert.match(bridge, /const activeUploadControls = new Set\(\)/);
assert.match(bridge, /nativePickerBusy = false/);
assert.doesNotMatch(bridge, /let uploadPickerBusy = false/);
assert.match(bridge, /directory_enumeration_completed/);
console.log("Generic file-access policy isolation and large-tree safety tests passed.");
