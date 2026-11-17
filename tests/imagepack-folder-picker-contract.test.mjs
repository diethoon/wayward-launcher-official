import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const main = readFileSync(new URL("../app/src/main/java/com/wayward/launcher/MainActivity.java", import.meta.url), "utf8");
const picker = readFileSync(new URL("../app/src/main/java/com/wayward/launcher/WebFileChooser.java", import.meta.url), "utf8");
const access = readFileSync(new URL("../app/src/main/java/com/wayward/launcher/ImagePackDirectoryAccess.java", import.meta.url), "utf8");
const bridge = readFileSync(new URL("../app/src/main/assets/imagepack-folder-picker.js", import.meta.url), "utf8");
const filesystem = readFileSync(new URL("../app/src/main/java/com/wayward/launcher/ImagePackFileSystemAccess.java", import.meta.url), "utf8");
const pathGuard = readFileSync(new URL("../app/src/main/java/com/wayward/launcher/ImagePackPathGuard.java", import.meta.url), "utf8");
const atomicWriter = readFileSync(new URL("../app/src/main/java/com/wayward/launcher/ImagePackAtomicFileWriter.java", import.meta.url), "utf8");
const manifest = readFileSync(new URL("../app/src/main/AndroidManifest.xml", import.meta.url), "utf8");
const smoke = readFileSync(new URL("../scripts/android15-emulator-smoke.py", import.meta.url), "utf8");
const helper = readFileSync(new URL("../app/src/main/java/com/wayward/launcher/ImagePackFolderPickerScript.java", import.meta.url), "utf8");
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
assert.match(picker, /MODE_OPEN_MULTIPLE/, "the actual pack.json input must preserve HTML multiple-selection mode");
assert.match(picker, /EXTRA_ALLOW_MULTIPLE/, "the native document picker must receive the multi-select flag");
assert.match(picker, /WebFileChooser\(Activity activity, String sessionId\)/, "file-input diagnostics must share the active game-session ID");
assert.match(main, /new WebFileChooser\(this,imagePackDiagnosticSessionId\)/, "the active session must flow into the ordinary file chooser");
assert.match(smoke, /Pick multiple pack\.json files/, "the real game multi-pack JSON file-input path must be exercised");
assert.match(smoke, /application\/json/, "the file-input acceptance test must use the MIME type selected by the actual game");
assert.match(picker, /CATEGORY_OPENABLE/, "ordinary file picker must remain OPENABLE");
assert.match(picker, /Intent\.EXTRA_ALLOW_MULTIPLE/, "ZIP multi-select must remain supported");
assert.match(picker, /getClipData\(\)/, "ZIP selections must preserve ClipData support");
assert.doesNotMatch(picker, /MODE_OPEN_FOLDER|ACTION_OPEN_DOCUMENT_TREE|pendingFolderSelection/, "folder selection must use the JS-native bridge rather than the WebView file callback");
assert.match(picker, /Intent\.ACTION_OPEN_DOCUMENT/, "ordinary files and ZIPs must retain the SAF document picker");

assert.match(bridge, /window\.showDirectoryPicker\s*=/, "the Image Studio directory API must be intercepted at document start");
assert.match(bridge, /host\.chooseImagePackFolder\(id\)/, "the folder action must call the native launcher bridge directly");
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
assert.match(access, /Build\.VERSION\.SDK_INT >= Build\.VERSION_CODES\.R/, "Android 11+ must use the filesystem backend");
assert.match(access, /new ImagePackFileSystemAccess\(activity, webView, safeProfile, diagnosticSessionId\)/, "filesystem access must remain scoped to the active user profile and diagnostic session");
assert.match(access, /Intent\.ACTION_OPEN_DOCUMENT_TREE/, "Android 10 must keep the existing SAF tree picker");
assert.match(access, /takePersistableUriPermission\(selected, grants\)/, "Android 10 must retain persisted SAF permissions");
assert.match(access, /DEFAULT_INITIAL_DOCUMENT_ID = "primary:Download"/, "the Android 10 SAF picker must start in Download");

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
assert.match(manifest, /android\.permission\.MANAGE_EXTERNAL_STORAGE/, "the app must declare all-files access");
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
assert.match(uploader, /DiagnosticsLogger\.acknowledge\(app, ids\)/,
  "locally retained events must only be acknowledged after successful upload");
assert.match(main, /DiagnosticsUploader\.uploadPending\(this\)/,
  "unacknowledged diagnostic events must retry when the launcher resumes");
assert.match(exporter, /lastFailureStage/, "the exported metadata must report the last failure stage");
assert.match(filesystem, /Environment\.isExternalStorageManager\(\)/, "the filesystem backend must verify all-files access");
assert.match(filesystem, /Settings\.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION/, "missing permission must open the app-specific all-files settings");
assert.match(filesystem, /Settings\.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION/, "the global all-files settings must be a fallback");
assert.match(filesystem, /void onResume\(\)/, "permission must be rechecked after returning from settings");
assert.match(filesystem, /new File\(storageRoot, "Download"\)/, "first navigation must start at Download");
assert.match(filesystem, /initial = isUsableDirectory\(download\) \? canonicalOrNull\(download\) : storageRoot/, "an unavailable Download folder must fall back to internal storage root");
assert.match(filesystem, /"이 폴더 선택"/, "the custom browser must provide the current-folder selection action");
assert.match(filesystem, /"새 폴더"/, "the custom browser must provide folder creation");
assert.match(filesystem, /"↑ 상위 폴더"/, "the custom browser must support parent navigation");
assert.match(filesystem, /image_pack_folder_path_/, "last selected path must be stored per profile");
assert.match(filesystem, /Files\.newInputStream\(file\.toPath\(\)/, "image files must be opened through the filesystem");
assert.match(filesystem, /LinkOption\.NOFOLLOW_LINKS/, "image reads must not follow a swapped-in symbolic link");
assert.match(filesystem, /ImagePackAtomicFileWriter\.write\(guard, path, bytes\)/, "file writes must use temporary-file atomic replacement");
assert.match(atomicWriter, /File\.createTempFile\("\.wayward-imagepack-"/, "writes must use a sibling temporary file");
assert.match(atomicWriter, /output\.getFD\(\)\.sync\(\)/, "temporary file contents must be synchronized before replacement");
assert.match(atomicWriter, /StandardCopyOption\.ATOMIC_MOVE/, "completed files must be atomically moved over the target");
assert.match(pathGuard, /selectedRootPath/, "the chosen folder must define the filesystem boundary");
assert.match(pathGuard, /candidate\.startsWith\(selectedRootPath\)/, "canonical paths must remain beneath the selected folder");
assert.match(pathGuard, /getCanonicalFile\(\)/, "path checks must resolve canonical paths");
assert.match(pathGuard, /Files\.isSymbolicLink/, "symbolic links must be rejected from traversal and listing");
assert.match(filesystem, /new ImagePackPathGuard\(selectedRoot\)/, "filesystem methods must use the selected-root guard");
assert.match(filesystem, /target\.mkdir\(\)/, "folders must be created through the normal filesystem");
assert.match(filesystem, /target\.delete\(\)/, "entries must be deleted through the normal filesystem");
assert.doesNotMatch(filesystem, /Wayward_Faith|image-packs/, "no fixed image-pack folder name may be forced");
assert.doesNotMatch(filesystem, /ACTION_OPEN_DOCUMENT_TREE/, "Android 11+ custom browsing must not invoke the system SAF picker");

assert.match(main, /new ImagePackDirectoryAccess\(this,web,activeProfileId,imagePackDiagnosticSessionId\)/, "the production game WebView must own the request-correlated native image-pack bridge");
assert.match(main, /recordImagePackDiagnostic\(String requestId/, "JavaScript diagnostics must enter the durable journal through WMLHost");
assert.match(main, /diagnostics=compactButton\("진단"/, "the launcher home screen must expose the diagnostics share action");
assert.match(main, /DiagnosticsBundleExporter\.share\(this\)/, "the diagnostics button must open the Android share flow");
assert.match(access, /__waywardNativeImagePackPickerDispatch/, "native completion must resolve the original frame instead of main-frame-only delivery");
assert.match(main, /imagePackDirectoryAccess\.onResume\(\)/, "the production activity must recheck all-files permission on resume");
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
const gameSessionAlias = manifest.split("<activity-alias")[1]?.split("/>")[0] || "";
assert.match(gameSessionAlias, /android:name="\.GameSessionActivity"/,
  "the game-session alias must have its own manifest component name");
assert.match(gameSessionAlias, /android:targetActivity="\.MainActivity"/,
  "the game-session alias must reuse the existing MainActivity implementation");
assert.ok(gameSessionAlias.includes('android:taskAffinity="${applicationId}.games"'),
  "game document tasks must use a dedicated affinity separate from the app-icon launcher task");
assert.match(main, /setClassName\(this,GAME_SESSION_ACTIVITY_ALIAS\)/,
  "game sessions must launch through the dedicated alias component");

assert.match(main, /else if\(sessionProfileId\(getIntent\(\)\)!=null\)\{[\s\S]*?finish\(\);/, "root BACK in a game session must finish only the session Activity");
assert.match(main, /if\(web\.canGoBack\(\)\)\{\s*web\.goBack\(\);/, "BACK inside WebView history must stay inside the game");
assert.match(main, /ImagePackFolderPickerScript\.installDocumentStart\(\s*web,\s*imagePackFolderPickerScript,\s*origin\)/, "the directory adapter must register at document start when supported");
assert.match(main, /ImagePackFolderPickerScript\.installAfterPageFinished\(/, "the directory adapter must have a runtime fallback");
assert.match(readFileSync(new URL("../app/src/main/java/com/wayward/launcher/ImagePackFolderPickerScript.java", import.meta.url), "utf8"), /isSameOrigin\(/, "runtime fallback must be restricted to the active game origin");
assert.match(readFileSync(new URL("../app/src/main/java/com/wayward/launcher/ImagePackFolderPickerScript.java", import.meta.url), "utf8"), /WebViewCompat\.addDocumentStartJavaScript/, "supported document-start injection must remain");
assert.match(main, /installDocumentStartAsset\("imagegen-proxy\.js", origin\)/, "image-generation scripts must remain installed separately");
assert.match(main, /chooseImagePackFolder\(String requestId\)/, "WMLHost must preserve the existing folder action");
assert.match(main, /listImagePackDirectory\(String path\)/, "WMLHost must preserve the existing directory-list method");
assert.match(main, /readImagePackFile\(String path\)/, "WMLHost must preserve the existing file-read method");
assert.match(readFileSync(new URL("../app/src/debug/java/com/wayward/launcher/ImagePackPickerAcceptanceActivity.java", import.meta.url), "utf8"), /PASS_FS_DIRECTORY_HANDLE/, "acceptance must inspect a real directory-handle object, enumerate it, and read a file");
assert.match(smoke, /appops", "set", "--uid", PACKAGE, "MANAGE_EXTERNAL_STORAGE", "allow"/, "the Android 15 emulator acceptance test must grant all-files access");
assert.match(smoke, /run_native_folder_acceptance\(\)/, "the Android 15 smoke test must exercise the custom folder browser");
assert.match(smoke, /run_main_activity_game_session_acceptance\(\)/, "Android 15 must exercise image-pack selection in the real MainActivity game session");
assert.match(smoke, /PASS_GAME_SESSION_DIRECTORY_HANDLE/, "the game session must verify the JavaScript directory handle and image read");
assert.match(smoke, /picker-frame\.html/, "the integration test must invoke the folder picker from a same-origin iframe");
assert.match(smoke, /ChooserActivity|ResolverActivity/, "Android 15 smoke must verify the diagnostic ZIP share sheet opens");
assert.match(smoke, /wayward-diagnostics-/, "Android 15 smoke must verify a diagnostic ZIP was generated");
assert.match(smoke, /"launcher\.ndjson"/, "Android 15 smoke must verify the ZIP contains general launcher diagnostics");
assert.match(smoke, /ensure_previous_diagnostic_journals\(\)/,
  "Android 15 smoke must create or retain previous journals for ZIP inclusion checks");
assert.match(smoke, /json\.loads\(line\)/,
  "Android 15 smoke must validate previous journal entries as NDJSON");
assert.match(smoke, /PASS_PACK_JSON_MULTI_FILES/, "the game session must regression-test multiple real pack.json selection");
assert.match(smoke, /PASS_ZIP_MULTI_FILES/, "the game session must regression-test ZIP multi-selection");
assert.match(smoke, /dumpsys", "activity", "recents"/, "the Android 15 test must inspect the actual Recent Apps task list");
assert.equal((smoke.match(/"-f", "0x10200000"/g) || []).length, 2,
  "foreground and process restart must use actual launcher NEW_TASK|RESET_TASK_IF_NEEDED semantics");
assert.doesNotMatch(smoke, /run_documentsui_acceptance/i, "the Android 15 smoke test must also exercise an actual MainActivity game session");
assert.match(readFileSync(new URL("../tests/ImagePackPathGuardTest.java", import.meta.url), "utf8"), /linked-documents/, "path tests must cover symbolic-link escape");
assert.match(readFileSync(new URL("../tests/ImagePackAtomicFileWriterTest.java", import.meta.url), "utf8"), /simulated short write/, "writer tests must verify preservation after an incomplete write");
assert.match(smoke, /MANAGE_EXTERNAL_STORAGE", "deny"/, "Android 15 smoke must verify permission revocation");
assert.match(smoke, /__wayward_nav_child/, "Android 15 smoke must verify child and parent folder navigation");
assert.match(smoke, /require_clickable: bool = True/, "Android 15 smoke must support locating non-clickable ListView text rows");
assert.match(smoke, /require_clickable=False/, "folder navigation must use bounds for ListView text rows");
assert.match(readFileSync(new URL("../app/src/debug/java/com/wayward/launcher/ImagePackPickerAcceptanceActivity.java", import.meta.url), "utf8"), /verifyFilesystemOperations/, "Android 15 smoke must exercise filesystem CRUD");
assert.match(smoke, /symlink_fixture_created = symlink_result\.returncode == 0/, "emulator smoke must detect whether its storage provider supports symlink fixtures");
assert.match(readFileSync(new URL("../app/src/debug/java/com/wayward/launcher/ImagePackPickerAcceptanceActivity.java", import.meta.url), "utf8"), /verifySymlinkFixture/, "device symlink checks must only claim coverage when the fixture exists");

console.log("Native image-pack filesystem/SAF versioned bridge contract tests passed.");
