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
assert.deepEqual(fixture.scannerContract.required¶»§q«^