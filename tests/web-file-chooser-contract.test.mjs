import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const main = readFileSync(new URL("../app/src/main/java/com/wayward/launcher/MainActivity.java", import.meta.url), "utf8");
const picker = readFileSync(new URL("../app/src/main/java/com/wayward/launcher/WebFileChooser.java", import.meta.url), "utf8");

assert.match(picker, /onShowFileChooser\s*\(/, "WebView file chooser callback must be implemented");
assert.match(picker, /Intent\.ACTION_OPEN_DOCUMENT/, "Android Storage Access Framework file picker must be used");
assert.match(picker, /CATEGORY_OPENABLE/, "ordinary file selection must remain openable");
assert.match(picker, /Intent\.EXTRA_ALLOW_MULTIPLE/, "multiple ZIP selection must be supported");
assert.match(picker, /getClipData\(\)/, "ClipData selections must be read");
assert.match(picker, /getData\(\)/, "single-file selections must be read");
assert.match(picker, /callback\.onReceiveValue\(result\)/, "selected file URIs must be returned to WebView");
assert.match(picker, /MODE_OPEN_FOLDER\s*=\s*2/, "folder requests must be distinguished without requiring compileSdk 37");
assert.match(picker, /buildFolderPickerIntent\(fileChooserParams\)/, "folder requests must use the directory-only picker");
assert.match(picker, /Intent\.ACTION_OPEN_DOCUMENT_TREE/, "folder requests must open a SAF tree, never a file picker");
assert.match(picker, /DocumentsContract\.isTreeUri\(selected\)/, "folder results must be validated as a tree URI");
assert.match(picker, /takePersistableUriPermission\(selected, grants\)/, "folder access should persist when the provider grants it");
assert.match(picker, /Intent\.ACTION_OPEN_DOCUMENT/, "ordinary files must keep the document picker");
assert.match(main, /web\.setWebChromeClient\(webFileChooser\)/, "WebView must use the file chooser client");
assert.match(main, /req==WebFileChooser\.REQUEST_CODE/, "file chooser activity results must be routed");
assert.match(main, /collectSelectedUris\(data\)/, "launcher ZIP picker must accept ClipData-only results");
assert.doesNotMatch(main, /setWebChromeClient\(new WebChromeClient\(\)\)/, "empty default WebChromeClient must not remain");

console.log("WebView ordinary file and ZIP chooser contract tests passed.");
