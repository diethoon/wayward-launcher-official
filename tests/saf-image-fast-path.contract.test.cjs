const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const root = path.resolve(__dirname, "..");
const main = fs.readFileSync(path.join(root, "app/src/main/java/com/wayward/launcher/MainActivity.java"), "utf8");
const resolver = fs.readFileSync(path.join(root, "app/src/main/java/com/wayward/launcher/SafImageDocumentResolver.java"), "utf8");

assert.match(main, /SafImageDocumentResolver\.buildDocumentId\(/);
assert.match(main, /queryImageDocument\(documentUri,directDocumentId\)/);
assert.match(main, /resolverMode="external-storage-direct"/);
assert.match(main, /resolverMode="provider-directory-index"/);
assert.match(main, /private Node resolveImageViaProvider\(/);
assert.match(main, /details\.put\("resolverMode",resolverMode\)/);
assert.match(resolver, /com\.android\.externalstorage\.documents/);
assert.match(resolver, /normalizedRelativePath\.startsWith\("images\/"\)/);
assert.match(main, /if\(FolderHandler\.isLocalOnlyCharacterImagePath\(path\)\)return serveImageRequest/);
assert.match(main, /if\(FolderHandler\.isImageRequestPath\(path\)\)return folderHandler\.serveExternalImagePath/);

const openImageStart = main.indexOf("InputStream openImage(String p)");
const openImageEnd = main.indexOf("private void recordImageOpenTiming", openImageStart);
assert.ok(openImageStart >= 0 && openImageEnd > openImageStart, "image resolver block must exist");
const block = main.slice(openImageStart, openImageEnd);
assert.match(block, /if\(directDocumentId!=null\)[\s\S]*?queryImageDocument/);
assert.match(block, /else\{[\s\S]*?resolveImageViaProvider/);
console.log("saf-image-fast-path.contract: PASS");
