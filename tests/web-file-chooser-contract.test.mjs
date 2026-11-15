import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const main = readFileSync(new URL("../app/src/main/java/com/wayward/launcher/MainActivity.java", import.meta.url), "utf8");
const picker = readFileSync(new URL("../app/src/main/java/com/wayward/launcher/WebFileChooser.java", import.meta.url), "utf8");

assert.match(picker, /onShowFileChooser\s*\(/, "WebView file chooser callback must be implemented");
assert.match(picker, /Intent\.ACTION_OPEN_DOCUMENT/, "Android Storage Access Framework file picker must be used");
assert.match(picker, /MODE_OPEN_FOLDER\s*=\s*2/, "WebView directory requests must be recognized independently of file requests");
assert.match(picker, /getMode\(\)\s*==\s*MODE_OPEN_FOLDER/, "folder mode must be detected from WebView FileChooserParams");
assert.match(picker, /new Intent\(Intent\.ACTION_OPEN_DOCUMENT_TREE\)/, "image-pack folders must open the Android tree picker");
assert.match(picker, /FLAG_GRANT_PERSISTABLE_URI_PERMISSION/, "folder selection must request a persistable grant");
assert.match(picker, /FLAG_GRANT_PREFIX_URI_PERMISSION/, "folder selection must request access to descendants");
assert.match(picker, /DocumentsContract\.isTreeUri\(selectedFolder\)/, "only a returned SAF tree URI may be delivered as a folder");
assert.match(picker, /takePersistableUriPermission\(selectedFolder, readGrant\)/, "the tree read grant must be persisted before returning it");
assert.match(picker, /deliver\(new Uri\[\]\s*\{\s*selectedFolder\s*\}\)/, "the selected tree URI must be returned through the existing WebView callback");
const folderIntentStart = picker.indexOf("private Intent buildFolderPickerIntent()");
const fileIntentStart = picker.indexOf("private Intent buildPickerIntent(");
assert.ok(folderIntentStart >= 0 && fileIntentStart > folderIntentStart, "folder and file picker builders must remain separate");
const folderIntent = picker.slice(folderIntentStart, fileIntentStart);
const fileIntent = picker.slice(fileIntentStart, picker.indexOf("private static List<String> acceptedMimeTypes", fileIntentStart));
assert.match(folderIntent, /ACTION_OPEN_DOCUMENT_TREE/);
assert.doesNotMatch(folderIntent, /CATEGORY_OPENABLE/);
assert.match(fileIntent, /ACTION_OPEN_DOCUMENT/);
assert.match(fileIntent, /CATEGORY_OPENABLE/);
assert.match(picker, /Intent\.EXTRA_ALLOW_MULTIPLE/, "multiple ZIP selection must be supported");
assert.match(picker, /getClipData\(\)/, "ClipData selections must be read");
assert.match(picker, /getData\(\)/, "single-file selections must be read");
assert.match(picker, /callback\.onReceiveValue\(result\)/, "selected URIs must be returned to WebView");
assert.match(main, /web\.setWebChromeClient\(webFileChooser\)/, "WebView must use the file chooser client");
assert.match(main, /req==WebFileChooser\.REQUEST_CODE/, "file chooser activity results must be routed");
assert.match(main, /collectSelectedUris\(data\)/, "launcher ZIP picker must also accept ClipData-only results");
assert.doesNotMatch(main, /setWebChromeClient\(new WebChromeClient\(\)\)/, "empty default WebChromeClient must not remain");

console.log("WebFileChooser contract tests passed.");
