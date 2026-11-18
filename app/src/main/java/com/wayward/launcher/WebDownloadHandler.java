package com.wayward.launcher;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Process;
import android.util.Base64;
import android.widget.Toast;

import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicInteger;

/** Saves the game's Blob-backed anchor downloads through Android's system document picker. */
final class WebDownloadHandler {
    private static final int REQUEST_CODE_MIN = 42000;
    private static final int REQUEST_CODE_MAX = 49999;
    private static final int MAX_DOWNLOAD_BYTES = 32 * 1024 * 1024;
    private static final AtomicInteger NEXT_REQUEST_CODE = new AtomicInteger(REQUEST_CODE_MIN);

    private static final class Pending {
        final String requestId;
        final String filename;
        final String mimeType;
        final byte[] bytes;
        final int requestCode;

        Pending(String requestId, String filename, String mimeType, byte[] bytes, int requestCode) {
            this.requestId = requestId;
            this.filename = filename;
            this.mimeType = mimeType;
            this.bytes = bytes;
            this.requestCode = requestCode;
        }
    }

    private final Activity activity;
    private Pending pending;

    WebDownloadHandler(Activity activity) {
        this.activity = activity;
    }

    static boolean isRequestCode(int requestCode) {
        return requestCode >= REQUEST_CODE_MIN && requestCode <= REQUEST_CODE_MAX;
    }

    private static int nextRequestCode() {
        return NEXT_REQUEST_CODE.getAndUpdate(current ->
                current >= REQUEST_CODE_MAX ? REQUEST_CODE_MIN : current + 1);
    }

    void requestSave(String requestId, String filename, String mimeType, String encodedBytes) {
        final byte[] bytes;
        try {
            if (requestId == null || !requestId.matches("[A-Za-z0-9._:-]{1,128}")) {
                throw new IllegalArgumentException("Invalid download request identifier.");
            }
            if (filename == null || filename.trim().isEmpty() || filename.length() > 255
                    || filename.indexOf('/') >= 0 || filename.indexOf('\\') >= 0
                    || filename.indexOf('\0') >= 0 || ".".equals(filename) || "..".equals(filename)) {
                throw new IllegalArgumentException("Invalid suggested download filename.");
            }
            bytes = Base64.decode(encodedBytes == null ? "" : encodedBytes, Base64.DEFAULT);
            if (bytes.length > MAX_DOWNLOAD_BYTES) {
                throw new IllegalArgumentException("The exported file exceeds the 32 MiB Android bridge limit.");
            }
        } catch (Exception error) {
            DiagnosticsLogger.event(activity, "ERROR", "webview", "blob_download_rejected", null);
            activity.runOnUiThread(() -> Toast.makeText(activity,
                    "파일 저장 요청을 처리하지 못했습니다: " + safeMessage(error), Toast.LENGTH_LONG).show());
            return;
        }

        final Pending request = new Pending(requestId, filename,
                mimeType == null || mimeType.trim().isEmpty() ? "application/octet-stream" : mimeType.trim(),
                bytes, nextRequestCode());
        activity.runOnUiThread(() -> {
            synchronized (WebDownloadHandler.this) {
                if (pending != null) {
                    DiagnosticsLogger.event(activity, "WARN", "webview", "blob_download_rejected_busy", null);
                    Toast.makeText(activity, "다른 파일 저장이 진행 중입니다.", Toast.LENGTH_LONG).show();
                    return;
                }
                pending = request;
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
                clear(request);
                DiagnosticsLogger.event(activity, "ERROR", "webview", "blob_download_picker_launch_failed", null);
                Toast.makeText(activity, "시스템 저장 선택기를 열지 못했습니다: " + safeMessage(error),
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    void handleActivityResult(int requestCode, int resultCode, Intent data) {
        final Pending request;
        synchronized (this) {
            request = pending;
            if (request == null || request.requestCode != requestCode) {
                DiagnosticsLogger.event(activity, "WARN", "webview", "blob_download_stale_result", null);
                return;
            }
            pending = null;
        }
        if (resultCode != Activity.RESULT_OK) {
            DiagnosticsLogger.event(activity, "WARN", "webview", "blob_download_cancelled", null);
            Toast.makeText(activity, "파일 저장이 취소되었습니다.", Toast.LENGTH_SHORT).show();
            return;
        }
        Uri uri = data == null ? null : data.getData();
        if (uri == null || !"content".equalsIgnoreCase(uri.getScheme())
                || uri.getAuthority() == null || uri.getAuthority().trim().isEmpty()) {
            fail("Android가 유효한 저장 URI를 반환하지 않았습니다.");
            return;
        }
        int grant = activity.checkUriPermission(uri, Process.myPid(), Process.myUid(),
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if (grant != PackageManager.PERMISSION_GRANTED) {
            fail("선택한 저장 위치에 쓰기 권한이 없습니다.");
            return;
        }
        try (OutputStream output = activity.getContentResolver().openOutputStream(uri, "w")) {
            if (output == null) throw new IllegalStateException("저장 URI를 쓸 수 없습니다.");
            output.write(request.bytes);
            output.flush();
            DiagnosticsLogger.event(activity, "INFO", "webview", "blob_download_saved", null);
            Toast.makeText(activity, request.filename + " 저장 완료", Toast.LENGTH_LONG).show();
        } catch (Exception error) {
            DiagnosticsLogger.event(activity, "ERROR", "webview", "blob_download_write_failed", null);
            fail("파일을 저장하지 못했습니다: " + safeMessage(error));
        }
    }

    void cancelPending() {
        synchronized (this) {
            if (pending == null) return;
            pending = null;
        }
        DiagnosticsLogger.event(activity, "WARN", "webview", "blob_download_cancelled_activity_destroyed", null);
    }

    private synchronized void clear(Pending request) {
        if (pending == request) pending = null;
    }

    private void fail(String message) {
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
    }

    private static String safeMessage(Throwable error) {
        return error == null || error.getMessage() == null ? "알 수 없는 오류" : error.getMessage();
    }
}
