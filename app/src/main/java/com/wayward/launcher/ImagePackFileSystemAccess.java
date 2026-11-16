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
import java.io.FileInputStream;
import java.io.FileOutputStream;
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
    private final File storageRoot;
    private String pendingRequestId;
    private boolean waitingForSettings;
    private File selectedRoot;

    ImagePackFileSystemAccess(Activity activity, WebView webView, String profileId) {
        this.activity = activity;
        this.webView = webView;
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
        if (requestId == null || !requestId.matches("[A-Za-z0-9_-]{1,80}")) return;
        activity.runOnUiThread(() -> {
            if (pendingRequestId != null) {
                finishPending(failure("AbortError", "A previous folder request was replaced."));
            }
            pendingRequestId = requestId;
            if (hasAllFilesAccess()) beginFolderBrowsing();
            else showPermissionPrompt(false);
        });
    }

    void onResume() {
        if (!waitingForSettings || pendingRequestId == null) return;
        waitingForSettings = false;
        activity.runOnUiThread(() -> {
            if (hasAllFilesAccess()) beginFolderBrowsing();
            else showPermissionPrompt(true);
        });
    }

    void cancelPending() {
        if (pendingRequestId != null) {
            finishPending(failure("AbortError", "The page was closed."));
        }
    }

    private boolean hasAllFilesAccess() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager();
    }

    private void showPermissionPrompt(boolean returningFromSettings) {
        if (pendingRequestId == null || activity.isFinishing()) return;
        new AlertDialog.Builder(activity)
                .setTitle("전체 파일 접근 권한 필요")
                .setMessage(returningFromSettings
                        ? "권한이 아직 허용되지 않았습니다. 전체 파일 접근 설정에서 Wayward Launcher를 허용해 주세요."
                        : "이미지팩 폴더를 직접 탐색하고 파일을 관리하려면 전체 파일 접근 권한이 필요합니다.")
                .setPositiveButton(returningFromSettings ? "설정 다시 열기" : "설정 열기",
                        (dialog, which) -> openAllFilesSettings())
                .setNegativeButton("취소", (dialog, which) ->
                        finishPending(failure("NotAllowedError", "전체 파일 접근 권한이 허용되지 않았습니다.")))
                .show();
    }

    private void openAllFilesSettings() {
        waitingForSettings = true;
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + activity.getPackageName()));
            activity.startActivity(intent);
        } catch (Exception appSpecificUnavailable) {
            try {
                activity.startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            } catch (Exception error) {
                waitingForSettings = false;
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
        showDirectoryDialog(canonicalOrNull(initial));
    }

    private void showDirectoryDialog(File directory) {
        if (pendingRequestId == null) return;
        File current = canonicalOrNull(directory);
        if (!isUsableDirectory(current)) {
            finishPending(failure("NotFoundError", "선택한 폴더에 접근할 수 없습니다."));
            return;
        }

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
                .setNegativeButton("취소", (dialog, which) ->
                        finishPending(failure("AbortError", "Folder selection was cancelled.")));

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
            File root = requireSelectedRoot();
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
        try {
            File directory = resolveDirectory(path);
            File[] children = directory.listFiles();
            if (children == null) throw new IOException("Directory listing is unavailable.");
            Arrays.sort(children, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
            JSONArray entries = new JSONArray();
            for (File child : children) {
                if (!isWithinStorage(child) || (!child.isDirectory() && !child.isFile())) continue;
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
            return result.toString();
        } catch (Exception error) {
            return failure(error instanceof SecurityException ? "SecurityError" : "NotFoundError",
                    "The selected image-pack directory could not be listed.").toString();
        }
    }

    String readFileJson(String path) {
        try {
            File file = resolveFile(path);
            if (file.length() > MAX_FILE_BYTES) {
                return failure("QuotaExceededError", "The image file exceeds the 32 MiB bridge limit.").toString();
            }
            try (FileInputStream input = new FileInputStream(file);
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
                return result.toString();
            }
        } catch (Exception error) {
            return failure(error instanceof SecurityException ? "SecurityError" : "NotFoundError",
                    "The selected image-pack file could not be read.").toString();
        }
    }

    String writeFileJson(String path, String mimeType, String encodedBytes) {
        try {
            requireWriteAccess();
            File target = resolveTarget(path);
            if (target.exists() && !target.isFile()) {
                throw new IllegalArgumentException("A directory already uses this name.");
            }
            byte[] bytes = Base64.decode(encodedBytes == null ? "" : encodedBytes, Base64.DEFAULT);
            if (bytes.length > MAX_FILE_BYTES) {
                return failure("QuotaExceededError", "The image file exceeds the 32 MiB bridge limit.").toString();
            }
            try (FileOutputStream output = new FileOutputStream(target, false)) {
                output.write(bytes);
                output.flush();
            }
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
            requireWriteAccess();
            File target = resolveTarget(path);
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
            requireWriteAccess();
            File target = resolveTarget(path);
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
        File candidate = canonicalOrNull(new File(saved));
        boolean pathAllowed = candidate != null && isWithinStorage(candidate);
        if (pathAllowed && (!hasAllFilesAccess() || isUsableDirectory(candidate))) {
            selectedRoot = candidate;
        } else {
            preferences.edit().remove(preferenceKey).apply();
        }
    }

    private File requireSelectedRoot() throws IOException {
        if (!hasAllFilesAccess()) throw new SecurityException("MANAGE_EXTERNAL_STORAGE is not granted.");
        if (!isUsableDirectory(selectedRoot)) throw new IllegalStateException("No selected folder.");
        return selectedRoot.getCanonicalFile();
    }

    private void requireWriteAccess() throws IOException {
        File root = requireSelectedRoot();
        if (!root.canWrite()) throw new SecurityException("The selected folder is read-only.");
    }

    private File resolveDirectory(String path) throws IOException {
        File current = requireSelectedRoot();
        for (String part : splitPath(path)) {
            current = new File(current, part);
            if (!isUsableDirectory(current)) throw new IllegalArgumentException("Directory not found.");
            current = current.getCanonicalFile();
        }
        return current;
    }

    private File resolveFile(String path) throws IOException {
        File target = resolveTarget(path);
        if (!target.isFile() || !target.canRead()) throw new IllegalArgumentException("File not found.");
        return target;
    }

    private File resolveTarget(String path) throws IOException {
        String[] parts = splitPath(path);
        if (parts.length == 0) throw new IllegalArgumentException("A relative entry path is required.");
        File parent = resolveDirectory(join(parts, parts.length - 1));
        String name = validateName(parts[parts.length - 1]);
        File target = new File(parent, name);
        if (!isWithinStorage(target)) throw new SecurityException("Path is outside the selected folder.");
        return target;
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
        if (requestId != null) deliver(requestId, result);
    }

    private void deliver(String requestId, JSONObject result) {
        String script = "window.__waywardNativeImagePackPickerResolve&&window.__waywardNativeImagePackPickerResolve("
                + JSONObject.quote(requestId) + "," + result.toString() + ");";
        activity.runOnUiThread(() -> {
            if (webView != null) webView.evaluateJavascript(script, null);
        });
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
