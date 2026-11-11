import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const main = readFileSync(new URL("../app/src/main/java/com/wayward/launcher/MainActivity.java", import.meta.url), "utf8");
const picker = readFileSync(new URL("../app/src/main/java/com/wayward/launcher/WebFileChooser.java", import.meta.url), "utf8");

assert.match(picker, /onShowFileChooser\s*\(/, "WebView file chooser callback must be implemented");
assert.match(picker, /Intent\.ACTION_OPEN_DOCUMENT/, "Android Storage Access Framework must be used");
assert.match(picker, /Intent\.EXTRA_ALLOW_MULTIPLE/, "multiple ZIP selection must be supported");
assert.match(picker, /getClipData\(\)/, "ClipData selections must be read");
assert.match(picker, /getData\(\)/, "single-file selections must be read");
assert.match(picker, /callback\.onReceiveValue\(result\)/, "selected URIs must be returned to WebView");
assert.match(main, /web\.setWebChromeClient\(webFileChooser\)/, "WebView must use the file chooser client");
assert.match(main, /req==WebFileChooser\.REQUEST_CODE/, "file chooser activity results must be routed");
assert.match(main, /collectSelectedUris\(data\)/, "launcher ZIP picker must also accept ClipData-only results");
assert.doesNotMatch(main, /setWebChromeClient\(new WebChromeClient\(\)\)/, "empty default WebChromeClient must not remain");


const mainRequestCodes = main.match(/private static final int PICK_TREE=(\d+), PICK_MOD_ZIP=(\d+), REQUEST_GOOGLE_AUTH=(\d+);/);
assert.ok(mainRequestCodes, "launcher folder, ZIP, and Google OAuth request codes must be declared");
const webFileChooserRequestCode = picker.match(/static final int REQUEST_CODE = (\d+);/);
assert.ok(webFileChooserRequestCode, "WebView file chooser request code must be declared");
const allRequestCodes = [
  Number(mainRequestCodes[1]),
  Number(mainRequestCodes[2]),
  Number(mainRequestCodes[3]),
  Number(webFileChooserRequestCode[1]),
];
assert.equal(new Set(allRequestCodes).size, allRequestCodes.length,
  `Android activity request codes must be unique; found: ${allRequestCodes.join(", ")}`);

console.log("WebFileChooser contract tests passed.");
