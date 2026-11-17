package com.wayward.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;
import android.webkit.WebView;
import android.widget.EditText;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.io.IOException;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Android 11+ image-pack access through normal shared-storage file APIs. */
final class ImagePackFileSystemAccess {
    private static final String PREFS = "wayward_launcher";
    private static final String TAG = "WaywardImagePackFS";
    private static final int MAX_FILE_BYTES = 32 * 1024 * 1024;

    private final Activity activity;
    private final WebView webView;
    private final String preferenceKey;
    private final String diagnosticSessionId;
    private final File storageRoot;
    private String pendingRequestId;
    private boolean waitingForSettings;
    private File selectedRoot;

    ImagePackFileSystemAccess(Activity activity, WebView webView, String profileId, String sessionId) {
        this.activity = activity;
        this.webView = webView;
        diagnosticSessionId = sessionId == null ? "" : sessionId;
        String safeProfile = profileId == null || !profileId.matches("[A-Za-z0-9_-]{1,80}")
                ? "default" : profileId;
        preferenceKey = "image_pack_folder_path_" + safeProfile;
        File root = Environment.getExternalStorageDirectory();
        File canonical;
        try {
            canonical = root.getCanonicalFile();
        } catch (IOException error) {
            canonical = root.getAbsoluteFile();
        }
        storageRoot = canonical;
        restoreSelectedRoot();
    }

    void openFolderPicker(String requestId) {
        if (requestId == null || !requestId.matches("[A-Za-z0-9._:-]{1,128}")) {
            diagnostic("native_folder_picker_request_rejected", "", "error", details("result", "invalid_request_id"));
            return;
        }
        activity.runOnUiThread(() -> {
            if (pendingRequestId != null) {
                if (pendingRequestId.equals(requestId)) {
                    diagnostic("native_folder_picker_duplicate_request_id_ignored", requestId,
                            "warn", details("pendingRequest", true, "result", "duplicate_request_id"));
                    return;
                }
                diagnostic("native_folder_picker_request_rejected_busy", requestId,
                        "warn", details("pendingRequest", true, "result", "invalid_state"));
                deliver(requestId, failure("InvalidStateError",
                        "Another image-pack folder selection is already in progress."));
                return;
            }
            pendingRequestId = requestId;
            boolean permission = hasAllFilesAccess();
            diagnostic("native_folder_picker_entered", requestId, "info",
                    details("apiLevel", Build.VERSION.SDK_INT, "permissionGranted", permission,
                            "requestSource", "showDirectoryPicker"));
            if (permission) {
                diagnostic("native_all_files_permission_already_granted", requestId, "info",
                        details("permissionGranted", true));
                beginFolderBrowsing();
            } else {
                diagnostic("native_all_files_permission_missing", requestId, "warn",
                        details("permissionGranted", false));
                showPermissionPrompt(false);
            }
        });
    }

    void onResume() {
        if (!waitingForSettings || pendingRequestId == null) return;
        waitingForSettings = false;
        boolean granted = hasAllFilesAccess();
        diagnostic("native_permission_settings_returned", pendingRequestId,
                granted ? "info" : "warn", details("permissionGranted", granted));
        activity.runOnUiThread(() -> {
            if (granted) beginFolderBrowsing();
            else showPermissionPrompt(true);
        });
    }

    void cancelPending() {
        if (pendingRequestId != null) {
            diagnostic("native_folder_picker_cancelled_on_lifecycle", pendingRequestId, "warn",
                    details("cancelled", true, "result", "webview_or_activity_closed"));
            finishPending(failure("AbortError", "The page was closed."));
        }
    }

    private boolean hasAllFilesAccess() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager();
    }

    private void showPermissionPrompt(boolean returningFromSettings) {
        if (pendingRequestId == null || activity.isFinishing()) return;
        diagnostic("native_permission_prompt_shown", pendingRequestId, "info",
                details("permissionGranted", false, "permissionAction",
                        returningFromSettings ? "settings_return_denied" : "settings_required"));
        new AlertDialog.Builder(activity)
                .setTitle("전체 파일 접근 권한 필요")
                .setMessage(returningFromSettings
                        ? "권한이 아직 허용되지 않았습니다. 전체 파일 접근 설정에서 Wayward Launcher를 허용해 주세요."
                        : "이미지팩 폴더를 직접 탐색하고 파일을 관리하려면 전체 파일 접근 권한이 필요합니다.")
                .setPositiveButton(returningFromSettings ? "설정 다시 열기" : "설정 열기",
                        (dialog, which) -> openAllFilesSettings())
                .setNegativeButton("취소", (dialog, which) -> {
                    diagnostic("native_permission_prompt_cancelled", pendingRequestId, "warn",
                            details("cancelled", true, "permissionGranted", false));
                    finishPending(failure("NotAllowedError", "전체 파일 접근 권한이 허용되지 않았습니다."));
                })
                .show();
    }

    private void openAllFilesSettings() {
        waitingForSettings = true;
        diagnostic("native_permission_settings_launch_started", pendingRequestId, "info",
                details("permissionAction", Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION));
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + activity.getPackageName()));
            activity.startActivity(intent);
            diagnostic("native_permission_settings_launch_requested", pendingRequestId, "info",
                    details("permissionAction", Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION));
        } catch (Exception appSpecificUnavailable) {
            diagnosticError("native_permission_app_settings_launch_failed", pendingRequestId, appSpecificUnavailable);
            try {
                activity.startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                diagnostic("native_permission_global_settings_launch_requested", pendingRequestId, "info",
                        details("permissionAction", Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            } catch (Exception error) {
                waitingForSettings = false;
                diagnosticError("native_permission_settings_launch_failed", pendingRequestId, error);
                finishPending(failure("NotAllowedError", "전체 파일 접근 설정 화면을 열 수 없습니다."));
            }
        }
    }

    private void beginFolderBrowsing() {
        if (!hasAllFilesAccess()) {
            showPermissionPrompt(false);
            return;
        }
        restoreSelectedRoot();
        File initial = selectedRoot;
        if (!isUsableDirectory(initial)) {
            File download = new File(storageRoot, "Download");
            initial = isUsableDirectory(download) ? canonicalOrNull(download) : storageRoot;
        }
        if (!isUsableDirectory(initial)) {
            finishPending(failure("NotFoundError", "내부 저장소에 접근할 수 없습니다."));
            return;
        }
        diagnostic("native_folder_browser_opened", pendingRequestId, "info",
                details("permissionGranted", true, "result", selectedRoot == null ? "default_download" : "saved_folder"));
        showDirectoryDialog(canonicalOrNull(initial));
    }

    private void showDirectoryDialog(File directory) {
        if (pendingRequestId == null) return;
        File current = canonicalOrNull(directory);
        if (!isUsableDirectory(current)) {
            finishPending(failure("NotFoundError", "선택한 폴더에 접근할 수 없습니다."));
            return;
        }

        diagnostic("native_folder_browser_displayed", pendingRequestId, "info",
                details("permissionGranted", hasAllFilesAccess(), "result", "dialog_visible"));
        List<String> labels = new ArrayList<>();
        List<File> destinations = new ArrayList<>();
        File parent = current.getParentFile();
        if (parent != null && isWithinStorage(parent) && !parent.equals(current)) {
            labels.add("↑ 상위 폴더");
            destinations.add(parent);
        }

        File[] children = current.listFiles();
        if (children != null) {
            Arrays.sort(children, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
            for (File child : children) {
                if (child.isDirectory() && isUsableDirectory(child)) {
                    labels.add("📁 " + child.getName());
                    destinations.add(child);
                }
            }
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(current.getAbsolutePath())
                .setPositiveButton("이 폴더 선택", (dialog, which) -> selectDirectory(current))
                .setNeutralButton("새 폴더", (dialog, which) -> showCreateDirectoryDialog(current))
                .setNegativeButton("취소", (dialog, which) -> {
                    diagnostic("native_folder_browser_cancelled", pendingRequestId, "warn",
                            details("cancelled", true));
                    finishPending(failure("AbortError", "Folder selection was cancelled."));
                });

        if (labels.isEmpty()) {
            builder.setMessage("하위 폴더가 없습니다.");
        } else {
            builder.setItems(labels.toArray(new String[0]), (dialog, which) -> {
                if (which >= 0 && which < destinations.size()) showDirectoryDialog(destinations.get(which));
            });
        }
        builder.show();
    }

    private void showCreateDirectoryDialog(File parent) {
        EditText input = new EditText(activity);
        input.setSingleLine(true);
        input.setHint("폴더 이름");
        new AlertDialog.Builder(activity)
                .setTitle("새 폴더 만들기")
                .setView(input)
                .setPositiveButton("만들기", (dialog, which) -> {
                    try {
                        String name = validateName(input.getText() == null ? "" : input.getText().toString().trim());
                        File target = new File(parent, name);
                        if (!isWithinStorage(target) || !target.mkdir()) {
                            throw new IOException("mkdir failed");
                        }
                        showDirectoryDialog(parent);
                    } catch (Exception error) {
                        new AlertDialog.Builder(activity)
                                .setMessage("폴더를 만들 수 없습니다. 이름이나 저장소 권한을 확인해 주세요.")
                                .setPositiveButton("확인", (ignored, ignoredWhich) -> showDirectoryDialog(parent))
                                .show();
                    }
                })
                .setNegativeButton("취소", (dialog, which) -> showDirectoryDialog(parent))
                .show();
    }

    private void selectDirectory(File directory) {
        File selected = canonicalOrNull(directory);
        if (!isUsableDirectory(selected)) {
            finishPending(failure("NotFoundError", "선택한 폴더에 접근할 수 없습니다."));
            return;
        }
        boolean saved = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
                .edit().putString(preferenceKey, selected.getAbsolutePath()).commit();
        if (!saved) {
            finishPending(failure("NotAllowedError", "선택한 폴더 경로를 저장할 수 없습니다."));
            return;
        }
        selectedRoot = selected;
        diagnostic("native_folder_selection_saved", pendingRequestId, "info",
                details("permissionGranted", hasAllFilesAccess(), "writable", selected.canWrite() && hasAllFilesAccess()));
        JSONObject result = new JSONObject();
        try {
            result.put("ok", true);
            result.put("name", selected.equals(storageRoot) ? "내부 저장소" : selected.getName());
            result.put("writable", selected.canWrite() && hasAllFilesAccess());
        } catch (Exception ignored) {}
        Log.i(TAG, "folder_select result=success writable=" + (selected.canWrite() && hasAllFilesAccess()));
        finishPending(result);
    }

    String getFolderInfoJson() {
        try {
            ImagePackPathGuard guard = requirePathGuard();
            File root = guard.rootDirectory();
            JSONObject result = new JSONObject();
            result.put("ok", true);
            result.put("name", root.equals(storageRoot) ? "내부 저장소" : root.getName());
            result.put("writable", root.canWrite());
            return result.toString();
        } catch (Exception error) {
            return failure(error instanceof SecurityException ? "SecurityError" : "NotFoundError",
                    "No saved image-pack folder is available.").toString();
        }
    }

    String listDirectoryJson(String path) {
        return listDirectoryJson(path, "");
    }

    String listDirectoryJson(String path, String requestId) {
        diagnostic("native_directory_list_started", requestId, "info", details("method", "listImagePackDirectory"));
        try {
            ImagePackPathGuard guard = requirePathGuard();
            File directory = guard.resolveDirectory(path);
            File[] children = directory.listFiles();
            if (children == null) throw new IOException("Directory listing is unavailable.");
            Arrays.sort(children, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
            JSONArray entries = new JSONArray();
            for (File child : children) {
                if (!guard.isSafeListedEntry(child)) continue;
                JSONObject item = new JSONObject();
                item.put("name", child.getName());
                item.put("kind", child.isDirectory() ? "directory" : "file");
                item.put("size", child.isFile() ? child.length() : -1L);
                item.put("mimeType", child.isFile() ? mimeType(child) : "vnd.android.document/directory");
                item.put("lastModified", child.lastModified());
                entries.put(item);
            }
            JSONObject result = new JSONObject();
            result.put("ok", true);
            result.put("entries", entries);
            diagnostic("native_directory_list_succeeded", requestId, "info", details("entryCount", entries.length()));
            return result.toString();
        } catch (Exception error) {
            diagnosticError("native_directory_list_failed", requestId, error);
            return failure(error instanceof SecurityException ? "SecurityError" : "NotFoundError",
                    "The selected image-pack directory could not be listed.").toString();
        }
    }

    String readFileJson(String path) {
        return readFileJson(path, "");
    }

    String readFileJson(String path, String requestId) {
        diagnostic("native_file_read_started", requestId, "info", details("method", "readImagePackFile"));
        try {
            File file = requirePathGuard().resolveFile(path);
            if (file.length() > MAX_FILE_BYTES) {
                return failure("QuotaExceededError", "The image file exceeds the 32 MiB bridge limit.").toString();
            }
            try (InputStream input = Files.newInputStream(file.toPath(),
                    StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[16 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > MAX_FILE_BYTES) {
                        return failure("QuotaExceededError", "The image file exceeds the 32 MiB bridge limit.").toString();
                    }
                    output.write(buffer, 0, count);
                }
                JSONObject result = new JSONObject();
                result.put("ok", true);
                result.put("name", file.getName());
                result.put("mimeType", mimeType(file));
                result.put("lastModified", file.lastModified());
                result.put("size", output.size());
                result.put("data", Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP));
                diagnostic("native_file_read_succeeded", requestId, "info", details("fileSize", output.size()));
                return result.toString();
            }
        } catch (Exception error) {
            diagnosticError("native_file_read_failed", requestId, error);
            return failure(error instanceof SecurityException ? "SecurityError" : "NotFoundError",
                    "The selected image-pack file could not be read.").toString();
        }
    }

    String writeFileJson(String path, String mimeType, String encodedBytes) {
        try {
            ImagePackPathGuard guard = requirePathGuard();
            requireWriteAccess(guard);
            byte[] bytes = Base64.decode(encodedBytes == null ? "" : encodedBytes, Base64.DEFAULT);
            if (bytes.length > MAX_FILE_BYTES) {
                return failure("QuotaExceededError", "The image file exceeds the 32 MiB bridge limit.").toString();
            }
            File target = guard.resolveTarget(path);
            if (target.exists() && !target.isFile()) {
                throw new IllegalArgumentException("A directory already uses this name.");
            }
            ImagePackAtomicFileWriter.write(guard, path, bytes);
            JSONObject result = new JSONObject();
            result.put("ok", true);
            result.put("name", target.getName());
            return result.toString();
        } catch (Exception error) {
            return failure(error instanceof SecurityException ? "SecurityError" : "NotAllowedError",
                    "The image-pack file could not be written.").toString();
        }
    }

    String createDirectoryJson(String path) {
        try {
            ImagePackPathGuard guard = requirePathGuard();
            requireWriteAccess(guard);
            File target = guard.resolveTarget(path);
            if (target.exists()) {
                if (!target.isDirectory()) throw new IllegalArgumentException("A file already uses this name.");
            } else if (!target.mkdir()) {
                throw new IOException("Directory creation failed.");
            }
            JSONObject result = new JSONObject();
            result.put("ok", true);
            return result.toString();
        } catch (Exception error) {
            return failure(error instanceof SecurityException ? "SecurityError" : "NotAllowedError",
                    "The image-pack directory could not be created.").toString();
        }
    }

    String deleteEntryJson(String path) {
        try {
            ImagePackPathGuard guard = requirePathGuard();
            requireWriteAccess(guard);
            File target = guard.resolveTarget(path);
            if (!target.exists() || !target.delete()) {
                throw new IOException("Entry deletion failed.");
            }
            JSONObject result = new JSONObject();
            result.put("ok", true);
            return result.toString();
        } catch (Exception error) {
            return failure(error instanceof SecurityException ? "SecurityError" : "NotAllowedError",
                    "The image-pack entry could not be removed.").toString();
        }
    }

    private void restoreSelectedRoot() {
        SharedPreferences preferences = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
        String saved = preferences.getString(preferenceKey, "");
        selectedRoot = null;
        if (saved == null || saved.isEmpty()) return;
        File savedPath = new File(saved).getAbsoluteFile();
        File candidate = canonicalOrNull(savedPath);
        boolean pathWasCanonical = candidate != null
                && savedPath.toPath().normalize().equals(candidate.toPath());
        boolean pathAllowed = pathWasCanonical && isWithinStorage(candidate);
        if (pathAllowed && (!hasAllFilesAccess() || isUsableDirectory(candidate))) {
            selectedRoot = candidate;
        } else {
            preferences.edit().remove(preferenceKey).apply();
        }
    }

    private ImagePackPathGuard requirePathGuard() throws IOException {
        if (!hasAllFilesAccess()) throw new SecurityException("MANAGE_EXTERNAL_STORAGE is not granted.");
        if (selectedRoot == null) throw new IllegalStateException("No selected image-pack folder.");
        ImagePackPathGuard guard = new ImagePackPathGuard(selectedRoot);
        File root = guard.rootDirectory();
        if (!root.canRead() || root.list() == null) {
            throw new SecurityException("The selected image-pack folder is not readable.");
        }
        return guard;
    }

    private void requireWriteAccess(ImagePackPathGuard guard) throws IOException {
        if (!guard.rootDirectory().canWrite()) {
            throw new SecurityException("The selected image-pack folder is read-only.");
        }
    }

    private boolean isUsableDirectory(File directory) {
        if (directory == null || !directory.isDirectory() || !directory.canRead() || !isWithinStorage(directory)) {
            return false;
        }
        return directory.list() != null;
    }

    private boolean isWithinStorage(File candidate) {
        try {
            String root = storageRoot.getCanonicalPath();
            String path = candidate.getCanonicalPath();
            return path.equals(root) || path.startsWith(root + File.separator);
        } catch (IOException error) {
            return false;
        }
    }

    private File canonicalOrNull(File file) {
        if (file == null) return null;
        try {
            return file.getCanonicalFile();
        } catch (IOException error) {
            return null;
        }
    }

    private static String mimeType(File file) {
        String mime = URLConnection.guessContentTypeFromName(file.getName());
        return mime == null ? "application/octet-stream" : mime;
    }

    private static String[] splitPath(String path) {
        if (path == null || path.length() > 4096 || path.startsWith("/")
                || path.indexOf('\\') >= 0 || path.indexOf(0) >= 0) {
            throw new IllegalArgumentException("Invalid relative image-pack path.");
        }
        if (path.isEmpty()) return new String[0];
        String[] parts = path.split("/", -1);
        for (String part : parts) {
            if (part.isEmpty() || ".".equals(part) || "..".equals(part)) {
                throw new IllegalArgumentException("Invalid relative image-pack path.");
            }
        }
        return parts;
    }

    private static String validateName(String value) {
        if (value == null || value.isEmpty() || value.length() > 255
                || ".".equals(value) || "..".equals(value)
                || value.indexOf('/') >= 0 || value.indexOf('\\') >= 0
                || value.indexOf(0) >= 0) {
            throw new IllegalArgumentException("Invalid file or directory name.");
        }
        return value;
    }

    private static String join(String[] parts, int count) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) result.append('/');
            result.append(parts[i]);
        }
        return result.toString();
    }

    private void finishPending(JSONObject result) {
        waitingForSettings = false;
        String requestId = pendingRequestId;
        pendingRequestId = null;
        if (requestId != null) {
            diagnostic("native_folder_picker_result_ready", requestId,
                    result.optBoolean("ok") ? "info" : "warn",
                    details("ok", result.optBoolean("ok"), "errorName", result.optBoolean("ok") ? "" : result.optString("name", "")));
            deliver(requestId, result);
        }
    }

    private void deliver(String requestId, JSONObject result) {
        String script = "(function(){"
                + "if(typeof window.__waywardNativeImagePackPickerDispatch==='function')"
                + "return window.__waywardNativeImagePackPickerDispatch("
                + JSONObject.quote(requestId) + "," + result.toString() + ");"
                + "if(typeof window.__waywardNativeImagePackPickerResolve==='function')"
                + "return window.__waywardNativeImagePackPickerResolve("
                + JSONObject.quote(requestId) + "," + result.toString() + ")?1:0;"
                + "return 0;})()";
        activity.runOnUiThread(() -> {
            if (webView == null || activity.isFinishing()) {
                diagnostic("native_js_result_delivery_skipped", requestId, "error",
                        details("webViewAlive", webView != null));
                return;
            }
            webView.evaluateJavascript(script, value -> {
                int delivered = 0;
                try { delivered = value == null ? 0 : Integer.parseInt(value.trim()); }
                catch (Exception ignored) {}
                diagnostic(delivered > 0 ? "native_js_result_dispatched" : "native_js_result_no_pending_frame",
                        requestId, delivered > 0 ? "info" : "error",
                        details("dispatchCount", delivered, "webViewAlive", true));
            });
        });
    }

    private void diagnostic(String stage, String requestId, String status, JSONObject details) {
        ImagePackDiagnostics.event(activity, diagnosticSessionId, requestId, stage, status, details);
    }

    private void diagnosticError(String stage, String requestId, Throwable error) {
        ImagePackDiagnostics.error(activity, diagnosticSessionId, requestId, stage, error);
    }

    private static JSONObject details(Object... values) {
        JSONObject result = new JSONObject();
        for (int i = 0; i + 1 < values.length; i += 2) {
            try { result.put(String.valueOf(values[i]), values[i + 1]); } catch (Exception ignored) {}
        }
        return result;
    }

    private static JSONObject failure(String name, String message) {
        JSONObject result = new JSONObject();
        try {
            result.put("ok", false);
            result.put("name", name);
            result.put("message", message);
        } catch (Exception ignored) {}
        return result;
    }
}
