import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const main = readFileSync(new URL("../app/src/main/java/com/wayward/launcher/MainActivity.java", import.meta.url), "utf8");
const picker = readFileSync(new URL("../app/src/main/java/com/wayward/launcher/WebFileChooser.java", import.meta.url), "utf8");
const policy = readFileSync(new URL("../app/src/main/java/com/wayward/launcher/WebFileChooserRequestPolicy.java", import.meta.url), "utf8");
const folderBridge = readFileSync(new URL("../app/src/main/assets/imagepack-folder-picker.js", import.meta.url), "utf8");

assert.match(picker, /onShowFileChooser\s*\(/, "WebView file chooser callback must be implemented");
assert.match(picker, /getMode\(\)/, "the WebView request mode must be inspected");
assert.match(picker, /getAcceptTypes\(\)/, "MIME and extension filters must be inspected");
assert.match(picker, /isCaptureEnabled\(\)/, "capture preference must be inspected");
assert.match(picker, /getTitle\(\)/, "chooser title must be preserved");
assert.match(picker, /getFilenameHint\(\)/, "save filename hint must be preserved");
assert.match(picker, /createIntent\(\)/, "WebView's capture-capable chooser Intent must be preserved");
assert.match(picker, /Intent\.ACTION_OPEN_DOCUMENT/, "ordinary files retain the document picker");
assert.match(picker, /Intent\.ACTION_CREATE_DOCUMENT/, "save requests use create-document semantics");
assert.doesNotMatch(picker, /ACTION_OPEN_DOCUMENT_TREE/, "generic WebView file chooser must not create a competing folder-picker route");
assert.match(picker, /Intent\.EXTRA_ALLOW_MULTIPLE/, "multiple file requests preserve multi-select");
assert.match(picker, /Intent\.EXTRA_MIME_TYPES/, "multiple MIME filters are preserved");
assert.match(picker, /WebFileChooserRequestPolicy\.classify\(mode\)/, "all modes pass through the explicit classifier");
assert.match(policy, /MODE_OPEN_MULTIPLE = 1/, "mode 1 is multiple files");
assert.match(policy, /default: return Kind\.UNSUPPORTED/, "folder mode is not diverted through generic file chooser");
assert.match(policy, /MODE_OPEN = 0/, "mode 0 is one file");
assert.match(policy, /MODE_SAVE = 3/, "mode 3 is save");
assert.match(picker, /allowsMultipleFiles\(kind\)/, "only multiple-file kind enables the multi-select flag");
assert.doesNotMatch(picker, /isFolder|collectFolderResult|MODE_OPEN_FOLDER/, "generic file chooser must not own folder selection");
assert.match(picker, /request\.requestCode != requestCode/, "results must match their owning request code");
assert.match(picker, /static boolean isRequestCode\(int requestCode\)/, "MainActivity routes request-specific codes");
assert.match(picker, /web_file_chooser_stale_or_duplicate_result/, "late and duplicate results are ignored");
assert.match(picker, /web_file_chooser_request_rejected_busy/, "overlapping file requests cannot replace the active callback");
assert.match(picker, /web_file_chooser_duplicate_callback_suppressed/, "a callback only completes once");
assert.match(picker, /validateReadableUri\(uri\)/, "returned file URIs are checked for read access");
assert.match(picker, /isContentUri\(uri\)/, "untrusted URI schemes and app-private authorities are rejected");

assert.match(picker, /result_validation_failed/, "malformed success results differ from user cancellation");
assert.match(picker, /permission_denied/, "permission denial differs from cancellation");
assert.match(picker, /picker_launch_failed/, "picker launch failure differs from cancellation");
assert.match(picker, /web_file_chooser_result_status/, "final result status is journaled");
assert.match(main, /web\.setWebChromeClient\(webFileChooser\)/, "the game WebView uses this chooser client");
assert.match(main, /WebFileChooser\.isRequestCode\(req\)/, "request-specific Activity results are routed");
assert.doesNotMatch(main, /req==WebFileChooser\.REQUEST_CODE/, "no shared fixed chooser result code remains");
assert.match(folderBridge, /host\.chooseImagePackFolder\(id, nativeCapability, mode\)/,
  "showDirectoryPicker forwards its permission mode through the capability-guarded native bridge");
assert.doesNotMatch(picker, /MODE_OPEN_MULTIPLE[\s\S]{0,400}ACTION_OPEN_DOCUMENT_TREE/,
  "MODE_OPEN_MULTIPLE must never imply folder selection");

console.log("WebView file/folder chooser request contracts passed.");
