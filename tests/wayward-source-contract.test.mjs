import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const source = JSON.parse(readFileSync(new URL("./fixtures/wayward-file-picker-source-contract.json", import.meta.url), "utf8"));
const analysis = readFileSync(new URL("../docs/wayward-file-picker-source-analysis.md", import.meta.url), "utf8");
const matrix = readFileSync(new URL("../docs/wayward-file-picker-api-matrix.md", import.meta.url), "utf8");

assert.equal(source.sourceRepository, "diethoon/wayward");
assert.equal(source.sourceBranch, "main");
assert.equal(source.sourcePath, "Wayward_Faith_v3.0.0.html");
assert.equal(source.sourceBlobSha, "ac6b44ccb75584383ad2875f4f36a55138c228cf");
assert.equal(source.sourceIdentityVerified, true);

const input = source.imagePackFolderInput;
assert.equal(input.type, "file");
assert.equal(input.webkitdirectory, true);
assert.equal(input.multiple, true);
assert.equal(input.buttonFunction, "wfPackFolderButton");
assert.equal(input.scanFunction, "wfPackScanFolder");
assert.equal(input.changeHandler, "a");

assert.equal(source.scannerContract.fileCollection, "FileList-compatible");
assert.deepEqual(source.scannerContract.requiredCollectionMembers, ["length", "item(index)"]);
assert.deepEqual(source.scannerContract.requiredFileMembers, ["name", "text()", "webkitRelativePath"]);
assert.ok(source.scannerContract.relativePathMustPreserve.includes("image-packs/"));
assert.equal(source.scannerContract.javascriptExtension, ".js");
assert.equal(source.scannerContract.imagePackMarker, "/* WAYWARD_FAITH_IMAGE_PACK */");
assert.deepEqual(source.scannerContract.mustRemainUnmodified, ["wfPackFolderButton", "wfPackScanFolder"]);

assert.equal(source.separateLauncherApi.api, "window.showDirectoryPicker");
assert.equal(source.separateLauncherApi.result, "DirectoryHandle-compatible Promise result");
assert.equal(source.separateLauncherApi.mustNotBeConflatedWith, "webkitdirectory FileList upload");

assert.equal(source.historicalWmlBaseline.deprecated, true);
assert.equal(source.historicalWmlBaseline.sourcePath, "Wayward_WML_ko.html");
assert.equal(source.historicalWmlBaseline.sourceBlobSha, "d198eacfc127c55dc44bd5d1b8e29ff2bb7d08e6");

assert.match(analysis, /Wayward_Faith_v3\.0\.0\.html/);
assert.match(analysis, /ac6b44ccb75584383ad2875f4f36a55138c228cf/);
assert.match(analysis, /webkitdirectory/);
assert.match(analysis, /wfPackScanFolder/);
assert.match(analysis, /webkitRelativePath/);
assert.match(analysis, /FileList/);
assert.match(analysis, /showDirectoryPicker/);
assert.match(analysis, /historical.*baseline warning/i);

assert.match(matrix, /Wayward_Faith_v3\.0\.0\.html/);
assert.match(matrix, /ac6b44ccb75584383ad2875f4f36a55138c228cf/);
assert.match(matrix, /DirectoryHandle is not a FileList/);
assert.match(matrix, /Tree URI is not a FileList/);
assert.match(matrix, /API 35/);

console.log("FAITH 3.0.0 source identity and picker contract baseline passed.");
