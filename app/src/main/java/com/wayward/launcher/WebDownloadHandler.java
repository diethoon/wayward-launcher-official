package com.wayward.launcher;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Process;
import android.util.Base64;
import android.webkit.WebView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicInteger;

/** Streams the game's Blob-backed anchor downloads to Android's system document picker. */
final class WebDownloadHandler {
    private static final int REQUEST_CODE_MIN = 42000;
    private static final int REQUEST_CODE_MAX = 49999;
    private static final long MAX_DOWNLOAD_BYTES = 512L * 1024L * 1024L;
    private static final AtomicInteger NEXT_REQUEST_CODE = new AtomicInteger(REQUEST_CODE_MIN);

    private static final class Pending {
        final String requestId;
        final String filename;
        final String mimeType;
        final long expectedBytes;
        final int requestCode;
        final File temporaryFile;
        FileOutputStream stream;
        long writtenBytes;

        Pending(String requestId, String filename, String mimeType, long expectedBytes,
                int requestCode, File temporaryFile, FileOutputStream stream) {
            this.requestId = requestId;
            this.filename = filename;
            this.mimeType = mimeType;
            this.expectedBytes = expectedBytes;
            this.requestCode = requestCode;
            this.temporaryFile = temporaryFile;
            this.stream = stream;
        }
    }

    private final Activity activity;
    private final WebView webView;
    private Pending pending;

    WebDownloadHandler(Activity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
    }

    static boolean isRequestCode(int requestCode) {
        return requestCode >= REQUEST_CODE_MIN && requestCode <= REQUEST_CODE_MAX;
    }

    private static int nextRequestCode() {
        return NEXT_REQUEST_CODE.getAndUpdate(current ->
                current >= REQUEST_CODE_MAX ? REQUEST_CODE_MIN : current + 1);
    }

    synchronized boolean beginDownload(String requestId, String filename, String mimeType, long expectedBytes) {
        if (pending != null) return false;
        if (requestId == null || !requestId.matches("[A-Za-z0-9._:-]{1,128}")) return false;
        if (filename == null || filename.trim().isEmpty() || filename.length() > 255
                || filename.indexOf('/') >= 0 || filename.indexOf('\\') >= 0
                || filename.indexOf('\0') >= 0 || ".".equals(filename) || "..".equals(filename)) return false;
        if (expectedBytes < 0 || expectedBytes > MAX_DOWNLOAD_BYTES) return false;
        File directory = new File(activity.getCacheDir(), "wayward-exports");
        if (!directory.isDirectory() && !directory.mkdirs()) return false;
        File temporaryFile;
        FileOutputStream stream;
        try {
            temporaryFile = File.createTempFile("export-", ".part", directory);
            stream = new FileOutputStream(temporaryFile, false);
        } catch (Exception error) {
            DiagnosticsLogger.event(activity, "ERROR", "webview", "blob_download_temp_create_failed", null);
            return false;
        }
        pending = new Pending(requestId, filename,
                mimeType == null || mimeType.trim().isEmpty() ? "application/octet-stream" : mimeType.trim(),
                expectedBytes, nextRequestCode(), temporaryFile, stream);
        DiagnosticsLogger.event(activity, "INFO", "webview", "blob_download_stream_started", null);
        return true;
    }

    synchronized boolean appendChunk(String requestId, String encodedBytes) {
        if (pending == null || !pending.requestId.equals(requestId) || pending.stream == null) return false;
        try {
            byte[] bytes = Base64.decode(encodedBytes == null ? "" : encodedBytes, Base64.DEFAULT);
            if (pending.writtenBytes + bytes.length > pending.expectedBytes
                    || pending.writtenBytes + bytes.length > MAX_DOWNLOAD_BYTES) return false;
            pending.stream.write(bytes);
            pending.writtenBytes += bytes.length;
            return true;
        } catch (Exception error) {
            DiagnosticsLogger.event(activity, "ERROR", "webview", "blob_download_chunk_failed", null);
            return false;
        }
    }

    boolean finishDownload(String requestId) {
        final Pending request;
        synchronized (this) {
            if (pending == null || !pending.requestId.equals(requestId)
                    || pending.writtenBytes != pending.expectedBytes || pending.stream == null) return false;
            request = pending;
            try {
                request.stream.flush();
                request.stream.close();
                request.stream = null;
            } catch (Exception error) {
                cleanupLocked(request);
                pending = null;
                DiagnosticsLogger.event(activity, "ERROR", "webview", "blob_download_stream_close_failed", null);
                return false;
            }
        }
        activity.runOnUiThread(() -> launchPicker(request));
        return true;
    }

    synchronized void abortDownload(String requestId) {
        if (pending == null || !pending.requestId.equals(requestId)) return;
        Pending request = pending;
        pending = null;
        cleanupLocked(request);
        DiagnosticsLogger.event(activity, "WARN", "webview", "blob_download_stream_aborted", null);
    }

    private void launchPicker(Pending request) {
        synchronized (this) {
            if (pending != request) return;
        }
        if (activity.isFinishing()) {
            complete(request, false);
            return;
        }
        Intent picker = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        picker.addCategory(Intent.CATEGORY_OPENABLE);
        picker.setType(request.mimeType);
        picker.putExtra(Intent.EXTRA_TITLE, request.filename);
        picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            activity.startActivityForResult(picker, request.requestCode);
            DiagnosticsLogger.event(activity, "INFO", "webview", "blob_download_picker_started", null);
        } catch (Exception error) {
            DiagnosticsLogger.event(activity, "ERROR", "webview", "blob_download_picker_launch_failed", null);
            Toast.makeText(activity, "시스템 저장 선택기를 열지 못했습니다: " + safeMessage(error),
                    Toast.LENGTH_LONG).show();
            complete(request, false);
        }
    }

    void handleActivityResult(int requestCode, int resultCode, Intent data) {
        final Pending request;
        synchronized (this) {
            request = pending;
            if (request == null || request.requestCode != requestCode) {
                DiagnosticsLogger.event(activity, "WARN", "webview", "blob_download_stale_result", null);
                return;
            }
        }
        if (resultCode != Activity.RESULT_OK) {
            DiagnosticsLogger.event(activity, "WARN", "webview", "blob_download_cancelled", null);
            Toast.makeText(activity, "파일 저장이 취소되었습니다.", Toast.LENGTH_SHORT).show();
            complete(request, false);
            return;
        }
        Uri uri = data == null ? null : data.getData();
        if (uri == null || !"content".equalsIgnoreCase(uri.getScheme())
                || uri.getAuthority() == null || uri.getAuthority().trim().isEmpty()) {
            fail(request, "Android가 유효한 저장 URI를 반환하지 않았습니다.");
            return;
        }
        int grant = activity.checkUriPermission(uri, Process.myPid(), Process.myUid(),
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if (grant != PackageManager.PERMISSION_GRANTED) {
            fail(request, "선택한 저장 위치에 쓰기 권한이 없습니다.");
            return;
        }
        try (InputStream input = new FileInputStream(request.temporaryFile);
             OutputStream output = activity.getContentResolver().openOutputStream(uri, "w")) {
            if (output == null) throw new IllegalStateException("저장 URI를 쓸 수 없습니다.");
            byte[] buffer = new byte[32 * 1024];
            long copied = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
                copied += count;
            }
            output.flush();
            if (copied != request.expectedBytes) throw new IllegalStateException("Export byte count mismatch.");
        } catch (Exception error) {
            DiagnosticsLogger.event(activity, "ERROR", "webview", "blob_download_write_failed", null);
            fail(request, "파일을 저장하지 못했습니다: " + safeMessage(error));
            return;
        }
        DiagnosticsLogger.event(activity, "INFO", "webview", "blob_download_saved", null);
        Toast.makeText(activity, request.filename + " 저장 완료", Toast.LENGTH_LONG).show();
        complete(request, true);
    }

    void cancelPending() {
        final Pending request;
        synchronized (this) { request = pending; }
        if (request != null) {
            DiagnosticsLogger.event(activity, "WARN", "webview", "blob_download_cancelled_activity_destroyed", null);
            complete(request, false);
        }
    }

    private void fail(Pending request, String message) {
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
        complete(request, false);
    }

    private void complete(Pending request, boolean saved) {
        synchronized (this) {
            if (pending != request) return;
            pending = null;
            cleanupLocked(request);
        }
        if (webView == null || activity.isFinishing()) return;
        String script = "if(typeof window.__waywardBlobDownloadResult==='function')"
                + "window.__waywardBlobDownloadResult(" + JSONObject.quote(request.requestId)
                + "," + (saved ? "true" : "false") + ")";
        activity.runOnUiThread(() -> {
            if (!activity.isFinishing()) webView.evaluateJavascript(script, null);
        });
    }

    private void cleanupLocked(Pending request) {
        if (request.stream != null) {
            try { request.stream.close(); } catch (Exception ignored) {}
            request.stream = null;
        }
        if (request.temporaryFile.exists() && !request.temporaryFile.delete()) {
            DiagnosticsLogger.event(activity, "WARN", "webview", "blob_download_temp_cleanup_failed", null);
        }
    }

    private static String safeMessage(Throwable error) {
        return error == null || error.getMessage() == null ? "알 수 없는 오류" : error.getMessage();
    }
}
