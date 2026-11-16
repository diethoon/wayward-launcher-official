package com.wayward.launcher;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
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
    private static final int MAX_FILE_BYTES = 32 * 1024 * 1024;

    private final Activity activity;
    private final WebView webView;
    private final String preferenceKey;
    private Uri treeUri;
    private boolean writable;
    private String pendingRequestId;
    private Uri initialUriForTesting;

    ImagePackDirectoryAccess(Activity activity, WebView webView, String profileId) {
        this.activity = activity;
        this.webView = webView;
        String safeProfile = profileId == null || !profileId.matches("[A-Za-z0-9_-]{1,80}")
                ? "default" : profileId;
        preferenceKey = "image_pack_tree_uri_" + safeProfile;
        restorePersistedTree();
    }

    void setInitialUriForTesting(Uri uri) {
        initialUriForTesting = uri;
    }

    void openFolderPicker(String requestId) {
        if (requestId == null || !requestId.matches("[A-Za-z0-9_-]{1,80}")) return;
        activity.runOnUiThread(() -> {
            if (pendingRequestId != null) {
                finishPending(failure("AbortError", "A previous folder request was replaced."));
            }
            pendingRequestId = requestId;
            Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            if (initialUriForTesting != null) {
                picker.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUriForTesting);
            }
            try {
                activity.startActivityForResult(picker, REQUEST_CODE);
            } catch (Exception error) {
                finishPending(failure("NotAllowedError", "Android folder picker could not be opened."));
            }
        });
    }

    void handleActivityResult(int resultCode, Intent data) {
        if (pendingRequestId == null) return;
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            finishPending(failure("AbortError", "Folder selection was cancelled."));
            return;
        }

        Uri selected = data.getData();
        int grants = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if (!"content".equalsIgnoreCase(selected.getScheme())
                || !DocumentsContract.isTreeUri(selected)
                || (grants & Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0) {
            finishPending(failure("SecurityError", "DocumentsUI did not return a readable SAF tree."));
            return;
        }

        try {
            activity.getContentResolver().takePersistableUriPermission(selected, grants);
            treeUri = selected;
            writable = (grants & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0;
            boolean saved = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
                    .edit().putString(preferenceKey, selected.toString()).commit();
            if (!saved) throw new IllegalStateException("The image-pack folder URI could not be saved.");

            JSONObject result = new JSONObject();
            result.put("ok", true);
            result.put("name", rootName());
            result.put("writable", writable);
            finishPending(result);
        } catch (Exception error) {
            treeUri = null;
            writable = false;
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
            return result.toString();
        } catch (Exception error) {
            return failure("NotFoundError", "No saved image-pack folder is available.").toString();
        }
    }

    String listDirectoryJson(String path) {
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
            return result.toString();
        } catch (Exception error) {
            return failure(error instanceof SecurityException ? "SecurityError" : "NotFoundError",
                    "The selected image-pack directory could not be listed.").toString();
        }
    }

    String readFileJson(String path) {
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
                return result.toString();
            }
        } catch (Exception error) {
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

    private void restorePersistedTree() {
        SharedPreferences preferences = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
        String saved = preferences.getString(preferenceKey, "");
        if (saved == null || saved.isEmpty()) return;
        try {
            Uri candidate = Uri.parse(saved);
            for (android.content.UriPermission permission : activity.getContentResolver().getPersistedUriPermissions()) {
                if (candidate.equals(permission.getUri()) && permission.isReadPermission()) {
                    treeUri = candidate;
                    writable = permission.isWritePermission();
                    return;
                }
            }
        } catch (Exception ignored) {}
        preferences.edit().remove(preferenceKey).apply();
    }

    private void requireTree() {
        if (treeUri == null) throw new SecurityException("No selected image-pack tree.");
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
