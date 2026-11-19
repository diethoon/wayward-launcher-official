package com.wayward.launcher;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Uploads only a ZIP explicitly created after the user taps the diagnostics button. */
final class DiagnosticsBundleUploader {
    private static final String BASE_URL =
            "https://wayward-diagnostics-ingest.trpgengine.workers.dev";
    private static final long MAX_BUNDLE_BYTES = 5L * 1024L * 1024L;
    private static final String PENDING_SUFFIX = ".upload-pending";
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int TICKET_READ_TIMEOUT_MS = 15000;
    private static final int UPLOAD_READ_TIMEOUT_MS = 60000;
    private static final ExecutorService WORKER =
            Executors.newSingleThreadExecutor(r -> new Thread(r, "Wayward-Diagnostics-Zip-Upload"));
    private static final AtomicBoolean BUSY = new AtomicBoolean(false);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    interface Callback {
        void onResult(boolean success, String result);
    }

    private DiagnosticsBundleUploader() {}

    static boolean isUploading() {
        return BUSY.get();
    }

    static File findPendingArchive(Context context) {
        File directory = new File(context.getCacheDir(), "diagnostics");
        File[] markers = directory.listFiles((dir, name) -> name.endsWith(PENDING_SUFFIX));
        if (markers == null || markers.length == 0) return null;
        java.util.Arrays.sort(markers, (left, right) ->
                Long.compare(right.lastModified(), left.lastModified()));
        for (File marker : markers) {
            String archiveName = marker.getName().substring(
                    0, marker.getName().length() - PENDING_SUFFIX.length());
            File archive = new File(directory, archiveName);
            if (archive.isFile() && archive.length() > 0L
                    && archive.length() <= MAX_BUNDLE_BYTES) return archive;
            marker.delete();
        }
        return null;
    }

    static void rememberPendingArchive(File archive) {
        if (archive == null || !archive.isFile()) return;
        File marker = new File(archive.getParentFile(), archive.getName() + PENDING_SUFFIX);
        try (FileOutputStream output = new FileOutputStream(marker, false)) {
            output.write("retry".getBytes(StandardCharsets.US_ASCII));
            output.flush();
            output.getFD().sync();
        } catch (Exception ignored) {
            // The ZIP itself remains available for manual sharing even if marker I/O fails.
        }
    }

    static void clearPendingArchive(File archive) {
        if (archive == null) return;
        File marker = new File(archive.getParentFile(), archive.getName() + PENDING_SUFFIX);
        if (marker.isFile()) marker.delete();
    }

    static void upload(Context context, File archive, Callback callback) {
        Context app = context.getApplicationContext();
        if (!BUSY.compareAndSet(false, true)) {
            deliver(callback, false, "already_uploading");
            return;
        }
        WORKER.execute(() -> {
            boolean success = false;
            String result = "upload_failed";
            try {
                result = uploadBlocking(app, archive);
                success = true;
            } catch (UploadException error) {
                result = error.safeCode;
            } catch (Exception error) {
                result = "network_or_io_error";
            } finally {
                if (success) clearPendingArchive(archive);
                else rememberPendingArchive(archive);
                BUSY.set(false);
                deliver(callback, success, result);
            }
        });
    }

    private static String uploadBlocking(Context context, File archive) throws Exception {
        if (archive == null || !archive.isFile()) throw new UploadException("zip_missing");
        long size = archive.length();
        if (size < 1L || size > MAX_BUNDLE_BYTES) throw new UploadException("zip_size_limit");
        String sha256 = sha256(archive);
        String installId = DiagnosticsLogger.deviceId(context).replace("-", "").toLowerCase(Locale.ROOT);
        if (!installId.matches("[a-f0-9]{32}")) throw new UploadException("install_id_invalid");

        JSONObject request = new JSONObject();
        request.put("installId", installId);
        request.put("sha256", sha256);
        request.put("byteLength", size);
        HttpResponse ticketResponse = postJson(BASE_URL + "/v1/ticket", request);
        if (ticketResponse.status < 200 || ticketResponse.status >= 300) {
            throw new UploadException("ticket_http_" + ticketResponse.status + "_" + responseCode(ticketResponse.body));
        }
        JSONObject ticketJson = new JSONObject(ticketResponse.body);
        String ticket = ticketJson.optString("ticket", "");
        String uploadPath = ticketJson.optString("uploadUrl", "");
        long serverLimit = ticketJson.optLong("maxBytes", MAX_BUNDLE_BYTES);
        if (ticket.isEmpty() || !"/v1/upload".equals(uploadPath)
                || serverLimit < size || serverLimit > MAX_BUNDLE_BYTES) {
            throw new UploadException("ticket_response_invalid");
        }

        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(BASE_URL + uploadPath).openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(UPLOAD_READ_TIMEOUT_MS);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Content-Type", "application/zip");
            connection.setRequestProperty("Authorization", "Bearer " + ticket);
            connection.setRequestProperty("X-Wayward-Version", appVersion(context));
            connection.setFixedLengthStreamingMode(size);
            try (InputStream input = new FileInputStream(archive);
                 OutputStream output = connection.getOutputStream()) {
                byte[] buffer = new byte[8192];
                long copied = 0L;
                int count;
                while ((count = input.read(buffer)) != -1) {
                    copied += count;
                    if (copied > size) throw new UploadException("zip_changed_during_upload");
                    output.write(buffer, 0, count);
                }
                if (copied != size) throw new UploadException("zip_truncated_during_upload");
                output.flush();
            }
            int status = connection.getResponseCode();
            String body = readResponse(connection, status);
            if (status < 200 || status >= 300) {
                throw new UploadException("upload_http_" + status + "_" + responseCode(body));
            }
            JSONObject response = new JSONObject(body);
            if (!response.optBoolean("ok", false)
                    || response.optString("submissionId", "").isEmpty()) {
                throw new UploadException("upload_response_invalid");
            }
            String submissionId = response.optString("submissionId", "");
            String result = response.optBoolean("duplicate", false) ? "uploaded_duplicate" : "uploaded";
            return result + ":" + submissionId;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static HttpResponse postJson(String url, JSONObject payload) throws Exception {
        HttpURLConnection connection = null;
        try {
            byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(TICKET_READ_TIMEOUT_MS);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(bytes);
                output.flush();
            }
            int status = connection.getResponseCode();
            return new HttpResponse(status, readResponse(connection, status));
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[16384];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte value : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return hex.toString();
    }

    private static String readResponse(HttpURLConnection connection, int status) throws Exception {
        InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if (input == null) return "";
        try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[2048];
            int count;
            int total = 0;
            while ((count = source.read(buffer)) != -1 && total < 8192) {
                int allowed = Math.min(count, 8192 - total);
                output.write(buffer, 0, allowed);
                total += allowed;
                if (allowed < count) break;
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static String appVersion(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return info.versionName == null ? "unknown" : info.versionName;
        } catch (Exception ignored) {
            return "unknown";
        }
    }

    private static String responseCode(String body) {
        try {
            String error = new JSONObject(body).optString("error", "server_error");
            return error.replaceAll("[^A-Za-z0-9_-]", "");
        } catch (Exception ignored) {
            return "server_error";
        }
    }

    private static void deliver(Callback callback, boolean success, String result) {
        if (callback != null) MAIN.post(() -> callback.onResult(success, result));
    }

    private static final class HttpResponse {
        final int status;
        final String body;
        HttpResponse(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    private static final class UploadException extends Exception {
        final String safeCode;
        UploadException(String code) {
            super(code);
            safeCode = code;
        }
    }
}
