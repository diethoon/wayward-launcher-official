import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";
import { createHash } from "node:crypto";

const fixture = JSON.parse(readFileSync(
  new URL("./fixtures/wayward-file-picker-source-contract.json", import.meta.url), "utf8"));
const asset = readFileSync(
  new URL("../app/src/main/assets/imagepack-folder-picker.js", import.meta.url), "utf8");
const main = readFileSync(
  new URL("../app/src/main/java/com/wayward/launcher/MainActivity.java", import.meta.url), "utf8");
const access = readFileSync(
  new URL("../app/src/main/java/com/wayward/launcher/ImagePackDirectoryAccess.java", import.meta.url), "utf8");
const scriptInstaller = readFileSync(
  new URL("../app/src/main/java/com/wayward/launcher/ImagePackFolderPickerScript.java", import.meta.url), "utf8");

assert.equal(fixture.sourceRepository, "diethoon/wayward");
assert.equal(fixture.sourceBranch, "main");
assert.equal(fixture.sourcePath, "Wayward_Faith_v3.0.0.html");
assert.equal(fixture.sourceBlobSha, "ac6b44ccb75584383ad2875f4f36a55138c228cf");
assert.equal(fixture.imagePackFolderInput.webkitdirectory, true);
assert.equal(fixture.imagePackFolderInput.multiple, true);
assert.equal(fixture.imagePackFolderInput.buttonFunction, "wfPackFolderButton");
assert.equal(fixture.imagePackFolderInput.scanFunction, "wfPackScanFolder");
assert.deepEqual(fixture.scannerContract.requiredCollectionMembers, ["length", "item(index)"]);
assert.deepEqual(fixture.scannerContract.requiredFileMembers, ["name", "text()", "webkitRelativePath"]);
assert.equal(fixture.scannerContract.imagePackMarker, "/* WAYWARD_FAITH_IMAGE_PACK */");
for (const [name, source] of Object.entries(fixture.sourceFunctions)) {
  const bytes = Buffer.from(source.code, "utf8");
  const blobSha = createHash("sha1").update(Buffer.concat([Buffer.from("blob " + bytes.length + "\0"), bytes])).digest("hex");
  assert.equal(blobSha, source.gitBlobSha, name + " fixture must be byte-identical to its extracted source snippet");
  assert.ok(source.startByte < source.endByteExclusive);
}
assert.equal(fixture.sourceFunctions.wfPackFolderButton.code.includes("await wfPackScanFolder(i.target.files)"), true);
assert.equal(fixture.sourceFunctions.wfPackScanFolder.code.startsWith("async function wfPackScanFolder("), true);

assert.match(asset, /function isDirectoryUploadInput\s*\(/,
  "real webkitdirectory input elements must be identified independently of FSA calls");
assert.match(asset, /document\.addEventListener\("click",\s*onClick,\s*true\)/,
  "directory input requests must be intercepted in the capture phase before WebView's file chooser");
assert.match(asset, /input\.webkitdirectory\s*===\s*true/,
  "the directory attribute, rather than showDirectoryPicker, is the request discriminator");
assert.match(asset, /nativeRequest\("read",\s*""\)/,
  "FAITH folder upload must select a read-only SAF tree through the common native backend");
assert.match(asset, /async function enumerateSafTree/,
  "the SAF tree must be recursively enumerated");
assert.match(asset, /relativePath = prefix \+ "\/" \+ name/,
  "the selected root name and nested hierarchy must be retained in relative paths");
assert.match(asset, /class SafBackedFile extends Blob/,
  "lazy files must keep Blob identity and File prototype compatibility");
assert.match(asset, /async text\(\)/,
  "File.text() must read SAF content lazily");
assert.match(asset, /webkitRelativePath:\s*\{\s*value:\s*metadata\.webkitRelativePath/,
  "each file must expose its relative path");
assert.match(asset, /function makeFileList\(files\)/,
  "the web adapter must construct a FileList-compatible collection");
assert.match(asset, /value\(index\)[\s\S]*?files\[position\]\s*:\s*null/,
  "FileList.item(index) must return a file or null");
assert.match(asset, /dispatchInputEvent\(input,\s*"input",\s*true\)[\s\S]*?dispatchInputEvent\(input,\s*"change",\s*true\)/,
  "the original input/change event sequence must be preserved");
assert.match(asset, /\[Symbol\.asyncIterator\]\(\)\s*\{\s*return this\.entries\(\)/,
  "DirectoryHandle objects must be async iterable");
assert.match(main, /ImagePackFolderPickerScript\.installDocumentStart\(\s*web,\s*imagePackFolderPickerScript,\s*origin\)/,
  "the request detector must install before page scripts on every matching origin frame");
assert.match(scriptInstaller, /WebViewCompat\.addDocumentStartJavaScript/,
  "the documented frame-aware document-start mechanism must be used");
assert.match(access, /Intent\.ACTION_OPEN_DOCUMENT_TREE/,
  "Android 15 folder selection must use the public SAF tree picker");
assert.match(access, /takePersistableUriPermission/,
  "the native layer must persist the selected tree grant");
assert.match(access, /requireDocumentUri\(/,
  "every document operation must validate that its URI is inside the selected tree");
assert.match(access, /MAX_FILE_BYTES\s*=\s*32\s*\*\s*1024\s*\*\s*1024/,
  "the existing bounded file read limit must remain explicit");
assert.doesNotMatch(asset, /MODE_OPEN_FOLDER/,
  "the API-35 path must not depend on the API-37 public FileChooserParams folder mode");

const FileCtor = globalThis.File || class TestFile extends Blob {
  constructor(parts, name, options = {}) {
    super(parts, options);
    Object.defineProperties(this, {
      name: { value: String(name) },
      lastModified: { value: Number(options.lastModified || 0) }
    });
  }
};
class FakeEvent {
  constructor(type, options = {}) {
    this.type = type;
    this.bubbles = options.bubbles === true;
    this.composed = options.composed === true;
    this.detail = options.detail;
    this.target = null;
  }
}
class FakeFileList {}
class FakeInputElement {}
Object.defineProperty(FakeInputElement.prototype, "showPicker", {
  configurable: true, writable: true, value: function() {}
});
Object.defineProperty(FakeInputElement.prototype, "files", {
  configurable: true, get() { return []; }
});

const treeUri = "content://com.android.externalstorage.documents/tree/primary%3AWayward";
const rootUri = "content://com.android.externalstorage.documents/tree/primary%3AWayward/document/primary%3AWayward";
const packsUri = rootUri + "%2Fimage-packs";
const nestedUri = rootUri + "%2Fimage-packs%2Fcharacters";
const scriptUri = rootUri + "%2Fimage-packs%2Fcharacters%2Ffaith-pack.js";
const packSource = "/* WAYWARD_FAITH_IMAGE_PACK */\n{\"packId\":\"faith-fixture-pack\",\"wfImagePack\":{\"character\":\"faith-fixture\",\"basic\":true,\"part\":\"\"},\"entryCount\":1,\"generatedAt\":\"2026-11-19\"}";
const packBytes = Buffer.from(packSource, "utf8");
const base64Pack = packBytes.toString("base64");

const directoryEntries = new Map([
  [rootUri, [
    { name: "image-packs", kind: "directory", uri: packsUri, size: -1, mimeType: "vnd.android.document/directory", lastModified: 10 },
    { name: "notes.txt", kind: "file", uri: rootUri + "%2Fnotes.txt", size: 5, mimeType: "text/plain", lastModified: 11 }
  ]],
  [packsUri, [
    { name: "characters", kind: "directory", uri: nestedUri, size: -1, mimeType: "vnd.android.document/directory", lastModified: 12 }
  ]],
  [nestedUri, [
    { name: "faith-pack.js", kind: "file", uri: scriptUri, size: packBytes.length, mimeType: "application/javascript", lastModified: 13 }
  ]]
]);
const diagnostics = [];
let chooseCount = 0;
let capturedId = "";
let lastChangeTarget = null;
let windowObject;
const host = {
  recordImagePackDiagnostic(requestId, stage, details) {
    diagnostics.push({ requestId, stage, details });
  },
  chooseImagePackFolder(requestId, capability, mode, initialUri) {
    assert.equal(capability, "test-native-capability");
    assert.ok(mode === "read" || mode === "readwrite");
    assert.equal(initialUri, "");
    chooseCount++;
    capturedId = requestId;
    setTimeout(() => windowObject.__waywardNativeImagePackPickerDispatch(requestId, {
      ok: true,
      requestId,
      uri: treeUri,
      documentUri: rootUri,
      name: "Faith Library",
      writable: mode === "readwrite",
      state: "SUCCESS"
    }), 0);
  },
  listImagePackDirectoryForRequest(capability, uri) {
    assert.equal(capability, "test-native-capability");
    return JSON.stringify({ ok: true, uri, entries: directoryEntries.get(uri) || [] });
  },
  writeImagePackFile(capability, uri, mimeType, encoded) {
    assert.equal(capability, "test-native-capability");
    host.lastWrite = { uri, mimeType, encoded };
    return JSON.stringify({ ok: true, uri, size: Buffer.from(encoded, "base64").length });
  },
  readImagePackFileForRequest(capability, uri) {
    assert.equal(capability, "test-native-capability");
    assert.equal(uri, scriptUri);
    return JSON.stringify({
      ok: true, uri, name: "faith-pack.js", mimeType: "application/javascript",
      lastModified: 13, size: packBytes.length, data: base64Pack
    });
  },
  getImagePackFolderInfo() {
    return JSON.stringify({ ok: true, uri: treeUri, documentUri: rootUri, name: "Faith Library", writable: false });
  },
  resolveImagePackEntry(capability, parentUri, targetUri) {
    assert.equal(capability, "test-native-capability");
    return JSON.stringify({
      ok: true,
      path: targetUri === nestedUri ? ["image-packs", "characters"] : null
    });
  }
};

const documentListeners = new Map();
const documentObject = {
  readyState: "loading",
  addEventListener(type, listener, capture) {
    if (type === "click") {
      assert.equal(capture, true);
      documentListeners.set(type, listener);
    }
  }
};
const windowListeners = [];
windowObject = {
  location: { origin: "https://wayward.invalid", pathname: "/game/" },
  frames: [],
  document: documentObject,
  WMLHost: host,
  HTMLInputElement: FakeInputElement,
  Event: FakeEvent,
  CustomEvent: FakeEvent,
  dispatchEvent(event) { windowListeners.push(event); return true; }
};
windowObject.window = windowObject;
windowObject.top = windowObject;
windowObject.parent = windowObject;
documentObject.defaultView = windowObject;

const context = {
  window: windowObject,
  document: documentObject,
  location: windowObject.location,
  DOMException,
  File: FileCtor,
  Blob,
  FileList: FakeFileList,
  Event: FakeEvent,
  CustomEvent: FakeEvent,
  TextDecoder,
  TextEncoder,
  ReadableStream,
  ArrayBuffer,
  Uint8Array,
  Map,
  WeakMap,
  Promise,
  Date,
  Math,
  Number,
  String,
  Object,
  JSON,
  RegExp,
  Error,
  TypeError,
  setTimeout,
  clearTimeout,
  atob,
  btoa
};
vm.runInNewContext(asset.replaceAll("__WAYWARD_FOLDER_PICKER_CAPABILITY__", "test-native-capability"), context);

assert.equal(windowObject.__waywardWebkitDirectoryAdapterInstalled, true);
assert.equal(typeof windowObject.showDirectoryPicker, "function");
assert.equal(typeof documentListeners.get("click"), "function");

const inputEvents = [];
let receivedFiles = null;
let changeResolve;
const changeReceived = new Promise(resolve => { changeResolve = resolve; });
const input = Object.create(FakeInputElement.prototype);
Object.assign(input, {
  nodeType: 1,
  tagName: "INPUT",
  type: "file",
  webkitdirectory: true,
  multiple: true,
  disabled: false,
  ownerDocument: documentObject,
  dispatchEvent(event) {
    event.target = this;
    inputEvents.push(event.type);
    if (event.type === "change") {
      receivedFiles = this.files;
      lastChangeTarget = event.target;
      changeResolve();
    }
    return true;
  }
});
const clickEvent = {
  target: input,
  composedPath: () => [input],
  prevented: false,
  stopped: false,
  preventDefault() { this.prevented = true; },
  stopImmediatePropagation() { this.stopped = true; }
};
documentListeners.get("click")(clickEvent);
assert.equal(clickEvent.prevented, true, "webkitdirectory must suppress the unrelated native file-only picker");
assert.equal(clickEvent.stopped, true);
await Promise.race([
  changeReceived,
  new Promise((_, reject) => setTimeout(() => reject(new Error("webkitdirectory change event timed out")), 1500))
]);

assert.deepEqual(inputEvents, ["input", "change"]);
assert.equal(receivedFiles instanceof FakeFileList, true);
assert.equal(input.files, receivedFiles, "the FileList remains attached after synchronous event dispatch");
assert.equal(receivedFiles.length, 2);
assert.equal(receivedFiles.item(0).name, "faith-pack.js");
assert.equal(receivedFiles.item(1).name, "notes.txt");
assert.equal(receivedFiles.item(-1), null);
assert.equal(receivedFiles.item(99), null);
const uploaded = receivedFiles.item(0);
assert.equal(uploaded instanceof FileCtor, true);
assert.equal(uploaded instanceof Blob, true);
assert.equal(uploaded.webkitRelativePath, "Faith Library/image-packs/characters/faith-pack.js");
assert.equal(uploaded.size, packBytes.length);
assert.equal(uploaded.type, "application/javascript");
assert.equal(uploaded.lastModified, 13);
assert.equal(await uploaded.text(), packSource);
assert.equal(new TextDecoder().decode(await uploaded.arrayBuffer()), packSource);
assert.equal(await uploaded.slice(0, 4).text(), "/* W");
assert.equal((await uploaded.stream().getReader().read()).value.length > 0, true);
assert.equal(chooseCount, 1);
assert.equal(capturedId.length > 0, true);
assert.ok(diagnostics.some(event => event.stage === "webkitdirectory_upload_dispatched"));

const regularInput = Object.create(FakeInputElement.prototype);
Object.assign(regularInput, {
  nodeType: 1, tagName: "INPUT", type: "file", webkitdirectory: false,
  multiple: false, disabled: false, ownerDocument: documentObject
});
const regularClick = {
  target: regularInput, composedPath: () => [regularInput],
  prevented: false, stopImmediatePropagation() { this.stopped = true; },
  preventDefault() { this.prevented = true; }
};
documentListeners.get("click")(regularClick);
assert.equal(regularClick.prevented, false);
assert.equal(chooseCount, 1, "ordinary file input requests must remain on WebView's normal chooser path");

const directoryHandle = await windowObject.showDirectoryPicker({ mode: "read" });
assert.equal(directoryHandle.kind, "directory");
assert.equal(directoryHandle.name, "Faith Library");
const childNames = [];
for await (const [name, handle] of directoryHandle) childNames.push([name, handle.kind]);
assert.deepEqual(childNames, [["image-packs", "directory"], ["notes.txt", "file"]]);
const imagePacks = await directoryHandle.getDirectoryHandle("image-packs");
const characters = await imagePacks.getDirectoryHandle("characters");
const packHandle = await characters.getFileHandle("faith-pack.js");
assert.equal(packHandle.kind, "file");
assert.equal(await (await packHandle.getFile()).text(), packSource);
assert.deepEqual(await directoryHandle.resolve(characters), ["image-packs", "characters"]);
assert.equal(await directoryHandle.queryPermission({ mode: "read" }), "granted");

// Execute the exact original FAITH scanner function from the pinned HTML fixture.
function makeFaithScanner() {
  const registrations = [];
  const scannerContext = {
    wfPackCatalogues: {},
    wfPackParseScript(source) { return JSON.parse(source.slice("/* WAYWARD_FAITH_IMAGE_PACK */".length).trim()); },
    wfPackValidate(parsed, cataloguePath) { return { ...parsed, cataloguePath, entries: [] }; },
    wfPackInstalled() { return []; }, wfPackFreezeSelections() {}, wfPackLinkExtensions() {},
    wfPackDropOrphans() {}, wfPackRefreshClothes() {}, ND() {},
    Rt: { getState: () => ({ set: state => registrations.push(...state.wfImagePacks) }) },
    Object, Array, Set, String, Date, JSON, Error
  };
  return { scan: vm.runInNewContext("(" + fixture.sourceFunctions.wfPackScanFolder.code + ")", scannerContext), registrations };
}
const faith = makeFaithScanner();
assert.equal(await faith.scan(receivedFiles), 1, "original FAITH scanner must register the adapter-provided FileList");
assert.equal(faith.registrations[0].packId, "faith-fixture-pack");
assert.equal(faith.registrations[0].cataloguePath, "image-packs/characters/faith-pack.js");
assert.equal(input.files, receivedFiles, "input.files remains accessible after awaited File.text()");
function scannerFile(name, path, source, read) { return { name, webkitRelativePath: path, text: read || (async () => source) }; }
function sourcePack(id) { return "/* WAYWARD_FAITH_IMAGE_PACK */\\n" + JSON.stringify({packId:id,wfImagePack:{character:id,basic:true,part:""},entryCount:1,generatedAt:"2026-11-19"}); }
const multi = makeFaithScanner();
assert.equal(await multi.scan([
 scannerFile("한글.js","Root/image-packs/deep/한글 폴더/one.js",sourcePack("one")),
 scannerFile("two.js","Root/image-packs/a/b/c/two.js",sourcePack("two")),
 scannerFile("same.js","Root/image-packs/other/same.js",sourcePack("three")),
 scannerFile("same.js","Root/image-packs/duplicate/same.js",sourcePack("four")),
 scannerFile("outside.js","Root/mods/outside.js",sourcePack("ignored")),
 scannerFile("special-#?.js","Root/image-packs/special-#?/special-#?.js",sourcePack("special"))
]),5,"nested, Unicode, special names and duplicate basenames are handled; non-image-packs paths are ignored");
await assert.rejects(()=>makeFaithScanner().scan([scannerFile("bad.js","Root/mods/bad.js",sourcePack("bad"))]),/image-packs 폴더에서 이미지팩을 찾지 못했습니다/);
await assert.rejects(()=>makeFaithScanner().scan([scannerFile("bad.js","Root/image-packs/bad.js","/* invalid */")]),/image-packs 폴더에서 이미지팩을 찾지 못했습니다/);
await assert.rejects(()=>makeFaithScanner().scan([scannerFile("empty.js","Root/image-packs/empty.js","")]),/image-packs 폴더에서 이미지팩을 찾지 못했습니다/);
await assert.rejects(()=>makeFaithScanner().scan([scannerFile("denied.js","Root/image-packs/denied.js","",async()=>{throw new Error("SAF read denied");})]),/SAF read denied/);
await assert.rejects(()=>makeFaithScanner().scan([scannerFile("one.js","A/image-packs/one.js",sourcePack("dup")),scannerFile("two.js","B/image-packs/two.js",sourcePack("dup"))]),/같은 ID의 이미지팩이 두 폴더에 있습니다/);
let releaseRead;
const delayedSource=sourcePack("delayed");
const delayedFile=scannerFile("delayed.js","Root/image-packs/delayed.js",delayedSource,()=>new Promise(resolve=>{releaseRead=()=>resolve(delayedSource);}));
const overlap=makeFaithScanner();
const pendingFirst=overlap.scan([delayedFile]);
assert.equal(await overlap.scan([scannerFile("second.js","Root/image-packs/second.js",sourcePack("second"))]),1);
releaseRead();
assert.equal(await pendingFirst,1,"the first scan retains its File object snapshot after a subsequent selection starts.");

console.log("Pinned FAITH scanner integration, async FileList lifetime and separate DirectoryHandle contracts passed.");
