import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const source = JSON.parse(readFileSync(new URL("./fixtures/wayward-file-picker-source-contract.json", import.meta.url), "utf8"));
const picker = readFileSync(new URL("../app/src/main/java/com/wayward/launcher/WebFileChooser.java", import.meta.url), "utf8");
const policy = readFileSync(new URL("../app/src/main/java/com/wayward/launcher/WebFileChooserRequestPolicy.java", import.meta.url), "utf8");
const main = readFileSync(new URL("../app/src/main/java/com/wayward/launcher/MainActivity.java", import.meta.url), "utf8");
const folderBridge = readFileSync(new URL("../app/src/main/assets/imagepack-folder-picker.js", import.meta.url), "utf8");
const folderNative = readFileSync(new URL("../app/src/main/java/com/wayward/launcher/ImagePackDirectoryAccess.java", import.meta.url), "utf8");
const downloadBridge = readFileSync(new URL("../app/src/main/assets/wayward-download-bridge.js", import.meta.url), "utf8");
const downloadNative = readFileSync(new URL("../app/src/main/java/com/wayward/launcher/WebDownloadHandler.java", import.meta.url), "utf8");

assert.equal(source.sourceRepository, "diethoon/wayward");
assert.equal(source.sourcePath, "Wayward_WML_ko.html");
assert.equal(source.sourceBlobSha, "d198eacfc127c55dc44bd5d1b8e29ff2bb7d08e6");
assert.equal(source.sourceSha256, "e159fb2002d31559b1ac1936cf6b48cdbbdc4858bfbb0b67e2d7284ca2195d1d");
assert.equal(source.byteSize, 4373978);
assert.equal(source.characterCount, 3506143);
assert.equal(source.lineCount, 492);
assert.equal(source.fileInputs.length, 2);

const single = source.fileInputs.find(item => item.id === "save-data-import");
assert.deepEqual({ type: single.type, accept: single.accept, multiple: single.multiple, capture: single.capture },
  { type: "file", accept: "application/json,.json", multiple: false, capture: false });
assert.match(single.onChange, /G\[0\]/);
assert.match(single.onChange, /\.text\(\)/);

const multiple = source.fileInputs.find(item => item.id === "image-pack-json-import");
assert.deepEqual({ type: multiple.type, accept: multiple.accept, multiple: multiple.multiple, capture: multiple.capture },
  { type: "file", accept: ".json,application/json", multiple: true, capture: false });
assert.match(multiple.onChange, /g\(k\.target\.files\)/);
const packConsumer = source.importFunctions.find(item => item.name === "pack-import-handler").source;
assert.match(packConsumer, /Array\.from\(k\)/);
assert.match(packConsumer, /await Oit\(S,c\)/);
assert.match(source.importFunctions.find(item => item.name === "Oit").source, /await e\.text\(\)/);

assert.deepEqual(source.downloads.map(item => item.mime), ["application/json", "application/jsonl"]);
assert.match(source.downloads[0].source, /new Blob\(\[t\],\{type:"application\/json"\}\)/);
assert.match(source.downloads[0].source, /s\.download=e/);
assert.match(source.downloads[1].source, /type:"application\/jsonl"/);
assert.match(source.downloads[1].source, /i\.download=a/);
for (const api of source.absentApis) {
  assert.ok(!source.fileInputs.some(input => input.onChange.includes(api)), `source fixture unexpectedly contains ${api}`);
}

assert.match(policy, /MODE_OPEN = 0/);
assert.match(policy, /MODE_OPEN_MULTIPLE = 1/);
assert.match(policy, /MODE_SAVE = 3/);
assert.match(policy, /parseAcceptTypes/);
assert.match(policy, /acceptsFile/);
assert.match(picker, /Intent\.ACTION_OPEN_DOCUMENT/);
assert.match(picker, /Intent\.EXTRA_ALLOW_MULTIPLE/);
assert.match(picker, /FileChooserParams\.parseResult/);
assert.match(picker, /validateAcceptedFile/);
assert.match(picker, /acceptedMimeTypes/);
assert.match(picker, /getFilenameHint\(\)/);

assert.match(main, /beginBlobDownload\(String requestId,String filename,String mimeType,long expectedBytes\)/);
assert.match(main, /appendBlobDownloadChunk\(String requestId,String base64Bytes\)/);
assert.match(main, /finishBlobDownload\(String requestId\)/);
assert.match(main, /abortBlobDownload\(String requestId\)/);
assert.match(main, /installWaywardDownloadBridge\(origin\)/);
assert.match(main, /WebDownloadHandler\.isRequestCode\(req\)/);
assert.match(downloadBridge, /a\[download\]/);
assert.match(downloadBridge, /href\.startsWith\("blob:"\)/);
assert.match(downloadBridge, /URL\.createObjectURL = function\(blob\)/, "the exact Blob object is retained before the game's immediate URL revocation");
assert.match(downloadBridge, /window\.__waywardBlobDownloadResult/, "native save completion is returned to the page");
assert.match(downloadBridge, /deferNextRemovalId/, "play-log deletion is deferred until native save completion");
assert.match(downloadBridge, /originalRemoveItem\.call\(deferred\.storage, deferred\.key\)/, "play logs are removed only after the selected document has been written successfully");
assert.match(downloadBridge, /saved === true/, "cancelled or failed exports preserve the game's play-log storage");
assert.match(downloadBridge, /blob\.type \|\| fallbackMime\(filename\)/);
assert.match(downloadBridge, /host\.beginBlobDownload\(requestId, filename, mimeType, blob\.size\)/);
assert.match(downloadBridge, /host\.appendBlobDownloadChunk\(requestId, base64\(bytes\)\)/);
assert.match(downloadBridge, /host\.finishBlobDownload\(requestId\)/);
assert.match(downloadBridge, /blob\.slice\(offset, offset \+ chunkSize\)/, "Blob bytes are transferred in bounded chunks");
assert.match(downloadNative, /Intent\.ACTION_CREATE_DOCUMENT/);
assert.match(downloadNative, /Intent\.EXTRA_TITLE, request\.filename/);
assert.match(downloadNative, /picker\.setType\(request\.mimeType\)/);
assert.match(downloadNative, /checkUriPermission\(uri/);
assert.match(downloadNative, /openOutputStream\(uri, "w"\)/);
assert.match(downloadNative, /FileInputStream\(request\.temporaryFile\)/);
assert.match(downloadNative, /resultCode != Activity\.RESULT_OK/);
assert.match(downloadNative, /boolean beginDownload\(/);
assert.match(downloadNative, /boolean appendChunk\(/);
assert.match(downloadNative, /boolean finishDownload\(/);
assert.match(downloadNative, /copied != request\.expectedBytes/, "short or incomplete exports cannot be reported as saved");
assert.match(downloadNative, /complete\(request, false\)/, "cancelled and failed saves return a negative completion");
assert.match(downloadNative, /complete\(request, true\)/, "only a completed content write returns success");
assert.match(downloadNative, /window\.__waywardBlobDownloadResult/, "native completion resolves the originating game page request");

assert.match(folderBridge, /__waywardSafDocumentUri/);
assert.doesNotMatch(folderBridge, /__path|childPath|parentPath/);
assert.match(folderBridge, /createImagePackFile\(nativeCapability,/);
assert.match(folderBridge, /resolveImagePackEntry\(nativeCapability,/);
assert.match(folderBridge, /queryHandlePermission/);
assert.match(main, /hasImagePackBridgeCapability\(String capability\)/);
assert.match(main, /matchesCapability\(\s*imagePackFolderPickerCapability, capability\)/);
assert.match(main, /listImagePackDirectoryForRequest\(String capability, String documentUri, String requestId\)/);
assert.match(main, /readImagePackFileForRequest\(String capability, String documentUri, String requestId\)/);
assert.match(main, /createImagePackFile\(String capability, String parentUri,String name,String mime,String requestId\)/);
assert.match(main, /writeImagePackFile\(String capability, String documentUri,String mime,String data,String requestId\)/);
assert.match(main, /createImagePackDirectory\(String capability, String parentUri,String name,String requestId\)/);
assert.match(main, /deleteImagePackEntry\(String capability, String documentUri,String requestId\)/);
assert.match(main, /resolveImagePackEntry\(String capability, String parentUri,String targetUri,String requestId\)/);
assert.match(main, /getImagePackFolderInfo\(String capability\)/);
assert.match(main, /if \(!hasImagePackBridgeCapability\(capability\)\) return imagePackBridgeDenied\(\)/,
  "all SAF document methods reject calls without the per-WebView capability");
assert.match(folderNative, /requireDocumentUri\(/);
assert.match(folderNative, /DocumentsContract\.getDocumentId\(directoryUri\)/);
assert.match(folderNative, /DocumentsContract\.buildChildDocumentsUriUsingTree/);
assert.match(folderNative, /DocumentsContract\.createDocument/);
assert.match(folderNative, /openInputStream\(fileUri\)/);
assert.match(folderNative, /openOutputStream\(fileUri, "w"\)/);
assert.match(folderNative, /DocumentsContract\.deleteDocument/);
assert.doesNotMatch(folderNative, /splitPath\(|resolveDirectory\(String path\)|resolveFile\(String path\)/);

console.log("Exact Wayward source and Android file/SAF bridge contracts passed.");
