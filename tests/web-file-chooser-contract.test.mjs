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
assert.match(picker, /containsLiveCaptureIntent/, "capture requests fail explicitly unless the WebView Intent actually contains a live capture action");
assert.match(picker, /findCaptureOutputUri/, "capture output URIs are retained when the picker returns no data URI");
assert.match(picker, /FileChooserParams\.parseResult/, "ordinary file results are parsed through Android's documented result parser");
assert.match(picker, /Intent\.ACTION_OPEN_DOCUMENT/, "ordinary files retain the document picker");
assert.match(picker, /Intent source = params\.createIntent\(\)/, "ordinary file requests begin with the original WebView Intent");
assert.match(picker, /new Intent\(source\)/, "unknown source Intent extras are retained during SAF action mapping");
assert.match(picker, /picker\.setAction\(Intent\.ACTION_OPEN_DOCUMENT\)/, "the source request is mapped to persistable SAF open semantics");
assert.match(picker, /Intent\.ACTION_CREATE_DOCUMENT/, "save requests use create-document semantics");
assert.doesNotMatch(picker, /ACTION_OPEN_DOCUMENT_TREE/, "generic WebView file chooser must not create a competing folder-picker route");
assert.match(picker, /Intent\.EXTRA_ALLOW_MULTIPLE/, "multiple file requests preserve multi-select");
assert.match(picker, /Intent\.EXTRA_MIME_TYPES/, "multiple MIME filters are preserved");
assert.match(picker, /WebFileChooserRequestPolicy\.classify\(mode\)/, "all modes pass through the explicit classifier");
assert.match(policy, /parseAcceptTypes\(/, "the original accept tokens, including unmapped extensions, are retained");
assert.match(policy, /acceptsFile\(/, "returned files are checked against the original MIME-or-extension filter");
assert.match(picker, /validateWritableUri\(/, "save results require an actual writable URI grant");
assert.match(picker, /openFileDescriptor\(uri, "rw"\)/, "save result writability is checked through the content provider");
assert.match(picker, /persistReturnedPermission\(/, "persistable URI grants are retained when returned");
assert.match(picker, /picker\.putExtra\(Intent\.EXTRA_ALLOW_MULTIPLE,\s*WebFileChooserRequestPolicy\.allowsMultipleFiles\(kind\)\)/, "capture-capable intents preserve selection cardinality");
assert.match(picker, /addTitle\(picker, params\.getTitle\(\)\)/, "the original chooser title is forwarded on ordinary open requests");
const saveStart = picker.indexOf("if (WebFileChooserRequestPolicy.isSave(kind))");
const captureStart = picker.indexOf("if (captureEnabled)", saveStart);
assert.ok(saveStart >= 0 && captureStart > saveStart, "save and capture request branches remain distinct");
assert.doesNotMatch(picker.slice(saveStart, captureStart), /params\.getTitle\(\)/, "save dialog title is never reused as the suggested filename");
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
assert.match(folderBridge, /host\.chooseImagePackFolder\(id, nativeCapability, mode, initialUri \|\| ""\)/,
  "showDirectoryPicker forwards its permission mode and startIn URI through the capability-guarded native bridge");
assert.doesNotMatch(picker, /MODE_OPEN_MULTIPLE[\s\S]{0,400}ACTION_OPEN_DOCUMENT_TREE/,
  "MODE_OPEN_MULTIPLE must never imply folder selection");

console.log("WebView file/folder chooser request contracts passed.");
