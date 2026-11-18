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
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
    private static final int MAX_STREAM_CHUNK_BYTES = 64 * 1024;
    private static final int MAX_OPEN_STREAMS = 4;
    private static final long STREAM_IDLE_TIMEOUT_MS = 5 * 60 * 1000L;

    private final Activity activity;
    private final WebView webView;
    private final String preferenceKey;
    private final String writablePreferenceKey;
    private final String diagnosticSessionId;
    private Uri treeUri;
    private boolean writable;
    private String pendingRequestId;
    private String pendingMode;
    private final Map<String, NativeFileStream> openFileStreams = new HashMap<>();

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
        writablePreferenceKey = "image_pack_tree_writable_" + safeProfile;
        restorePersistedTree();
        diagnostic("directory_access_backend_ready", "", "info",
                details("apiLevel", Build.VERSION.SDK_INT, "provider", "saf_tree"));
    }

    void openFolderPicker(String requestId, String mode, String initialUri) {
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
            Uri initial = null;
            if (initialUri != null && !initialUri.isEmpty()) {
                try {
                    initial = requireDocumentUri(initialUri, true);
                } catch (Exception error) {
                    deliver(requestId, failure("NotAllowedError", "The requested startIn directory is unavailable."));
                    return;
                }
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
            if (initial != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                picker.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial);
            }

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
        boolean writeGranted = writeRequired
                && (resultFlags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0;
        // A read request remains read-only even if a provider returns an extra write grant.
        if (!writeRequired) grants &= ~Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
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
            if (persisted == null || (writeRequired && !persisted.isWritePermission())) {
                throw new SecurityException("The provider did not retain the required selected tree permissions.");
            }
            diagnostic("native_saf_persisted_permission_verified", pendingRequestId, "info",
                    details("persistedRead", persisted.isReadPermission(),
                            "persistedWrite", persisted.isWritePermission()));

            treeUri = selected;
            writable = writeRequired && persisted.isWritePermission();
            String displayName = rootName();
            boolean saved = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
                    .edit().putString(preferenceKey, selected.toString())
                    .putBoolean(writablePreferenceKey, writable).commit();
            if (!saved) throw new IllegalStateException("The image-pack folder URI could not be saved.");

            JSONObject result = new JSONObject();
            result.put("ok", true);
            result.put("name", displayName);
            result.put("uri", selected.toString());
            result.put("documentUri", DocumentsContract.buildDocumentUriUsingTree(
                    selected, DocumentsContract.getTreeDocumentId(selected)).toString());
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
            Uri rootDocumentUri = DocumentsContract.buildDocumentUriUsingTree(
                    treeUri, DocumentsContract.getTreeDocumentId(treeUri));
            Node root = queryDocument(rootDocumentUri);
            JSONObject result = new JSONObject();
            result.put("ok", true);
            result.put("name", root.name);
            result.put("uri", treeUri.toString());
            result.put("documentUri", rootDocumentUri.toString());
            result.put("writable", writable);
            result.put("state", "SUCCESS");
            return result.toString();
        } catch (Exception error) {
            return failure(error instanceof SecurityException ? "SecurityError" : "NotFoundError",
                    "No saved image-pack folder is available.").toString();
        }
    }

    String listDirectoryJson(String documentUri, String requestId) {
        diagnostic("native_directory_list_started", requestId, "info",
                details("method", "listImagePackDirectory", "uriBased", true));
        try {
            Uri directoryUri = requireDocumentUri(documentUri, true);
            String documentId = DocumentsContract.getDocumentId(directoryUri);
            JSONArray entries = new JSONArray();
            for (Node node : queryChildren(documentId)) {
                JSONObject item = new JSONObject();
                item.put("name", node.name);
                item.put("kind", node.directory ? "directory" : "file");
                item.put("uri", DocumentsContract.buildDocumentUriUsingTree(treeUri, node.documentId).toString());
                item.put("size", node.size);
                item.put("mimeType", node.mimeType);
                item.put("lastModified", node.lastModified);
                entries.put(item);
            }
            JSONObject result = new JSONObject();
            result.put("ok", true);
            result.put("uri", directoryUri.toString());
            result.put("entries", entries);
            diagnostic("native_directory_list_succeeded", requestId, "info",
                    details("entryCount", entries.length(), "uriBased", true));
            return result.toString();
        } catch (Exception error) {
            ImagePackDiagnostics.error(activity, diagnosticSessionId, requestId,
                    "native_directory_list_failed", error);
            return failure(error instanceof SecurityException ? "SecurityError" : "NotFoundError",
                    "The selected SAF directory URI could not be listed.").toString();
        }
    }

    String readFileJson(String documentUri, String requestId) {
        diagnostic("native_file_read_started", requestId, "info",
                details("method", "readImagePackFile", "uriBased", true));
        try {
            Uri fileUri = requireDocumentUri(documentUri, false);
            Node node = queryDocument(fileUri);
            if (node.directory) throw new IllegalArgumentException("A directory URI cannot be read as a file.");
            if (node.size > MAX_FILE_BYTES) {
                return failure("QuotaExceededError", "The image file exceeds the 32 MiB bridge limit.").toString();
            }
            try (InputStream input = activity.getContentResolver().openInputStream(fileUri);
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
                result.put("uri", fileUri.toString());
                result.put("name", node.name);
                result.put("mimeType", node.mimeType == null ? "application/octet-stream" : node.mimeType);
                result.put("lastModified", node.lastModified);
                result.put("size", output.size());
                result.put("data", Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP));
                diagnostic("native_file_read_succeeded", requestId, "info",
                        details("fileSize", output.size(), "uriBased", true));
                return result.toString();
            }
        } catch (Exception error) {
            ImagePackDiagnostics.error(activity, diagnosticSessionId, requestId,
                    "native_file_read_failed", error);
            return failure(error instanceof SecurityException ? "SecurityError" : "NotFoundError",
                    "The selected SAF document URI could not be read.").toString();
        }
    }


    synchronized String openFileStreamJson(String documentUri, String startText,
                                           String lengthText, String requestId) {
        InputStream input = null;
        try {
            pruneExpiredStreams();
            if (openFileStreams.size() >= MAX_OPEN_STREAMS) {
                return failure("QuotaExceededError", "Too many image-pack files are open at once.").toString();
            }
            long start = parseNonNegativeLong(startText, "Invalid file stream offset.");
            long length = parseStreamLength(lengthText);
            if (start > MAX_FILE_BYTES || (length >= 0 && length > MAX_FILE_BYTES)) {
                return failure("QuotaExceededError", "The requested file range exceeds the 32 MiB bridge limit.").toString();
            }
            Uri fileUri = requireDocumentUri(documentUri, false);
            Node node = queryDocument(fileUri);
            if (node.directory) throw new IllegalArgumentException("A directory URI cannot be streamed as a file.");
            if (node.size > MAX_FILE_BYTES || (node.size >= 0 && start > node.size)) {
                return failure("QuotaExceededError", "The image file exceeds the 32 MiB bridge limit or requested range.").toString();
            }
            input = activity.getContentResolver().openInputStream(fileUri);
            if (input == null) throw new IOException("File stream unavailable.");
            skipFully(input, start);
            long effectiveLength = length;
            if (node.size >= 0) {
                long available = Math.max(0, node.size - start);
                effectiveLength = length < 0 ? available : Math.min(length, available);
            }
            String streamId = UUID.randomUUID().toString();
            openFileStreams.put(streamId, new NativeFileStream(input, effectiveLength, requestId));
            input = null;
            JSONObject result = new JSONObject();
            result.put("ok", true);
            result.put("streamId", streamId);
            result.put("size", effectiveLength);
            result.put("chunkBytes", MAX_STREAM_CHUNK_BYTES);
            result.put("name", node.name);
            result.put("mimeType", node.mimeType == null ? "application/octet-stream" : node.mimeType);
            result.put("lastModified", node.lastModified);
            diagnostic("native_file_stream_opened", requestId, "info",
                    details("streamId", streamId, "start", start, "size", effectiveLength));
            return result.toString();
        } catch (Exception error) {
            if (input != null) try { input.close(); } catch (IOException ignored) {}
            ImagePackDiagnostics.error(activity, diagnosticSessionId, requestId,
                    "native_file_stream_open_failed", error);
            return failure(error instanceof SecurityException ? "SecurityError" : "NotReadableError",
                    "The selected SAF document stream could not be opened.").toString();
        }
    }

    synchronized String readFileStreamChunkJson(String streamId, String maxBytesText, String requestId) {
        try {
            pruneExpiredStreams();
            NativeFileStream stream = openFileStreams.get(streamId);
            if (stream == null) return failure("NotReadableError", "The SAF file stream is no longer open.").toString();
            if (!stream.requestId.equals(requestId == null ? "" : requestId)) {
                return failure("SecurityError", "The SAF file stream belongs to another picker request.").toString();
            }
            int requested = (int) Math.min(MAX_STREAM_CHUNK_BYTES,
                    Math.max(1L, parseNonNegativeLong(maxBytesText, "Invalid stream chunk size.")));
            if (stream.remaining == 0) {
                closeFileStream(streamId);
                JSONObject done = new JSONObject();
                done.put("ok", true);
                done.put("data", "");
                done.put("done", true);
                done.put("bytesRead", 0);
                return done.toString();
            }
            int allowed = stream.remaining < 0 ? requested : (int) Math.min(requested, stream.remaining);
            byte[] buffer = new byte[allowed];
            int count = stream.input.read(buffer);
            if (count == 0) throw new IOException("The SAF provider returned an empty non-final read.");
            if (count < 0) {
                closeFileStream(streamId);
                JSONObject done = new JSONObject();
                done.put("ok", true);
                done.put("data", "");
                done.put("done", true);
                done.put("bytesRead", 0);
                return done.toString();
            }
            stream.totalRead += count;
            if (stream.totalRead > MAX_FILE_BYTES) {
                closeFileStream(streamId);
                return failure("QuotaExceededError", "The image file exceeds the 32 MiB bridge limit.").toString();
            }
            if (stream.remaining >= 0) stream.remaining -= count;
            stream.lastAccessAt = System.currentTimeMillis();
            boolean done = stream.remaining == 0;
            String encoded = Base64.encodeToString(
                    count == buffer.length ? buffer : java.util.Arrays.copyOf(buffer, count), Base64.NO_WRAP);
            if (done) closeFileStream(streamId);
            JSONObject result = new JSONObject();
            result.put("ok", true);
            result.put("data", encoded);
            result.put("done", done);
            result.put("bytesRead", count);
            return result.toString();
        } catch (Exception error) {
            closeFileStream(streamId);
            ImagePackDiagnostics.error(activity, diagnosticSessionId, requestId,
                    "native_file_stream_read_failed", error);
            return failure(error instanceof SecurityException ? "SecurityError" : "NotReadableError",
                    "The selected SAF document stream could not be read.").toString();
        }
    }

    synchronized String closeFileStreamJson(String streamId, String requestId) {
        NativeFileStream stream = openFileStreams.get(streamId);
        if (stream != null && !stream.requestId.equals(requestId == null ? "" : requestId)) {
            return failure("SecurityError", "The SAF file stream belongs to another picker request.").toString();
        }
        closeFileStream(streamId);
        JSONObject result = new JSONObject();
        try { result.put("ok", true); result.put("closed", true); }
        catch (Exception ignored) {}
        return result.toString();
    }

    private static long parseNonNegativeLong(String value, String message) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 0) throw new NumberFormatException("negative");
            return parsed;
        } catch (Exception error) { throw new IllegalArgumentException(message, error); }
    }

    private static long parseStreamLength(String value) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed < -1) throw new NumberFormatException("invalid length");
            return parsed;
        } catch (Exception error) { throw new IllegalArgumentException("Invalid file stream length.", error); }
    }

    private static void skipFully(InputStream input, long bytes) throws IOException {
        long remaining = bytes;
        byte[] discard = null;
        int zeroReads = 0;
        while (remaining > 0) {
            long skipped = input.skip(remaining);
            if (skipped > 0) { remaining -= skipped; zeroReads = 0; continue; }
            if (discard == null) discard = new byte[8192];
            int read = input.read(discard, 0, (int) Math.min(discard.length, remaining));
            if (read < 0) throw new IOException("Requested SAF file offset is beyond EOF.");
            if (read == 0) {
                if (++zeroReads >= 3) throw new IOException("The SAF provider stalled while seeking within the file.");
                continue;
            }
            zeroReads = 0;
            remaining -= read;
        }
    }

    private void pruneExpiredStreams() {
        long cutoff = System.currentTimeMillis() - STREAM_IDLE_TIMEOUT_MS;
        Iterator<Map.Entry<String, NativeFileStream>> iterator = openFileStreams.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, NativeFileStream> entry = iterator.next();
            if (entry.getValue().lastAccessAt < cutoff) {
                entry.getValue().close();
                iterator.remove();
            }
        }
    }

    private void closeFileStream(String streamId) {
        NativeFileStream stream = openFileStreams.remove(streamId);
        if (stream != null) stream.close();
    }

    synchronized void closeAllFileStreams() {
        for (NativeFileStream stream : openFileStreams.values()) stream.close();
        openFileStreams.clear();
    }

    private static final class NativeFileStream {
        final InputStream input;
        final String requestId;
        long remaining;
        long totalRead;
        long lastAccessAt = System.currentTimeMillis();
        NativeFileStream(InputStream input, long remaining, String requestId) {
            this.input = input;
            this.remaining = remaining;
            this.requestId = requestId == null ? "" : requestId;
        }
        void close() {
            try { input.close(); } catch (IOException ignored) {}
        }
    }

    String createFileJson(String parentUri, String name, String mimeType, String requestId) {
        try {
            requireWritePermission();
            Uri parent = requireDocumentUri(parentUri, true);
            validateEntryName(name);
            String parentId = DocumentsContract.getDocumentId(parent);
            Node existing = findChild(parentId, name);
            if (existing != null) {
                if (existing.directory) throw new IllegalArgumentException("A directory already uses this name.");
                JSONObject result = new JSONObject();
                result.put("ok", true);
                result.put("uri", DocumentsContract.buildDocumentUriUsingTree(treeUri, existing.documentId).toString());
                result.put("created", false);
                return result.toString();
            }
            String mime = mimeType == null || mimeType.trim().isEmpty()
                    ? "application/octet-stream" : mimeType.trim();
            Uri created = DocumentsContract.createDocument(activity.getContentResolver(), parent, mime, name);
            if (created == null) throw new IllegalStateException("Document provider refused file creation.");
            requireDocumentUri(created.toString(), false);
            JSONObject result = new JSONObject();
            result.put("ok", true);
            result.put("uri", created.toString());
            result.put("created", true);
            return result.toString();
        } catch (Exception error) {
            ImagePackDiagnostics.error(activity, diagnosticSessionId, requestId,
                    "native_file_create_failed", error);
            return failure(error instanceof SecurityException ? "SecurityError" : "NotAllowedError",
                    "The SAF file could not be created.").toString();
        }
    }

    String writeFileJson(String documentUri, String mimeType, String encodedBytes, String requestId) {
        try {
            requireWritePermission();
            Uri fileUri = requireDocumentUri(documentUri, false);
            Node node = queryDocument(fileUri);
            if (node.directory) throw new IllegalArgumentException("A directory cannot be written as a file.");
            byte[] bytes = Base64.decode(encodedBytes == null ? "" : encodedBytes, Base64.DEFAULT);
            if (bytes.length > MAX_FILE_BYTES) {
                return failure("QuotaExceededError", "The image file exceeds the 32 MiB bridge limit.").toString();
            }
            try (OutputStream output = activity.getContentResolver().openOutputStream(fileUri, "w")) {
                if (output == null) throw new IllegalStateException("Document provider returned no output stream.");
                output.write(bytes);
                output.flush();
            }
            JSONObject result = new JSONObject();
            result.put("ok", true);
            result.put("uri", fileUri.toString());
            result.put("name", node.name);
            result.put("size", bytes.length);
            return result.toString();
        } catch (Exception error) {
            ImagePackDiagnostics.error(activity, diagnosticSessionId, requestId,
                    "native_file_write_failed", error);
            return failure(error instanceof SecurityException ? "SecurityError" : "NotAllowedError",
                    "The SAF document could not be written.").toString();
        }
    }

    String createDirectoryJson(String parentUri, String name, String requestId) {
        try {
            requireWritePermission();
            Uri parent = requireDocumentUri(parentUri, true);
            validateEntryName(name);
            String parentId = DocumentsContract.getDocumentId(parent);
            Node existing = findChild(parentId, name);
            if (existing != null) {
                if (!existing.directory) throw new IllegalArgumentException("A file already uses this name.");
                JSONObject result = new JSONObject();
                result.put("ok", true);
                result.put("uri", DocumentsContract.buildDocumentUriUsingTree(treeUri, existing.documentId).toString());
                result.put("created", false);
                return result.toString();
            }
            Uri created = DocumentsContract.createDocument(activity.getContentResolver(), parent,
                    DocumentsContract.Document.MIME_TYPE_DIR, name);
            if (created == null) throw new IllegalStateException("Document provider refused directory creation.");
            requireDocumentUri(created.toString(), true);
            JSONObject result = new JSONObject();
            result.put("ok", true);
            result.put("uri", created.toString());
            result.put("created", true);
            return result.toString();
        } catch (Exception error) {
            ImagePackDiagnostics.error(activity, diagnosticSessionId, requestId,
                    "native_directory_create_failed", error);
            return failure(error instanceof SecurityException ? "SecurityError" : "NotAllowedError",
                    "The SAF directory could not be created.").toString();
        }
    }

    String deleteEntryJson(String documentUri, String requestId) {
        try {
            requireWritePermission();
            Uri entryUri = requireDocumentUri(documentUri, false, true);
            if (DocumentsContract.getDocumentId(entryUri)
                    .equals(DocumentsContract.getTreeDocumentId(treeUri))) {
                throw new SecurityException("The selected SAF root cannot be deleted.");
            }
            if (!DocumentsContract.deleteDocument(activity.getContentResolver(), entryUri)) {
                throw new IllegalStateException("Document provider refused deletion.");
            }
            JSONObject result = new JSONObject();
            result.put("ok", true);
            result.put("uri", entryUri.toString());
            return result.toString();
        } catch (Exception error) {
            ImagePackDiagnostics.error(activity, diagnosticSessionId, requestId,
                    "native_entry_delete_failed", error);
            return failure(error instanceof SecurityException ? "SecurityError" : "NotAllowedError",
                    "The SAF entry could not be removed.").toString();
        }
    }

    String resolveEntryJson(String parentUri, String targetUri, String requestId) {
        try {
            Uri parent = requireDocumentUri(parentUri, true);
            Uri target = requireDocumentUri(targetUri, false, true);
            List<String> relative = new ArrayList<>();
            boolean found = findRelativeDocument(DocumentsContract.getDocumentId(parent),
                    DocumentsContract.getDocumentId(target), relative, new java.util.HashSet<>(), 0);
            JSONObject result = new JSONObject();
            result.put("ok", true);
            if (found) {
                JSONArray path = new JSONArray();
                for (String part : relative) path.put(part);
                result.put("path", path);
            } else result.put("path", JSONObject.NULL);
            return result.toString();
        } catch (Exception error) {
            ImagePackDiagnostics.error(activity, diagnosticSessionId, requestId,
                    "native_entry_resolve_failed", error);
            return failure(error instanceof SecurityException ? "SecurityError" : "NotFoundError",
                    "The SAF entry relationship could not be resolved.").toString();
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
                    writable = permission.isWritePermission()
                            && preferences.getBoolean(writablePreferenceKey, permission.isWritePermission());
                    Log.i(TAG, "tree_restore result=success persistedRead=true persistedWrite=" + writable);
                    return;
                }
            }
        } catch (Exception error) {
            Log.w(TAG, "tree_restore result=failed error=" + error.getClass().getSimpleName());
        }
        Log.w(TAG, "tree_restore result=stale_grant");
        preferences.edit().remove(preferenceKey).remove(writablePreferenceKey).apply();
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
                writable = permission.isWritePermission()
                        && activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
                                .getBoolean(writablePreferenceKey, permission.isWritePermission());
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

    private Uri requireDocumentUri(String value, boolean requireDirectory) throws Exception {
        return requireDocumentUri(value, requireDirectory, false);
    }

    private Uri requireDocumentUri(String value, boolean requireDirectory, boolean eitherKind) throws Exception {
        requireTree();
        if (value == null || value.length() > 8192) throw new IllegalArgumentException("Missing SAF document URI.");
        Uri candidate = Uri.parse(value);
        if (!"content".equalsIgnoreCase(candidate.getScheme())
                || candidate.getAuthority() == null
                || !candidate.getAuthority().equals(treeUri.getAuthority())
                || !DocumentsContract.isDocumentUri(activity, candidate)
                || !DocumentsContract.getTreeDocumentId(candidate).equals(
                        DocumentsContract.getTreeDocumentId(treeUri))) {
            throw new SecurityException("The document URI is outside the selected SAF tree.");
        }
        Node node = queryDocument(candidate);
        if (!eitherKind && node.directory != requireDirectory) {
            throw new IllegalArgumentException(requireDirectory
                    ? "The selected SAF document is not a directory."
                    : "The selected SAF document is not a file.");
        }
        return DocumentsContract.buildDocumentUriUsingTree(treeUri, node.documentId);
    }

    private Node queryDocument(Uri documentUri) throws Exception {
        String documentId = DocumentsContract.getDocumentId(documentUri);
        String[] projection = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
        };
        try (Cursor cursor = activity.getContentResolver().query(documentUri, projection, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) throw new IllegalArgumentException("SAF document does not exist.");
            int idColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int nameColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            int mimeColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
            int sizeColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE);
            int modifiedColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED);
            String id = idColumn >= 0 ? cursor.getString(idColumn) : documentId;
            String name = nameColumn >= 0 ? cursor.getString(nameColumn) : null;
            if (id == null || name == null) throw new IllegalStateException("SAF document metadata is incomplete.");
            String mime = mimeColumn >= 0 ? cursor.getString(mimeColumn) : null;
            long size = sizeColumn >= 0 && !cursor.isNull(sizeColumn) ? cursor.getLong(sizeColumn) : -1L;
            long modified = modifiedColumn >= 0 && !cursor.isNull(modifiedColumn) ? cursor.getLong(modifiedColumn) : -1L;
            return new Node(id, name, DocumentsContract.Document.MIME_TYPE_DIR.equals(mime),
                    mime, size, modified);
        }
    }

    private void validateEntryName(String name) {
        if (name == null || name.trim().isEmpty() || name.length() > 255
                || ".".equals(name) || "..".equals(name) || name.indexOf('/') >= 0
                || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("A single valid SAF entry name is required.");
        }
    }

    private boolean findRelativeDocument(String parentId, String targetId, List<String> path,
            java.util.Set<String> visited, int depth) throws Exception {
        if (parentId.equals(targetId)) return true;
        if (depth >= 64 || !visited.add(parentId)) return false;
        for (Node child : queryChildren(parentId)) {
            path.add(child.name);
            if (child.documentId.equals(targetId)) return true;
            if (child.directory && findRelativeDocument(child.documentId, targetId, path, visited, depth + 1)) {
                return true;
            }
            path.remove(path.size() - 1);
        }
        return false;
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
