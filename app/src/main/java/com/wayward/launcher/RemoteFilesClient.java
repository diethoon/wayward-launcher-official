package com.wayward.launcher;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;

/**
 * Native Remote Host file APIs. No file operation is exposed to the game JavaScript
 * environment; callers must enforce their own UI confirmation for destructive actions.
 */
public final class RemoteFilesClient {
    private static final int MAX_UPLOAD_CHUNK_BYTES = 1024 * 1024;
    private static final int CONNECTED_RESPONSE_LIMIT = 4 * 1024 * 1024;

    private RemoteFilesClient() {}

    public static JSONArray list(Context context, RemoteHostConnection host,
            String rootId, String relativePath) throws Exception {
        String path = "/v1/files/list?rootId=" + encode(requireRootId(rootId)) +
                "&path=" + encode(normalizeRelativePath(relativePath));
        HttpsURLConnection connection = RemoteHostClient.openAuthenticatedConnection(
                context, host, path, "GET", null);
        try {
            return RemoteHostClient.readJsonArrayResponse(connection);
        } finally {
            connection.disconnect();
        }
    }

    public static FileDownloadResponse openDownload(Context context, RemoteHostConnection host,
            String rootId, String relativePath, Map<String, String> requestHeaders) throws Exception {
        String path = "/v1/files/download?rootId=" + encode(requireRootId(rootId)) +
                "&path=" + encode(normalizeRelativePath(relativePath));
        HttpsURLConnection connection = RemoteHostClient.openAuthenticatedConnection(
                context, host, path, "GET", requestHeaders);
        boolean success = false;
        try {
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK && status != HttpURLConnection.HTTP_PARTIAL) {
                String message = readError(connection);
                throw new IOException("Remote Host file download returned HTTP " + status +
                        (message.isEmpty() ? "" : ": " + message));
            }
            String checksum = connection.getHeaderField("X-Wayward-Content-SHA256");
            if (checksum == null || !checksum.matches("(?i)[0-9a-f]{64}")) {
                throw new SecurityException("Remote Host did not provide a valid file SHA-256.");
            }
            FileDownloadResponse response = new FileDownloadResponse(
                    connection, connection.getInputStream(), status, checksum.toUpperCase(Locale.ROOT),
                    connection.getContentLengthLong(), connection.getContentType(),
                    connection.getHeaderField("Content-Range"));
            success = true;
            return response;
        } finally {
            if (!success) connection.disconnect();
        }
    }

    public static JSONObject beginUpload(Context context, RemoteHostConnection host,
            String rootId, String relativePath, long totalBytes, String sha256,
            String expectedExistingSha256) throws Exception {
        String normalizedHash = normalizeSha256(sha256);
        if (totalBytes <= 0) throw new IllegalArgumentException("Upload size must be positive.");
        String expected = expectedExistingSha256 == null || expectedExistingSha256.trim().isEmpty()
                ? null : normalizeSha256(expectedExistingSha256);
        JSONObject request = new JSONObject()
                .put("rootId", requireRootId(rootId))
                .put("relativePath", normalizeRelativePath(relativePath))
                .put("totalBytes", totalBytes)
                .put("sha256", normalizedHash);
        if (expected != null) request.put("expectedExistingSha256", expected);
        return RemoteHostClient.requestAuthenticatedJson(context, host,
                "/v1/files/uploads", "POST", request);
    }

    public static JSONObject getUploadProgress(Context context, RemoteHostConnection host,
            String uploadId) throws Exception {
        String id = validateUploadId(uploadId);
        return RemoteHostClient.requestAuthenticatedJson(context, host,
                "/v1/files/uploads/" + id, "GET", null);
    }

    /**
     * Appends one byte[] chunk using the Host's authoritative sequential Upload-Offset.
     * The caller should persist the uploadId locally and query progress after reconnect.
     */
    public static JSONObject appendUploadChunk(Context context, RemoteHostConnection host,
            String uploadId, long offset, byte[] chunk, int maximumChunkBytes) throws Exception {
        String id = validateUploadId(uploadId);
        if (chunk == null || chunk.length == 0) throw new IllegalArgumentException("Upload chunk is empty.");
        int limit = Math.min(MAX_UPLOAD_CHUNK_BYTES, maximumChunkBytes > 0
                ? maximumChunkBytes : MAX_UPLOAD_CHUNK_BYTES);
        if (chunk.length > limit) throw new IllegalArgumentException("Upload chunk exceeds the Host-advertised chunk limit.");
        if (offset < 0) throw new IllegalArgumentException("Upload offset cannot be negative.");

        String path = "/v1/files/uploads/" + id + "/chunks";
        Map<String, String> headers = new HashMap<>();
        headers.put("Upload-Offset", Long.toString(offset));
        try {
            return sendChunk(context, host, path, headers, chunk);
        } catch (IOException error) {
            // A session may expire between chunk preparation and transmission. Since
            // this chunk is held in memory (maximum 1 MiB), renew and replay the same
            // exact offset/content once; the server rejects duplicate/non-sequential data.
            if (error.getMessage() == null || !error.getMessage().contains("HTTP 401")) throw error;
            RemoteHostClient.clearSession();
            return sendChunk(context, host, path, headers, chunk);
        }
    }

    private static JSONObject sendChunk(Context context, RemoteHostConnection host,
            String path, Map<String, String> headers, byte[] chunk) throws Exception {
        HttpsURLConnection connection = RemoteHostClient.openAuthenticatedUpload(
                context, host, path, "application/octet-stream", chunk.length,
                new ByteArrayInputStream(chunk), headers);
        try {
            return RemoteHostClient.readJsonResponse(connection);
        } finally {
            connection.disconnect();
        }
    }

    public static JSONObject completeUpload(Context context, RemoteHostConnection host,
            String uploadId) throws Exception {
        String id = validateUploadId(uploadId);
        return RemoteHostClient.requestAuthenticatedJson(context, host,
                "/v1/files/uploads/" + id + "/complete", "POST", new JSONObject());
    }

    public static void cancelUpload(Context context, RemoteHostConnection host,
            String uploadId) throws Exception {
        String id = validateUploadId(uploadId);
        HttpsURLConnection connection = RemoteHostClient.openAuthenticatedConnection(
                context, host, "/v1/files/uploads/" + id, "DELETE", null);
        try {
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_NO_CONTENT && status != HttpURLConnection.HTTP_OK) {
                throw new IOException("Remote Host upload cancellation returned HTTP " + status +
                        ": " + readError(connection));
            }
        } finally {
            connection.disconnect();
        }
    }

    private static String readError(HttpsURLConnection connection) {
        try {
            InputStream input = connection.getErrorStream();
            if (input == null) return "";
            try (InputStream stream = input; java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
                byte[] buffer = new byte[2048];
                int total = 0;
                int read;
                while ((read = stream.read(buffer)) >= 0) {
                    if (read == 0) continue;
                    int count = Math.min(read, CONNECTED_RESPONSE_LIMIT - total);
                    if (count <= 0) break;
                    output.write(buffer, 0, count);
                    total += count;
                }
                return new String(output.toByteArray(), StandardCharsets.UTF_8)
                        .replace('\r', ' ').replace('\n', ' ').replaceAll("\\s+", " ").trim();
            }
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String requireRootId(String rootId) {
        if (rootId == null || !rootId.matches("(?i)[0-9a-f]{16}")) {
            throw new IllegalArgumentException("Workspace root ID must be a 16-character hexadecimal ID.");
        }
        return rootId.toLowerCase(Locale.ROOT);
    }

    private static String normalizeRelativePath(String path) {
        if (path == null || path.trim().isEmpty()) return "";
        String value = path.replace('\\', '/').trim();
        if (value.startsWith("/") || value.length() > 4096 || value.split("/", -1).length > 128) {
            throw new IllegalArgumentException("Workspace path must be a bounded relative path.");
        }
        for (String segment : value.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..") ||
                    segment.indexOf(':') >= 0 || segment.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("Workspace path contains an unsafe segment.");
            }
        }
        return value;
    }

    private static String normalizeSha256(String value) {
        if (value == null) throw new IllegalArgumentException("SHA-256 is required.");
        String normalized = value.replace(":", "").trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[0-9A-F]{64}")) {
            throw new IllegalArgumentException("SHA-256 must contain exactly 64 hexadecimal characters.");
        }
        return normalized;
    }

    private static String validateUploadId(String value) {
        try {
            java.util.UUID parsed = java.util.UUID.fromString(value);
            if (parsed.equals(new java.util.UUID(0L, 0L))) throw new IllegalArgumentException();
            return parsed.toString();
        } catch (Exception error) {
            throw new IllegalArgumentException("Upload ID must be a non-empty UUID.", error);
        }
    }

    private static String encode(String value) throws Exception {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
    }

    /** Owns the HTTPS response and stream. Close it after consuming/canceling the download. */
    public static final class FileDownloadResponse implements AutoCloseable {
        private final HttpsURLConnection connection;
        private final int statusCode;
        private final String sha256;
        private final long contentLength;
        private final String contentType;
        private final String contentRange;
        private InputStream stream;

        private FileDownloadResponse(HttpsURLConnection connection, InputStream stream,
                int statusCode, String sha256, long contentLength, String contentType,
                String contentRange) {
            this.connection = connection;
            this.stream = stream;
            this.statusCode = statusCode;
            this.sha256 = sha256;
            this.contentLength = contentLength;
            this.contentType = contentType;
            this.contentRange = contentRange;
        }

        public int getStatusCode() { return statusCode; }
        public String getSha256() { return sha256; }
        public long getContentLength() { return contentLength; }
        public String getContentType() { return contentType; }
        public String getContentRange() { return contentRange; }

        public synchronized InputStream getStream() throws IOException {
            if (stream == null) throw new IOException("Remote file download is already closed.");
            return stream;
        }

        @Override public synchronized void close() {
            if (stream != null) {
                try { stream.close(); } catch (IOException ignored) { }
                stream = null;
            }
            connection.disconnect();
        }
    }
}
