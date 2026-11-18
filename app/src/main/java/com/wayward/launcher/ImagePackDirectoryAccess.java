package com.wayward.launcher;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import android.provider.DocumentsContract;
import android.util.Base64;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * On-demand SAF access for the image-pack directory chooser.
 * Only the current directory is queried when requested; no whole-tree scan,
 * index, file copy, or image cache is created.
 */
final class ImagePackDirectoryAccess {
    static final int REQUEST_CODE = 4106;
    private static final String PREFS = "wayward_launcher";
    private static final String TAG = "WaywardImagePackSAF";
    private static final int MAX_FILE_BYTES = 32 * 1024 * 1024;

    private final Activity activity;
    private final WebView webView;
    private final String preferenceKey;
    private final String diagnosticSessionId;
    private Uri treeUri;
    private boolean writable;
    private String pendingRequestId;
    private String pendingMode;

    ImagePackDirectoryAccess(Activity activity, WebView webView, String profileId) {
        this(activity, webView, profileId, "");
    }

    ImagePackDirectoryAccess(Activity activity, WebView webView, String profileId, String sessionId) {
        this.activity = activity;
        this.webView = webView;
        diagnosticSessionId = sessionId == null ? "" : sessionId;
        String safeProfile = profileId == null || !profileId.matches("[A-Za-z0-9_-]{1,80}")
                ? "default" : profileId;
        preferenceKey = "image_pack_tree_uri_" + safeProfile;
        restorePersistedTree();
        diagnostic("directory_access_backend_ready", "", "info",
                details("apiLevel", Build.VERSION.SDK_INT, "provider", "saf_tree"));
    }

    void openFolderPicker(String requestId, String mode) {
        if (requestId == null || !requestId.matches("[A-Za-z0-9._:-]{1,128}")) {
            diagnostic("native_folder_picker_request_rejected", "", "error", details("result", "invalid_request_id"));
            return;
        }
        if (!("read".equals(mode) || "readwrite".equals(mode))) {
            diagnostic("native_folder_picker_request_rejected", requestId, "error",
                    details("result", "invalid_permission_mode"));
            deliver(requestId, failure("TypeError", "Invalid directory picker permission mode."));
            return;
        }
        diagnostic("native_folder_picker_entered", requestId, "info",
                details("apiLevel", Build.VERSION.SDK_INT, "provider", "saf_tree",
                        "requestSource", "showDirectoryPicker", "mode", mode));
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
            pendingMode = mode;
            diagnostic("native_folder_picker_ui_start", requestId, "info",
                    details("permissionBefore", "saf_tree_grant_pending",
                            "requestSource", "showDirectoryPicker", "mode", mode, "requestState", "PENDING"));
            Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            int requestedFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION;
            if ("readwrite".equals(mode)) requestedFlags |= Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
            picker.addFlags(requestedFlags);

            Log.i(TAG, "picker_open action=OPEN_DOCUMENT_TREE api=" + Build.VERSION.SDK_INT
                    + " flags=caller_mode,persistable,prefix");
            try {
                activity.startActivityForResult(picker, REQUEST_CODE);
                diagnostic("native_saf_activity_launch_requested", requestId, "info",
                        details("result", "started", "permissionAction", "ACTION_OPEN_DOCUMENT_TREE"));
            } catch (Exception error) {
                Log.e(TAG, "picker_open result=failed error=" + error.getClass().getSimpleName());
                ImagePackDiagnostics.error(activity, diagnosticSessionId, requestId,
                        "native_saf_activity_launch_failed", error);
                finishPending(failure(error instanceof android.content.ActivityNotFoundException
                        ? "NotSupportedError" : "NotAllowedError",
                        "Android system folder picker could not be opened."));
            }
        });
    }

    void rejectRequest(String requestId, String errorName, String message) {
        if (requestId == null || !requestId.matches("[A-Za-z0-9._:-]{1,128}")) return;
        deliver(requestId, failure(errorName, message));
    }

    void onResume() {
        if (treeUri == null) {
            restorePersistedTree();
            return;
        }
        try {
            requireTree();
        } catch (SecurityException error) {
            treeUri = null;
            writable = false;
            diagnostic("native_saf_persisted_permission_revoked", "", "error",
                    details("result", "denied", "recoverable", true));
        }
    }

    void handleActivityResult(int resultCode, Intent data) {
        if (pendingRequestId == null) {
            diagnostic("native_saf_activity_result_without_pending_request", "", "warn", details("resultCode", resultCode));
            return;
        }
        diagnostic("native_saf_activity_result_received", pendingRequestId, "info",
                details("resultCode", resultCode, "result", resultCode == Activity.RESULT_OK ? "ok" : "cancelled",
                        "pendingRequest", true));
        if (resultCode != Activity.RESULT_OK) {
            Log.i(TAG, "picker_result result=cancelled resultCode=" + resultCode);
            diagnostic("native_saf_picker_cancelled", pendingRequestId, "warn",
                    details("resultCode", resultCode, "cancelled", true));
            finishPending(failure("AbortError", "Folder selection was cancelled."));
            return;
        }
        if (data == null || data.getData() == null) {
            diagnostic("native_saf_picker_uri_missing", pendingRequestId, "error",
                    details("resultCode", resultCode, "result", "missing_uri"));
            finishPending(failure("NotReadableError", "Android returned no selected folder URI."));
            return;
        }

        Uri selected = data.getData();
        int resultFlags = data.getFlags();
        int grants = resultFlags & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        boolean treeUriResult = "content".equalsIgnoreCase(selected.getScheme())
                && DocumentsContract.isTreeUri(selected);
        JSONObject uriDetails = details(
                "uriScheme", selected.getScheme() == null ? "none" : selected.getScheme(),
                "uriAuthority", selected.getAuthority() == null ? "" : selected.getAuthority(),
                "resultFlags", resultFlags,
                "readGranted", (grants & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0,
                "writeGranted", (grants & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0,
                "persistableGranted", (resultFlags & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0,
                "result", treeUriResult ? "tree_uri_returned" : "invalid_uri");
        diagnostic("native_saf_picker_uri_returned", pendingRequestId, "info", uriDetails);
        Log.i(TAG, "picker_result result=returned scheme="
                + (selected.getScheme() == null ? "none" : selected.getScheme())
                + " authority=" + (selected.getAuthority() == null ? "none" : selected.getAuthority())
                + " tree=" + treeUriResult
                + " read=" + ((grants & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)
                + " write=" + ((grants & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0)
                + " persistable=" + ((resultFlags & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0));

        boolean persistableFlag = (resultFlags & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0;
        boolean writeRequired = "readwrite".equals(pendingMode);
        boolean writeGranted = (grants & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0;
        if (!treeUriResult || (grants & Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0
                || !persistableFlag || (writeRequired && !writeGranted)) {
            Log.w(TAG, "picker_result result=rejected reason=invalid_or_non_persistable_tree");
            diagnostic("native_saf_uri_rejected", pendingRequestId, "error",
                    details("result", "missing_required_persistable_tree_grant",
                            "treeUri", treeUriResult, "readGranted",
                            (grants & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0,
                            "writeRequired", writeRequired, "writeGranted", writeGranted,
                            "persistableGranted", persistableFlag));
            finishPending(failure("SecurityError", writeRequired && !writeGranted
                    ? "DocumentsUI did not grant write access to the selected folder."
                    : "DocumentsUI did not return a readable, persistable SAF tree."));
            return;
        }

        Uri previousTreeUri = treeUri;
        boolean previousWritable = writable;
        boolean persistableTaken = false;
        try {
            activity.getContentResolver().takePersistableUriPermission(selected, grants);
            persistableTaken = true;
            android.content.UriPermission persisted = null;
            for (android.content.UriPermission permission :
                    activity.getContentResolver().getPersistedUriPermissions()) {
                if (selected.equals(permission.getUri()) && permission.isReadPermission()) {
                    persisted = permission;
                    break;
                }
            }
            if (persisted == null) {
                throw new SecurityException("The provider did not retain the selected tree grant.");
            }
            diagnostic("native_saf_persisted_permission_verified", pendingRequestId, "info",
                    details("persistedRead", persisted.isReadPermission(),
                            "persistedWrite", persisted.isWritePermission()));

            treeUri = selected;
            writable = persisted.isWritePermission();
            String displayName = rootName();
            boolean saved = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
                    .edit().putString(preferenceKey, selected.toString()).commit();
            if (!saved) throw new IllegalStateException("The image-pack folder URI could not be saved.");

            JSONObject result = new JSONObject();
            result.put("ok", true);
            result.put("name", displayName);
            result.put("uri", selected.toString());
            result.put("writable", writable);
            result.put("state", "SUCCESS");
            Log.i(TAG, "picker_result result=success persistedRead=true persistedWrite=" + writable);
            diagnostic("native_saf_permission_saved", pendingRequestId, "info",
                    details("persistedRead", true, "persistedWrite", writable, "writable", writable,
                            "requestState", "SUCCESS"));
            if (previousTreeUri != null && !previousTreeUri.equals(selected)) {
                releasePersistedTree(previousTreeUri);
            }
            result.put("state", "SUCCESS");
            finishPending(result);
        } catch (Exception error) {
            treeUri = previousTreeUri;
            writable = previousWritable;
            if (persistableTaken && !selected.equals(previousTreeUri)) {
                try {
                    activity.getContentResolver().releasePersistableUriPermission(selected, grants);
                } catch (Exception ignored) {}
            }
            Log.e(TAG, "picker_result result=failed error=" + error.getClass().getSimpleName());
            ImagePackDiagnostics.error(activity, diagnosticSessionId, pendingRequestId,
                    "native_saf_permission_persist_failed", error);
            finishPending(failure("SecurityError", "The selected folder permission could not be retained."));
        }
    }

    void cancelPending() {
        if (pendingRequestId != null) {
            finishPending(failure("AbortError", "The page was closed."));
        }
    }

    String getFolderInfoJson() {
        try {
            requireTree();
            JSONObject result = new JSONObject();
            result.put("ok", true);
            result.put("name", rootName());
            result.put("writable", writable);
            result.put("state", "SUCCESS");
            return result.toString();
        } catch (Exception error) {
            return failure("NotFoundError", "No saved image-pack folder is available.").toString();
        }
    }

    String listDirectoryJson(String path) {
        return listDirectoryJson(path, "");
    }

    String listDirectoryJson(String path, String requestId) {
        diagnostic("native_directory_list_started", requestId, "info", details("method", "listImagePackDirectory"));
        try {
            String documentId = resolveDirectory(path);
            JSONArray entries = new JSONArray();
            for (Node node : queryChildren(documentId)) {
                JSONObject item = new JSONObject();
                item.put("name", node.name);
                item.put("kind", node.directory ? "directory" : "file");
                item.put("size", node.size);
                item.put("mimeType", node.mimeType);
                item.put("lastModified", node.lastModified);
                entries.put(item);
            }
            JSONObject result = new JSONObject();
            result.put("ok", true);
            result.put("entries", entries);
            diagnostic("native_directory_list_succeeded", requestId, "info", details("entryCount", entries.length()));
            return result.toString();
        } catch (Exception error) {
            ImagePackDiagnostics.error(activity, diagnosticSessionId, requestId,
                    "native_directory_list_failed", error);
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
            Node node = resolveFile(path);
            if (node.size > MAX_FILE_BYTES) {
                return failure("QuotaExceededError", "The image file exceeds the 32 MiB bridge limit.").toString();
            }
            Uri documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, node.documentId);
            try (InputStream input = activity.getContentResolver().openInputStream(documentUri);
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                if (input == null) throw new IllegalStateException("File stream unavailable.");
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
                result.put("name", node.name);
                result.put("mimeType", node.mimeType == null ? "application/octet-stream" : node.mimeType);
                result.put("lastModified", node.lastModified);
                result.put("size", output.size());
                result.put("data", Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP));
                diagnostic("native_file_read_succeeded", requestId, "info", details("fileSize", output.size()));
                return result.toString();
            }
        } catch (Exception error) {
            ImagePackDiagnostics.error(activity, diagnosticSessionId, requestId,
                    "native_file_read_failed", error);
            return failure(error instanceof SecurityException ? "SecurityError" : "NotFoundError",
                    "The selected image-pack file could not be read.").toString();
        }
    }

    String writeFileJson(String path, String mimeType, String encodedBytes) {
        try {
            requireWritePermission();
            String[] parts = splitPath(path);
            if (parts.length == 0) throw new IllegalArgumentException("A file name is required.");
            String name = parts[parts.length - 1];
            String parentId = resolveDirectory(join(parts, parts.length - 1));
            Node existing = findChild(parentId, name);
            if (existing != null && existing.directory) {
                throw new IllegalArgumentException("A directory already uses this name.");
            }
            byte[] bytes = Base64.decode(encodedBytes == null ? "" : encodedBytes, Base64.DEFAULT);
            if (bytes.length > MAX_FILE_BYTES) {
                return failure("QuotaExceededError", "The image file exceeds the 32 MiB bridge limit.").toString();
            }
            String mime = mimeType == null || mimeType.trim().isEmpty()
                    ? "application/octet-stream" : mimeType.trim();
            Uri parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId);
            Uri documentUri = existing == null
                    ? DocumentsContract.createDocument(activity.getContentResolver(), parentUri, mime, name)
                    : DocumentsContract.buildDocumentUriUsingTree(treeUri, existing.documentId);
            if (documentUri == null) throw new IllegalStateException("Document provider refused file creation.");
            try (OutputStream output = activity.getContentResolver().openOutputStream(documentUri, "w")) {
                if (output == null) throw new IllegalStateException("Document provider returned no output stream.");
                output.write(bytes);
                output.flush();
            }
            JSONObject result = new JSONObject();
            result.put("ok", true);
            result.put("name", name);
            return result.toString();
        } catch (Exception error) {
            return failure(error instanceof SecurityException ? "SecurityError" : "NotAllowedError",
                    "The image-pack file could not be written.").toString();
        }
    }

    String createDirectoryJson(String path) {
        try {
            requireWritePermission();
            String[] parts = splitPath(path);
            if (parts.length == 0) throw new IllegalArgumentException("A directory name is required.");
            String name = parts[parts.length - 1];
            String parentId = resolveDirectory(join(parts, parts.length - 1));
            Node existing = findChild(parentId, name);
            if (existing != null) {
                if (!existing.directory) throw new IllegalArgumentException("A file already uses this name.");
            } else {
                Uri parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId);
                Uri created = DocumentsContract.createDocument(activity.getContentResolver(), parentUri,
                        DocumentsContract.Document.MIME_TYPE_DIR, name);
                if (created == null) throw new IllegalStateException("Document provider refused directory creation.");
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
            requireWritePermission();
            String[] parts = splitPath(path);
            if (parts.length == 0) throw new IllegalArgumentException("The selected root cannot be deleted.");
            String parentId = resolveDirectory(join(parts, parts.length - 1));
            Node node = findChild(parentId, parts[parts.length - 1]);
            if (node == null) throw new IllegalArgumentException("Entry not found.");
            Uri documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, node.documentId);
            if (!DocumentsContract.deleteDocument(activity.getContentResolver(), documentUri)) {
                throw new IllegalStateException("Document provider refused deletion.");
            }
            JSONObject result = new JSONObject();
            result.put("ok", true);
            return result.toString();
        } catch (Exception error) {
            return failure(error instanceof SecurityException ? "SecurityError" : "NotAllowedError",
                    "The image-pack entry could not be removed.").toString();
        }
    }

    private void releasePersistedTree(Uri uri) {
        if (uri == null) return;
        for (android.content.UriPermission permission :
                activity.getContentResolver().getPersistedUriPermissions()) {
            if (!uri.equals(permission.getUri())) continue;
            int flags = 0;
            if (permission.isReadPermission()) flags |= Intent.FLAG_GRANT_READ_URI_PERMISSION;
            if (permission.isWritePermission()) flags |= Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
            if (flags != 0) {
                try {
                    activity.getContentResolver().releasePersistableUriPermission(uri, flags);
                    diagnostic("native_saf_previous_tree_permission_released", "",
                            "info", details("readReleased", permission.isReadPermission(),
                                    "writeReleased", permission.isWritePermission()));
                } catch (Exception error) {
                    ImagePackDiagnostics.error(activity, diagnosticSessionId, "",
                            "native_saf_previous_tree_permission_release_failed", error);
                }
            }
            return;
        }
    }

    private void restorePersistedTree() {
        SharedPreferences preferences = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
        String saved = preferences.getString(preferenceKey, "");
        if (saved == null || saved.isEmpty()) {
            Log.i(TAG, "tree_restore result=empty");
            return;
        }
        try {
            Uri candidate = Uri.parse(saved);
            if (!"content".equalsIgnoreCase(candidate.getScheme()) || !DocumentsContract.isTreeUri(candidate)) {
                throw new SecurityException("Saved image-pack URI is not a valid content document tree.");
            }
            for (android.content.UriPermission permission : activity.getContentResolver().getPersistedUriPermissions()) {
                if (candidate.equals(permission.getUri()) && permission.isReadPermission()) {
                    treeUri = candidate;
                    writable = permission.isWritePermission();
                    Log.i(TAG, "tree_restore result=success persistedRead=true persistedWrite=" + writable);
                    return;
                }
            }
        } catch (Exception error) {
            Log.w(TAG, "tree_restore result=failed error=" + error.getClass().getSimpleName());
        }
        Log.w(TAG, "tree_restore result=stale_grant");
        preferences.edit().remove(preferenceKey).apply();
    }

    private void requireTree() {
        if (treeUri == null || !"content".equalsIgnoreCase(treeUri.getScheme())
                || !DocumentsContract.isTreeUri(treeUri)) {
            throw new SecurityException("No valid selected image-pack tree.");
        }
        boolean granted = false;
        writable = false;
        for (android.content.UriPermission permission : activity.getContentResolver().getPersistedUriPermissions()) {
            if (treeUri.equals(permission.getUri()) && permission.isReadPermission()) {
                writable = permission.isWritePermission();
                granted = true;
                break;
            }
        }
        if (!granted) throw new SecurityException("The selected image-pack tree grant is no longer available.");
    }

    private void requireWritePermission() {
        requireTree();
        if (!writable) throw new SecurityException("The selected tree is read-only.");
    }

    private String rootName() throws Exception {
        requireTree();
        String rootId = DocumentsContract.getTreeDocumentId(treeUri);
        Uri rootDocument = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId);
        try (Cursor cursor = activity.getContentResolver().query(rootDocument,
                new String[] { DocumentsContract.Document.COLUMN_DISPLAY_NAME }, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                if (column >= 0) return cursor.getString(column);
            }
        }
        return "Image packs";
    }

    private String resolveDirectory(String path) throws Exception {
        requireTree();
        String documentId = DocumentsContract.getTreeDocumentId(treeUri);
        for (String component : splitPath(path)) {
            Node child = findChild(documentId, component);
            if (child == null || !child.directory) throw new IllegalArgumentException("Directory not found.");
            documentId = child.documentId;
        }
        return documentId;
    }

    private Node resolveFile(String path) throws Exception {
        requireTree();
        String[] parts = splitPath(path);
        if (parts.length == 0) throw new IllegalArgumentException("A file name is required.");
        String parentId = resolveDirectory(join(parts, parts.length - 1));
        Node node = findChild(parentId, parts[parts.length - 1]);
        if (node == null || node.directory) throw new IllegalArgumentException("File not found.");
        return node;
    }

    private Node findChild(String parentId, String name) throws Exception {
        for (Node node : queryChildren(parentId)) {
            if (node.name.equals(name)) return node;
        }
        return null;
    }

    private List<Node> queryChildren(String parentId) throws Exception {
        requireTree();
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId);
        String[] projection = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
        };
        List<Node> result = new ArrayList<>();
        try (Cursor cursor = activity.getContentResolver().query(childrenUri, projection, null, null, null)) {
            if (cursor == null) throw new IllegalStateException("Document provider returned no directory cursor.");
            int idColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int nameColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            int mimeColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
            int sizeColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE);
            int modifiedColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED);
            while (cursor.moveToNext()) {
                String id = idColumn >= 0 ? cursor.getString(idColumn) : null;
                String name = nameColumn >= 0 ? cursor.getString(nameColumn) : null;
                if (id == null || name == null) continue;
                String mime = mimeColumn >= 0 ? cursor.getString(mimeColumn) : null;
                long size = sizeColumn >= 0 && !cursor.isNull(sizeColumn) ? cursor.getLong(sizeColumn) : -1L;
                long modified = modifiedColumn >= 0 && !cursor.isNull(modifiedColumn) ? cursor.getLong(modifiedColumn) : -1L;
                result.add(new Node(id, name, DocumentsContract.Document.MIME_TYPE_DIR.equals(mime),
                        mime, size, modified));
            }
        }
        return result;
    }

    private static String[] splitPath(String path) {
        if (path == null || path.length() > 4096 || path.startsWith("/")
                || path.indexOf('\\') >= 0 || path.indexOf('\0') >= 0) {
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

    private static String join(String[] parts, int count) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) result.append('/');
            result.append(parts[i]);
        }
        return result.toString();
    }

    private void finishPending(JSONObject result) {
        String requestId = pendingRequestId;
        pendingRequestId = null;
        pendingMode = null;
        if (requestId != null) {
            diagnostic("native_folder_picker_result_ready", requestId,
                    result.optBoolean("ok") ? "info" : "warn",
                    details("ok", result.optBoolean("ok"), "errorName", result.optBoolean("ok") ? "" : result.optString("name", ""),
                            "result", result.optBoolean("ok") ? "success" : "failure"));
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

    private static JSONObject details(Object... values) {
        JSONObject result = new JSONObject();
        for (int i = 0; i + 1 < values.length; i += 2) {
            try { result.put(String.valueOf(values[i]), values[i + 1]); } catch (Exception ignored) {}
        }
        return result;
    }

    private static JSONObject failure(String name, String message) {
        JSONObject result = new JSONObject();
        String state = "FAILED";
        if ("AbortError".equals(name)) state = "CANCELLED";
        else if ("NotAllowedError".equals(name) || "SecurityError".equals(name)) state = "DENIED";
        else if ("NotSupportedError".equals(name)) state = "UNSUPPORTED";
        try {
            result.put("ok", false);
            result.put("name", name);
            result.put("message", message);
            result.put("state", state);
        } catch (Exception ignored) {}
        return result;
    }

    private static final class Node {
        final String documentId;
        final String name;
        final boolean directory;
        final String mimeType;
        final long size;
        final long lastModified;

        Node(String documentId, String name, boolean directory, String mimeType, long size, long lastModified) {
            this.documentId = documentId;
            this.name = name;
            this.directory = directory;
            this.mimeType = mimeType;
            this.size = size;
            this.lastModified = lastModified;
        }
    }
}
