import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const main = readFileSync(new URL("../app/src/main/java/com/wayward/launcher/MainActivity.java", import.meta.url), "utf8");
const picker = readFileSync(new URL("../app/src/main/java/com/wayward/launcher/WebFileChooser.java", import.meta.url), "utf8");
const access = readFileSync(new URL("../app/src/main/java/com/wayward/launcher/ImagePackDirectoryAccess.java", import.meta.url), "utf8");
const bridge = readFileSync(new URL("../app/src/main/assets/imagepack-folder-picker.js", import.meta.url), "utf8");

assert.match(picker, /onShowFileChooser\s*\(/, "WebView file chooser callback must be implemented");
assert.match(picker, /Intent\.ACTION_OPEN_DOCUMENT/, "ordinary files and ZIPs must keep ACTION_OPEN_DOCUMENT");
assert.match(picker, /CATEGORY_OPENABLE/, "ordinary file picker must remain OPENABLE");
assert.match(picker, /Intent\.EXTRA_ALLOW_MULTIPLE/, "ZIP multi-select must remain supported");
assert.match(picker, /getClipData\(\)/, "ZIP selections must preserve ClipData support");
assert.doesNotMatch(picker, /MODE_OPEN_FOLDER|ACTION_OPEN_DOCUMENT_TREE/, "WebView mode 2 must not control image-pack folder selection");

assert.match(bridge, /window\.showDirectoryPicker\s*=/, "the Image Studio directory API must be intercepted at document start");
assert.match(bridge, /host\.chooseImagePackFolder\(id\)/, "the folder action must call the native launcher bridge directly");
assert.match(bridge, /listImagePackDirectory/, "directory children must be read through the SAF bridge");
assert.match(bridge, /readImagePackFile/, "the selected image file must be readable through the SAF bridge");
assert.match(access, /Intent\.ACTION_OPEN_DOCUMENT_TREE/, "the native button bridge must launch the SAF tree picker");
assert.match(access, /FLAG_GRANT_PERSISTABLE_URI_PERMISSION/, "the native picker must request a persisted grant");
assert.match(access, /takePersistableUriPermission\(selected, grants\)/, "the selected tree grant must be persisted");
assert.match(access, /putString\(preferenceKey, selected\.toString\(\)\)\.commit\(\)/, "the selected tree URI must be durably saved before returning to the WebView");
assert.match(access, /buildChildDocumentsUriUsingTree/, "SAF queries must be limited to the requested directory level");
assert.match(access, /buildDocumentUriUsingTree\(treeUri, node\.documentId\)/, "image files must be opened using the selected tree URI");
assert.match(access, /image_pack_tree_uri_/, "the selected tree URI must be retained per active game profile");
assert.match(main, /new ImagePackDirectoryAccess\(this,web,activeProfileId\)/, "the production game WebView must own the native SAF bridge");
assert.match(main, /req==ImagePackDirectoryAccess\.REQUEST_CODE/, "native tree results must reach the SAF bridge");
assert.match(main, /imagepack-folder-picker\.js/, "the directory adapter must be injected before game scripts");
assert.match(main, /chooseImagePackFolder\(String requestId\)/, "WMLHost must expose the native folder action");

console.log("Native image-pack SAF bridge contract tests passed.");
