import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { readFileSync } from "node:fs";

const main = readFileSync(new URL("../app/src/main/java/com/wayward/launcher/MainActivity.java", import.meta.url), "utf8");
const picker = readFileSync(new URL("../app/src/main/java/com/wayward/launcher/WebFileChooser.java", import.meta.url), "utf8");
const access = readFileSync(new URL("../app/src/main/java/com/wayward/launcher/ImagePackDirectoryAccess.java", import.meta.url), "utf8");
const bridge = readFileSync(new URL("../app/src/main/assets/imagepack-folder-picker.js", import.meta.url), "utf8");
const manifest = readFileSync(new URL("../app/src/main/AndroidManifest.xml", import.meta.url), "utf8");
const smoke = readFileSync(new URL("../scripts/android15-emulator-smoke.py", import.meta.url), "utf8");

const chooserSemanticRegression = String.raw`
import copy
import runpy

verify = runpy.run_path("scripts/android15-emulator-smoke.py")["validate_file_chooser_diagnostics"]

def event(request_id, stage, details):
    return {"requestId": request_id, "sessionId": "game-session", "stage": stage, "details": details}

events = [
    event("json-request", "web_file_chooser_called", {"mode": 1, "acceptTypes": "application/json"}),
    event("json-request", "web_file_chooser_activity_result", {"result": "selected", "clipItemCount": 2}),
    event("json-request", "web_file_chooser_callback_delivered", {"selectedCount": 2, "cancelled": False}),
    event("zip-request", "web_file_chooser_called", {"mode": 1, "acceptTypes": "application/zip"}),
    event("zip-request", "web_file_chooser_activity_result", {"result": "selected", "clipItemCount": 2}),
    event("zip-request", "web_file_chooser_callback_delivered", {"selectedCount": 2, "cancelled": False}),
]
summary = verify(events)
assert summary["packJson"]["requestId"] == "json-request"
assert summary["packJson"]["nativeClipItemCount"] == 2
assert summary["packJson"]["callbackSelectedCount"] == 2
assert summary["zip"]["requestId"] == "zip-request"
assert summary["zip"]["nativeClipItemCount"] == 2
assert summary["zip"]["callbackSelectedCount"] == 2

legacy = copy.deepcopy(events)
legacy[0]["details"]["mode"] = "multiple"
legacy[3]["details"]["mode"] = "multiple"
verify(legacy)

invalid_mode = copy.deepcopy(events)
invalid_mode[3]["details"]["mode"] = 0
try:
    verify(invalid_mode)
except RuntimeError:
    pass
else:
    raise AssertionError("single-file MODE_OPEN must not satisfy multi-select acceptance")

missing_callback = [item for item in events if not (
    item["requestId"] == "zip-request" and item["stage"] == "web_file_chooser_callback_delivered"
)]
try:
    verify(missing_callback)
except RuntimeError:
    pass
else:
    raise AssertionError("missing ZIP callback was accepted")

mis_correlated = copy.deepcopy(events)
mis_correlated[-1]["requestId"] = "other-request"
try:
    verify(mis_correlated)
except RuntimeError:
    pass
else:
    raise AssertionError("a callback from another request was accepted")

partial_callback = copy.deepcopy(events)
partial_callback[-1]["details"]["selectedCount"] = 1
try:
    verify(partial_callback)
except RuntimeError:
    pass
else:
    raise AssertionError("a one-file result was accepted as ZIP multi-select")

print("PASS: Android WebView chooser diagnostics correlate numeric MODE_OPEN_MULTIPLE requests, native results, and one callback per pack.json/ZIP request.")
`;
execFileSync("python3", ["-c", chooserSemanticRegression], { encoding: "utf8" });

const helper = readFileSync(new URL("../app/src/main/java/com/wayward/launcher/ImagePackFolderPickerScript.java", import.meta.url), "utf8");
const acceptance = readFileSync(new URL("../app/src/debug/java/com/wayward/launcher/ImagePackPickerAcceptanceActivity.java", import.meta.url), "utf8");
const imageDiagnostics = readFileSync(new URL("../app/src/main/java/com/wayward/launcher/ImagePackDiagnostics.java", import.meta.url), "utf8");
const exporter = readFileSync(new URL("../app/src/main/java/com/wayward/launcher/DiagnosticsBundleExporter.java", import.meta.url), "utf8");
const launcherLogger = readFileSync(new URL("../app/src/main/java/com/wayward/launcher/DiagnosticsLogger.java", import.meta.url), "utf8");
const uploadPolicy = readFileSync(new URL("../app/src/main/java/com/wayward/launcher/DiagnosticsUploadPolicy.java", import.meta.url), "utf8");
const uploader = readFileSync(new URL("../app/src/main/java/com/wayward/launcher/DiagnosticsUploader.java", import.meta.url), "utf8");
const diagnosticPaths = readFileSync(new URL("../app/src/main/res/xml/diagnostics_file_paths.xml", import.meta.url), "utf8");
const legacyBackup = readFileSync(new URL("../app/src/main/res/xml/backup_rules.xml", import.meta.url), "utf8");
const extractionBackup = readFileSync(new URL("../app/src/main/res/xml/data_extraction_rules.xml", import.meta.url), "utf8");

const registrationPicker = main.split("private void chooseFolder()")[1].split("private void chooseModZip()")[0];
assert.doesNotMatch(registrationPicker, /EXTRA_INITIAL_URI/,
  "registering another game must not trap the document picker inside the previously selected game folder");

assert.match(picker, /onShowFileChooser\s*\(/, "WebView file chooser callback must be implemented");
assert.match(picker, /Intent\.ACTION_OPEN_DOCUMENT/, "ordinary files and ZIPs must keep ACTION_OPEN_DOCUMENT");
assert.match(picker, /web_file_chooser_called/, "HTML file-input calls must be correlated in the durable diagnostic journal");
assert.match(picker, /web_file_chooser_activity_result/, "Android file-picker Activity results must be journaled");
assert.match(picker, /web_file_chooser_callback_delivered/, "WebView file callback delivery or cancellation must be journaled");
assert.match(picker, /WebFileChooserRequestPolicy\.allowsMultipleFiles\(kind\)/, "the actual pack.json input must preserve MODE_OPEN_MULTIPLE semantics through the centralized request classifier");
assert.match(picker, /EXTRA_ALLOW_MULTIPLE/, "the native document picker must receive the multi-select flag");
assert.match(picker, /WebFileChooser\(Activity activity, String sessionId\)/, "file-input diagnostics must share the active game-session ID");
assert.match(main, /new WebFileChooser\(this,imagePackDiagnosticSessionId\)/, "the active session must flow into the ordinary file chooser");
assert.match(smoke, /Pick multiple pack\.json files/, "the real game multi-pack JSON file-input path must be exercised");
assert.match(smoke, /application\/json/, "the file-input acceptance test must use the MIME type selected by the actual game");
assert.match(picker, /CATEGORY_OPENABLE/, "ordinary file picker must remain OPENABLE");
assert.match(picker, /Intent\.EXTRA_ALLOW_MULTIPLE/, "ZIP multi-select must remain supported");
assert.match(picker, /getClipData\(\)/, "ZIP selections must preserve ClipData support");
assert.doesNotMatch(picker, /ACTION_OPEN_DOCUMENT_TREE|collectFolderResult|isFolder\(/,
  "generic WebView file chooser must not provide a competing folder picker path");
assert.match(picker, /WebFileChooserRequestPolicy\.allowsMultipleFiles\(kind\)/,
  "multiple-file mode must remain file-only");
assert.doesNotMatch(picker, /kind\s*==\s*WebFileChooserRequestPolicy\.Kind\.OPEN_MULTIPLE[\s\S]{0,300}ACTION_OPEN_DOCUMENT_TREE/,
  "MODE_OPEN_MULTIPLE must never be routed to folder selection");
assert.match(picker, /Intent\.ACTION_OPEN_DOCUMENT/, "ordinary files and ZIPs must retain the SAF document picker");

assert.match(bridge, /window\.showDirectoryPicker\s*=/, "the Image Studio directory API must be intercepted at document start");
assert.match(bridge, /host\.chooseImagePackFolder\(id, nativeCapability, mode\)/,
  "the original File System Access read/readwrite mode must cross the native bridge unchanged");
assert.match(bridge, /show_directory_picker_called/, "the actual showDirectoryPicker call must be journaled");
assert.match(bridge, /wmlhost_choose_folder_call_start/, "the JS-to-native bridge call must be journaled");
assert.match(bridge, /setTimeout\([\s\S]*?120000/, "a lost native callback must not leave the game Promise pending forever");
assert.match(bridge, /__waywardNativeImagePackPickerDispatch/, "native results must be dispatched to the calling same-origin frame");
assert.match(bridge, /listImagePackDirectoryForRequest/, "directory enumeration must retain its originating request ID");
assert.match(bridge, /readImagePackFileForRequest/, "file reads must retain their originating request ID");
assert.ok(bridge.indexOf('Object.defineProperty(window, "showDirectoryPicker"') <
          bridge.indexOf("window.__waywardNativeImagePackPickerInstalled = true;"),
          "the installed flag must only be set after the picker function is installed");
assert.match(bridge, /listImagePackDirectory/, "directory children must use the existing native bridge contract");
assert.match(bridge, /readImagePackFile/, "selected image files must remain readable through the existing bridge");
assert.match(bridge, /writeImagePackFile/, "selected image files must remain writable through the existing bridge");
assert.match(bridge, /createImagePackDirectory/, "the directory API must retain folder creation support");
assert.match(bridge, /deleteImagePackEntry/, "the directory API must retain entry deletion support");

assert.match(helper, /typeof frame\.showDirectoryPicker==='function'/, "runtime verification must check the real API function, not only a marker");
assert.match(helper, /frame\.__waywardNativeImagePackPickerInstalled!==true/,
  "a pre-existing browser API must not suppress installation of the native adapter");
assert.match(helper, /frame\.eval\(source\)/, "runtime fallback must reach same-origin child frames");
assert.doesNotMatch(access, /ImagePackFileSystemAccess|fileSystemAccess|ImagePackPathGuard|ImagePackAtomicFileWriter/,
  "the retired local-filesystem chooser/backend and its path helpers must not remain in the SAF provider path");
assert.doesNotMatch(access, /ImagePackFileSystemAccess|fileSystemAccess|Environment\.isExternalStorageManager|AlertDialog/,
  "image-pack selection must not instantiate or fall back to a Wayward-owned filesystem browser");
assert.match(access, /new Intent\(Intent\.ACTION_OPEN_DOCUMENT_TREE\)/,
  "every supported Android version must use the system SAF tree picker");
assert.match(access, /"readwrite"\.equals\(mode\)[\s\S]{0,120}FLAG_GRANT_WRITE_URI_PERMISSION/,
  "the session read/readwrite mode must be forwarded to the system permission flags");
assert.doesNotMatch(access, /EXTRA_INITIAL_URI/, "the launcher must not override Android DocumentsUI start location");
assert.match(access, /"readwrite"\.equals\(mode\)[\s\S]{0,120}FLAG_GRANT_WRITE_URI_PERMISSION/,
  "the Android system request must preserve the caller's read versus readwrite permission mode");
assert.match(access, /Intent\.FLAG_GRANT_PERSISTABLE_URI_PERMISSION/,
  "the system tree request must ask for a persistable grant");
assert.match(access, /takePersistableUriPermission\(selected, grants\)/,
  "the selected tree must retain its returned permissions");
assert.match(access, /getPersistedUriPermissions\(\)/,
  "persisted tree permission must be verified and restored after restart");
assert.match(access, /DocumentsContract\.isTreeUri\(selected\)/,
  "the result must be validated as a document tree");
assert.match(access, /releasePersistedTree\(previousTreeUri\)/,
  "the previous tree grant is released only after the replacement is saved");
assert.doesNotMatch(access, /EXTRA_INITIAL_URI|resolveInitialPickerUri|pickerInitialSource/,
  "the session request must open the Android system picker without launcher-selected start-location overrides");
assert.match(access, /native_folder_picker_request_rejected_busy/,
  "overlapping requests cannot replace the active request");
assert.match(access, /native_saf_picker_uri_missing/,
  "a successful Activity result without a URI is not treated as cancellation");
assert.match(access, /result\.put\("state", "SUCCESS"\)/,
  "a successful result has an explicit request state");
assert.match(access, /"CANCELLED"/, "user cancellation is distinct from failure");
assert.match(access, /"DENIED"/, "permission and security denial have an explicit state");
assert.match(access, /"UNSUPPORTED"/, "unsupported requests have an explicit state");
assert.match(access, /DocumentsContract\.buildChildDocumentsUriUsingTree/,
  "subdirectory enumeration remains provider-backed");
assert.match(access, /DocumentsContract\.buildDocumentUriUsingTree/,
  "file access remains provider-backed without assuming a local path");
assert.match(access, /openInputStream\(documentUri\)/,
  "image files are streamed from the SAF provider");
assert.match(access, /createDocument\(/,
  "image-pack file and folder operations remain on the SAF provider");

assert.match(legacyBackup, /exclude domain="sharedpref" path="wayward_launcher.xml"/,
  "legacy cloud backup must exclude the profile and game-session preferences file");
assert.ok(legacyBackup.includes('exclude domain="file" path="diagnostics/"'),
  "legacy cloud backup must exclude local diagnostic journals");
assert.ok(legacyBackup.includes('exclude domain="root" path="app_webview/"'),
  "legacy cloud backup must exclude WebView game saves and session storage");
const cloudBackupRules = extractionBackup.split("<cloud-backup>")[1].split("</cloud-backup>")[0];
const deviceTransferRules = extractionBackup.split("<device-transfer>")[1].split("</device-transfer>")[0];
assert.ok(cloudBackupRules.includes('exclude domain="sharedpref" path="wayward_launcher.xml"'),
  "Android cloud backup must exclude game registrations");
assert.ok(deviceTransferRules.includes('exclude domain="sharedpref" path="wayward_launcher.xml"'),
  "Android device-to-device transfer must exclude game registrations");
assert.ok(cloudBackupRules.includes('exclude domain="root" path="app_webview/"'),
  "Android cloud backup must exclude WebView game saves and session storage");
assert.ok(deviceTransferRules.includes('exclude domain="root" path="app_webview/"'),
  "Android device-to-device transfer must exclude WebView game saves and session storage");
assert.equal(extractionBackup.split('exclude domain="file" path="diagnostics/"').length - 1, 2,
  "both cloud backup and device transfer must exclude picker diagnostics");
assert.doesNotMatch(manifest, /android\.permission\.MANAGE_EXTERNAL_STORAGE/,
  "image-pack access must not request all-files access or route users to storage settings");
assert.match(manifest, /androidx\.core\.content\.FileProvider/, "diagnostic ZIP sharing must use a non-exported FileProvider");
assert.match(diagnosticPaths, /cache-path name="diagnostics" path="diagnostics\//, "the FileProvider must expose only the diagnostics cache directory");
assert.match(imageDiagnostics, /imagepack\.ndjson/, "image-pack diagnostics must be stored separately from auto-uploaded launcher logs");
assert.match(imageDiagnostics, /output\.getFD\(\)\.sync\(\)/, "image-pack diagnostic events must be synced to durable storage");
assert.match(imageDiagnostics, /requestId/, "diagnostic events must carry a common request ID");
assert.match(imageDiagnostics, /"acceptTypes"/, "file chooser MIME requests must be safely retained in diagnostics");
assert.match(imageDiagnostics, /"selectedCount"/, "file chooser result counts must be safely retained in diagnostics");
assert.match(exporter, /application\/zip/, "the collected diagnostic archive must be shared as a ZIP");
assert.match(exporter, /Intent\.ACTION_SEND/, "the ZIP must be shared through Android's native share sheet");
assert.match(exporter, /launcher\.ndjson/, "the manual diagnostic ZIP must include the general launcher event journal");
assert.match(exporter, /imagepack\.ndjson/, "the manual diagnostic ZIP must include image-pack request traces");
assert.match(launcherLogger, /DiagnosticsUploader\.uploadPending\(app\)/,
  "urgent launcher events must trigger automatic upload");
assert.match(launcherLogger, /DiagnosticsUploadPolicy\.isUploadableLevel\(event\.optString\("level"\)\)/,
  "the automatic upload queue must filter events before adding them to the payload");
assert.match(uploadPolicy, /"WARN"\.equalsIgnoreCase\(level\) \|\| "ERROR"\.equalsIgnoreCase\(level\)/,
  "only WARN and ERROR levels may be automatically uploaded");
assert.match(launcherLogger, /CONTENT_URI\.matcher\(out\)/,
  "launcher log must redact full content URIs");
assert.match(launcherLogger, /ABSOLUTE_PATH\.matcher\(out\)/,
  "launcher log must redact external and private absolute paths");
assert.match(imageDiagnostics, /ABSOLUTE_PATH\.matcher\(safe\)/,
  "image-pack log must redact external and private absolute paths");
assert.doesNotMatch(uploader, /imagepack\.ndjson|wayward-diagnostics-|DiagnosticsBundleExporter/,
  "automatic upload must not read image-pack logs or user-shared diagnostic ZIPs");
assert.match(main, /Toast\.makeText\(this,\s*"진단 ZIP 수집 실패: "\s*\+\s*msg\(error\)/,
  "ZIP creation/share failure must be visible to the user");
assert.match(main, /"diagnostics_share_failed"/,
  "ZIP creation/share failure must be retained in the local diagnostic journal");
assert.match(manifest, /android:allowBackup="true"/,
  "global Android backup must remain enabled while targeted data is excluded");
assert.match(launcherLogger, /"ERROR".equalsIgnoreCase\(level\) \|\| "WARN".equalsIgnoreCase\(level\)/,
  "automatic upload must remain limited to errors and warnings");
assert.match(uploader, /https:\/\/wayward-launcher-diagnostics-/,
  "diagnostic events must be sent over HTTPS");

const bundleUploader = readFileSync(new URL("../app/src/main/java/com/wayward/launcher/DiagnosticsBundleUploader.java", import.meta.url), "utf8");
assert.match(main, /DiagnosticsBundleUploader\.upload\(this, selectedArchive/,
  "the diagnostics button must automatically upload the selected new or retry ZIP on explicit tap");
assert.match(main, /DiagnosticsBundleExporter\.share\(this, selectedArchive\)/,
  "the existing Android sharesheet must use the same selected ZIP file sent to Cloudflare");
assert.match(exporter, /static void share\(Activity activity, File archive\)/,
  "the existing native share flow must continue accepting a generated ZIP without rebuilding it");
assert.match(bundleUploader, /https:\/\/wayward-diagnostics-ingest\.trpgengine\.workers\.dev/,
  "manual ZIP upload must target the existing Cloudflare ingestion Worker");
assert.match(bundleUploader, /\/v1\/ticket/,
  "the Cloudflare Worker must issue a one-time checksum-bound upload ticket");
assert.match(bundleUploader, /\/v1\/upload/,
  "the Cloudflare Worker must receive ZIPs only through the ticketed upload endpoint");
assert.match(bundleUploader, /MessageDigest\.getInstance\("SHA-256"\)/,
  "the ZIP must be SHA-256 checked before ticket creation");
assert.match(bundleUploader, /application\/zip/,
  "the final ticketed upload must preserve application/zip content type");
assert.match(bundleUploader, /DiagnosticsLogger\.deviceId/,
  "the worker's per-install rate limit must use the stable local install identifier");
assert.match(bundleUploader, /MAX_BUNDLE_BYTES/,
  "the uploader must honor the Worker 5 MiB size ceiling");

assert.match(main, /findPendingArchive\(this\)/,
  "the next diagnostics tap must reuse the previously failed ZIP instead of generating a new one");
assert.match(main, /DiagnosticsBundleUploader\.isUploading\(\)/,
  "duplicate diagnostics taps must not enqueue concurrent uploads or create extra ZIPs");
assert.match(bundleUploader, /PENDING_SUFFIX = "\.upload-pending"/,
  "failed uploads must persist a retry marker beside the exact ZIP");
assert.match(bundleUploader, /rememberPendingArchive\(archive\)/,
  "network, timeout, and server failures must keep the ZIP available for retry");
assert.match(bundleUploader, /clearPendingArchive\(archive\)/,
  "the retry marker must only clear following a valid server success response");
const diagnosticsRetryTest = readFileSync(new URL("../app/src/androidTest/java/com/wayward/launcher/DiagnosticsBundleUploaderInstrumentedTest.java", import.meta.url), "utf8");
assert.match(diagnosticsRetryTest, /failedZipIsSelectedAgainAndMarkerClearsOnlyAfterSuccess/,
  "Android instrumentation must verify failed ZIP reuse and marker clearing");


assert.match(bundleUploader, /setConnectTimeout|setReadTimeout/,
  "network operations must have bounded timeouts");
assert.doesNotMatch(bundleUploader, /readPending|DiagnosticsLogger\.acknowledge/,
  "the ZIP uploader must not alter the independent WARN/ERROR event queue");

assert.match(uploader, /DiagnosticsLogger\.acknowledge\(app, ids\)/,
  "locally retained events must only be acknowledged after successful upload");
assert.match(main, /DiagnosticsUploader\.uploadPending\(this\)/,
  "unacknowledged diagnostic events must retry when the launcher resumes");
assert.match(exporter, /lastFailureStage/, "the exported metadata must report the last failure stage");
assert.doesNotMatch(smoke, /MANAGE_EXTERNAL_STORAGE|ImagePackFileSystemAccess|이 폴더 선택|새 폴더/,
  "Android 15 acceptance cannot depend on the retired browser or all-files permission");
assert.match(smoke, /select_download_tree_through_documents_ui/,
  "Android 15 acceptance must automate the actual system folder picker");
assert.match(smoke, /IMAGEPACK_FOLDER = "\/sdcard\/Download\/WaywardImagePackAcceptance"/,
  "Android 15 acceptance must select a real child folder, not the ungrantable Download root");
assert.match(smoke, /current_folder == target_name\.casefold\(\)/,
  "DocumentsUI automation must enter the selectable image-pack folder before granting access");
assert.doesNotMatch(smoke, /USE THIS FOLDER[\s\S]{0,500}current_folder in \("download", "downloads"\)/,
  "the Download root must never be treated as the selectable SAF grant target");
assert.match(smoke, /PASS_SINGLE_FILE/,
  "the real game page must verify its independent single-file input contract");
assert.match(smoke, /accept="application\/octet-stream"/,
  "the actual single-file input must preserve its exact MIME filter through ACTION_OPEN_DOCUMENT");
assert.match(smoke, /single-file.bin/,
  "the Android 15 single-file chooser must return exactly one selected document");
assert.match(smoke, /com\.google\.android\.documentsui:id\/item_root/,
  "single-file acceptance must tap the provider's enabled clickable document card, not the non-clickable title text");
assert.match(smoke, /node_bounds_exact\([\s\S]*?"Open"/,
  "single-file acceptance must handle a provider's explicit positive confirmation action");
assert.match(smoke, /android15-single-file-picker-stuck\.xml/,
  "failed single-file acceptance must preserve the exact DocumentsUI state for forensic analysis");
assert.match(smoke, /DirectoryHandle\.keys\(\)/,
  "the actual same-origin iframe must exercise the directory keys() iterator");
assert.match(smoke, /DirectoryHandle\.values\(\)/,
  "the actual same-origin iframe must exercise the directory values() iterator");
assert.match(smoke, /DirectoryHandle\.resolve\(\)/,
  "the actual same-origin iframe must exercise relative handle resolution");
assert.match(smoke, /createWritable\(\) write\/read failed/,
  "the actual same-origin iframe must exercise FileHandle.createWritable and read the written bytes");
assert.match(smoke, /NotFoundError/,
  "a nonexistent file lookup must preserve the File System Access API error contract");
assert.match(smoke, /PASS persisted-after-restart/,
  "the selected SAF tree must remain readable after process restart");
assert.match(smoke, /"verify_revoked"/,
  "Android 15 acceptance must launch the persisted SAF permission-revocation scenario");
assert.match(acceptance, /revokePersistedTreeGrantForTesting/,
  "the debug acceptance Activity must revoke the exact saved SAF grant before the denial check");
assert.match(acceptance, /PASS test fixture released the persisted SAF tree grant/,
  "the device fixture must record that the real persisted tree permission was released");
assert.match(acceptance, /verifyPermissionRevoked\(\)/,
  "the post-revocation test must verify provider reads and listings are denied");
assert.match(main, /private final SafImageCache imageCache = new SafImageCache\(MainActivity\.this\)/,
  "the game's original SAF image cache must remain in its resource-serving layer");
assert.match(main, /MAX_CACHED_DIRECTORIES=2048/,
  "the game's bounded directory index must remain intact");
assert.match(main, /MAX_CACHED_CHILDREN=100000/,
  "the game's bounded child index must remain intact");
assert.match(main, /serveImageRequestByMode\(/,
  "local, PC, and NAI image routing must remain in place");
assert.match(main, /isLocalOnlyCharacterImagePath\(/,
  "the existing local-only new-character image policy must remain in place");

assert.match(main, /new ImagePackDirectoryAccess\(this,web,activeProfileId,imagePackDiagnosticSessionId\)/, "the production game WebView must own the request-correlated native image-pack bridge");
assert.match(main, /MainActivity\.this\.runOnUiThread\(\(\) ->\s*chooseImagePackFolderOnUiThread\(requestId, capability, mode\)\)\)/,
  "the production JavascriptInterface must hand WebView URL access and Activity launch to the UI thread");
assert.match(main, /private void chooseImagePackFolderOnUiThread\(String requestId, String capability, String mode\)/,
  "origin and capability checks must execute within the UI-thread bridge handler");
assert.match(acceptance, /verifiedTestPageUrl/,
  "the debug SAF fixture must validate the last trusted WebViewClient URL when loadDataWithBaseURL and WebView.getUrl diverge");
assert.match(acceptance, /onPageStarted\([\s\S]*?verifiedTestPageUrl = ""/,
  "a new top-level navigation must clear the previously trusted debug fixture origin");
assert.match(acceptance, /onPageFinished[\s\S]*?isSameOrigin\(url, TEST_ORIGIN\)[\s\S]*?verifiedTestPageUrl = url/,
  "the debug fixture may trust only an origin-validated page after it finishes loading");
assert.match(acceptance, /runOnUiThread\(\(\) -> chooseImagePackFolderOnUiThread\(requestId, capability, mode\)\)\)/,
  "the Android 15 debug acceptance bridge must use the same WebView thread contract");
assert.match(main, /recordImagePackDiagnostic\(String requestId/, "JavaScript diagnostics must enter the durable journal through WMLHost");
assert.match(main, /diagnostics=compactButton\("진단"/, "the launcher home screen must expose the diagnostics share action");
assert.match(main, /DiagnosticsBundleExporter\.share\(this,\s*selectedArchive\)/,
  "the diagnostics button must open the Android share flow with the same selected ZIP sent to Cloudflare");
assert.match(access, /__waywardNativeImagePackPickerDispatch/, "native completion must resolve the original frame instead of main-frame-only delivery");
assert.match(main, /imagePackDirectoryAccess\.onResume\(\)/, "the production activity must revalidate the persisted SAF tree grant on resume");
const gameLaunch = main.split("private void launchProfileSession(String id)")[1]
  .split("private static MainActivity findSessionActivity")[0];
assert.match(gameLaunch, /intent\.addFlags\(Intent\.FLAG_ACTIVITY_NEW_DOCUMENT\)/,
  "profile sessions must use their distinct URI as a document-task key");
assert.doesNotMatch(gameLaunch, /FLAG_ACTIVITY_REORDER_TO_FRONT/,
  "component-only reordering must not route one profile into another profile's MainActivity");
const imageGenRoute = main.split("private void openImageGenPanel()")[1]
  .split("Runnable openSettings")[0];
assert.match(imageGenRoute, /route\.addFlags\(Intent\.FLAG_ACTIVITY_NEW_DOCUMENT\)/,
  "Image Studio must route to the URI-matched owner session task");
assert.doesNotMatch(imageGenRoute, /FLAG_ACTIVITY_REORDER_TO_FRONT/,
  "Image Studio must not reorder to an arbitrary MainActivity instance");
assert.doesNotMatch(manifest, /documentLaunchMode/,
  "document-task behavior must be opt-in on session intents, not global manifest behavior");
const gameSessionNameIndex = manifest.indexOf('android:name=".GameSessionActivity"');
const gameSessionStartIndex = manifest.lastIndexOf("<activity", gameSessionNameIndex);
const gameSessionEndIndex = manifest.indexOf("/>", gameSessionStartIndex);
const gameSessionActivity = gameSessionNameIndex >= 0 && gameSessionStartIndex >= 0 && gameSessionEndIndex > gameSessionStartIndex
  ? manifest.slice(gameSessionStartIndex, gameSessionEndIndex + 2) : "";
assert.ok(gameSessionActivity.length > 0,
  "the dedicated game-session activity must be declared");
assert.ok(gameSessionActivity.includes('android:taskAffinity="${applicationId}.games"'),
  "the real game-session activity must use a task affinity separate from the app-icon launcher task");
const launcherActivity = manifest.match(/<activity android:name="\.MainActivity"[^>]*>/)?.[0] || "";
assert.ok(launcherActivity.includes('android:launchMode="singleTop"'),
  "the app-icon launcher MainActivity must receive a new launcher intent instead of stacking a duplicate home Activity");
assert.doesNotMatch(gameSessionActivity, /android:launchMode=/,
  "per-profile GameSessionActivity must retain standard document-root behavior and independent Activity instances");
assert.doesNotMatch(manifest, /<activity-alias/,
  "game task affinity must be defined on a real activity, not an alias that inherits MainActivity affinity");
const gameSessionSource = readFileSync(new URL("../app/src/main/java/com/wayward/launcher/GameSessionActivity.java", import.meta.url), "utf8");
assert.match(gameSessionSource, /extends MainActivity/,
  "the dedicated session activity must reuse the existing launcher implementation");
assert.match(main, /new Intent\(this,GameSessionActivity\.class\)/,
  "game and Image Studio routes must target the dedicated session Activity class");
assert.match(main, /public class MainActivity extends Activity/,
  "the shared MainActivity implementation must remain subclassable by GameSessionActivity");

assert.match(main, /else if\(sessionProfileId\(getIntent\(\)\)!=null\)\{[\s\S]*?finish\(\);/, "root BACK in a game session must finish only the session Activity");
assert.match(main, /if\(web\.canGoBack\(\)\)\{\s*web\.goBack\(\);/, "BACK inside WebView history must stay inside the game");
assert.match(main, /ImagePackFolderPickerScript\.installDocumentStart\(\s*web,\s*imagePackFolderPickerScript,\s*origin\)/, "the directory adapter must register at document start when supported");
assert.match(main, /ImagePackFolderPickerScript\.installAfterPageFinished\(/, "the directory adapter must have a runtime fallback");
assert.match(readFileSync(new URL("../app/src/main/java/com/wayward/launcher/ImagePackFolderPickerScript.java", import.meta.url), "utf8"), /isSameOrigin\(/, "runtime fallback must be restricted to the active game origin");
assert.match(readFileSync(new URL("../app/src/main/java/com/wayward/launcher/ImagePackFolderPickerScript.java", import.meta.url), "utf8"), /WebViewCompat\.addDocumentStartJavaScript/, "supported document-start injection must remain");
assert.match(main, /installDocumentStartAsset\("imagegen-proxy\.js", origin\)/, "image-generation scripts must remain installed separately");
assert.match(main, /chooseImagePackFolder(String requestId, String capability, String mode)/,
  "WMLHost preserves the folder action and requires a scoped capability");
assert.match(main, /matchesCapability\(\s*imagePackFolderPickerCapability, capability\)/,
  "native folder requests validate the injected per-WebView capability");
assert.match(main, /isSameOrigin\(web\.getUrl\(\), imagePackFolderPickerOrigin\)/,
  "native folder requests verify the active game origin");
assert.match(access, /native_folder_picker_request_rejected_busy/,
  "overlapping folder requests are rejected without replacing the active request");
assert.match(main, /listImagePackDirectory\(String path\)/, "WMLHost must preserve the existing directory-list method");
assert.match(main, /readImagePackFile\(String path\)/, "WMLHost must preserve the existing file-read method");
assert.match(readFileSync(new URL("../app/src/debug/java/com/wayward/launcher/ImagePackPickerAcceptanceActivity.java", import.meta.url), "utf8"), /PASS_FS_DIRECTORY_HANDLE/, "acceptance must inspect a real directory-handle object, enumerate it, and read a file");
assert.match(smoke, /run_native_folder_acceptance\(\)/, "the Android 15 smoke test must exercise the custom folder browser");
assert.match(smoke, /run_main_activity_game_session_acceptance\(\)/, "Android 15 must exercise image-pack selection in the real MainActivity game session");
assert.match(smoke, /PASS_GAME_SESSION_DIRECTORY_HANDLE/, "the game session must verify the JavaScript directory handle and image read");
assert.match(smoke, /picker-frame\.html/, "the integration test must invoke the folder picker from a same-origin iframe");
assert.match(smoke, /ChooserActivity|ResolverActivity/, "Android 15 smoke must verify the diagnostic ZIP share sheet opens");
assert.match(smoke, /wait_for_cloudflare_diagnostic_upload\(\)/,
  "Android 15 smoke must verify the diagnostics button successfully uploads its ZIP to Cloudflare");
assert.match(smoke, /diagnostics_cloud_upload_succeeded/,
  "the Android 15 upload acceptance must be based on the uploader's durable success event");
assert.match(smoke, /wayward-diagnostics-/, "Android 15 smoke must verify a diagnostic ZIP was generated");
assert.match(smoke, /"launcher\.ndjson"/, "Android 15 smoke must verify the ZIP contains general launcher diagnostics");
assert.match(smoke, /ensure_previous_diagnostic_journals\(\)/,
  "Android 15 smoke must create or retain previous journals for ZIP inclusion checks");
assert.match(smoke, /shlex\.quote\(write_command\)/,
  "Android 15 diagnostic journal fixtures must preserve shell pipes/redirection through adb run-as sh -c");
assert.match(smoke, /diagnostics_dir = "\/data\/user\/0\/" \+ PACKAGE \+ "\/files\/diagnostics"/,
  "diagnostic fixtures must target the exact Android getFilesDir diagnostics folder");
assert.match(smoke, /"run-as", PACKAGE, "mkdir", "-p", diagnostics_dir/,
  "the fixture writer must create the app-scoped diagnostics directory with run-as");
assert.match(smoke, /"sh", "-c", shlex\.quote\(write_command\)/,
  "the fixture writer must quote the complete NDJSON seed shell command");
assert.match(smoke, /json\.loads\(line\)/,
  "Android 15 smoke must validate previous journal entries as NDJSON");
assert.match(smoke, /PASS_PACK_JSON_MULTI_FILES/, "the game session must regression-test multiple real pack.json selection");
assert.match(smoke, /PASS_ZIP_MULTI_FILES/, "the game session must regression-test ZIP multi-selection");

assert.match(smoke, /def _is_multiple_file_chooser_mode/,
  "acceptance must normalize Android's numeric MODE_OPEN_MULTIPLE value instead of assuming a string");
assert.match(smoke, /mode == 1/,
  "the chooser contract must recognize WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE");
assert.match(smoke, /def validate_file_chooser_diagnostics/,
  "the diagnostic ZIP must validate pack.json and ZIP requests independently");
assert.match(smoke, /matching_callbacks = \[\s*event for event in callbacks if event\.get\("requestId"\) == request_id\s*\]/,
  "each chooser callback must be correlated to its originating request ID");
assert.match(smoke, /android15-zip-multiselect-webview-callback\.xml/,
  "successful ZIP selection must preserve the actual WebView callback UI hierarchy");
assert.match(smoke, /imagepack-acceptance-logcat\.txt/,
  "acceptance failures must retain image-pack logcat evidence");
assert.match(smoke, /android15-main-activity-imagepack-picker\.xml/,
  "the MainActivity SAF picker UI must be captured under its stable diagnostic name");

assert.match(smoke, /dumpsys", "activity", "recents"/, "the Android 15 test must inspect the actual Recent Apps task list");
assert.equal((smoke.match(/"-f", "0x10200000"/g) || []).length, 2,
  "foreground and process restart must use actual launcher NEW_TASK|RESET_TASK_IF_NEEDED semantics");
assert.doesNotMatch(smoke, /run_documentsui_acceptance/i, "the Android 15 smoke test must also exercise an actual MainActivity game session");
assert.match(smoke, /__wayward_nav_child/, "Android 15 smoke must verify child and parent folder navigation");
assert.match(smoke, /require_clickable: bool = True/, "Android 15 smoke must support locating non-clickable ListView text rows");
assert.match(smoke, /require_clickable=False/, "folder navigation must use bounds for ListView text rows");
assert.match(readFileSync(new URL("../app/src/debug/java/com/wayward/launcher/ImagePackPickerAcceptanceActivity.java", import.meta.url), "utf8"), /verifyFilesystemOperations/, "Android 15 smoke must exercise filesystem CRUD");
assert.match(smoke, /symlink_fixture_created = symlink_result\.returncode == 0/, "emulator smoke must detect whether its storage provider supports symlink fixtures");
assert.match(readFileSync(new URL("../app/src/debug/java/com/wayward/launcher/ImagePackPickerAcceptanceActivity.java", import.meta.url), "utf8"), /verifySymlinkFixture/, "device symlink checks must only claim coverage when the fixture exists");

console.log("Native image-pack filesystem/SAF versioned bridge contract tests passed.");

const pairing = readFileSync(new URL("../app/src/main/java/com/wayward/launcher/RemotePairingActivity.java", import.meta.url), "utf8");
const build = readFileSync(new URL("../app/build.gradle", import.meta.url), "utf8");
assert.match(pairing, /scanner\.startScan\(\)/,
  "the existing camera QR scanner must remain the first pairing action");
assert.match(pairing, /addOnCanceledListener\(\(\) -> \{[\s\S]*?imageImportButton\.setVisibility\(View\.VISIBLE\)/,
  "the developer image import button must appear after the camera scanner is cancelled");
assert.match(pairing, /REQUEST_QR_IMAGE/,
  "the image import path must return its selected URI to the pairing activity");
assert.match(pairing, /Intent\.ACTION_OPEN_DOCUMENT[\s\S]*?setType\("image\/\*"/,
  "the temporary tool must use the system image picker without replacing camera scanning");
assert.match(pairing, /loadBoundedQrImage\(imageUri\)/,
  "the image QR reader must open the selected image URI through bounded decoding");
assert.match(pairing, /InputImage\.fromBitmap\(decoded\.bitmap, decoded\.rotationDegrees\)/,
  "the image QR reader must pass the memory-bounded bitmap and EXIF rotation to ML Kit");
assert.match(pairing, /calculateQrImageSampleSize/,
  "extreme QR images must be downsampled before allocation");
assert.match(pairing, /recycleQrBitmap\(bitmapForCallbacks\)/,
  "decoded QR bitmaps must be recycled on all asynchronous scanner completion paths");
assert.match(pairing, /Barcode\.FORMAT_QR_CODE/,
  "the image recognizer must only detect QR codes");
assert.match(pairing, /if \(count == 0\)/,
  "an image with no QR must produce its own result");
assert.match(pairing, /if \(count > 1\)/,
  "an image with multiple QR codes must be rejected explicitly");
assert.match(pairing, /handleQr\(barcode\)/,
  "exactly one recognized QR must reuse the original secure pairing validation path");
assert.match(pairing, /showImageImportFailure\([\s\S]*?finish\(\)/,
  "empty or multiple image QR results must return to the launcher after a message");
assert.doesNotMatch(pairing, /Log\.[idw]\(TAG,.*raw|Log\.[idw]\(TAG,.*imageUri/,
  "image QR payloads and selected image URIs must never be logged");
assert.match(build, /play-services-mlkit-barcode-scanning:18\.3\.1/,
  "the supported ML Kit image barcode API must be included without replacing the camera scanner");
