package com.wayward.launcher;

import android.content.Context;

import org.json.JSONObject;

import java.io.IOException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.net.ssl.HttpsURLConnection;

/**
 * Native-only ComfyUI client for the authenticated PC Host gateway.
 *
 * It never connects to the Android or PC ComfyUI port directly. All requests use the
 * QR-pinned Remote Host HTTPS endpoint and therefore inherit the Host-side UseComfyUi
 * permission check. The game WebView never receives the bearer token or Host address.
 */
public final class RemoteComfyClient {
    private static final int MAX_WORKFLOW_BYTES = 4 * 1024 * 1024;
    private static final int MAX_IMAGE_UPLOAD_BYTES = 32 * 1024 * 1024;
    private static final long MAX_IMAGE_DOWNLOAD_BYTES = 128L * 1024 * 1024;
    private static final int MAX_ERROR_BYTES = 16 * 1024;
    private static final long MAX_COMFY_CACHE_BYTES = 256L * 1024 * 1024;
    private static final ConcurrentHashMap<String, Object> CACHE_LOCKS = new ConcurrentHashMap<>();

    private RemoteComfyClient() {}

    public static JSONObject getStatus(Context context, RemoteHostConnection host) throws Exception {
        return RemoteHostClient.requestAuthenticatedJson(context, host, "/v1/comfy/status", "GET", null);
    }

    public static JSONObject getObjectInfo(Context context, RemoteHostConnection host) throws Exception {
        return RemoteHostClient.requestAuthenticatedJson(context, host, "/v1/comfy/object-info", "GET", null);
    }

    public static JSONObject getQueue(Context context, RemoteHostConnection host) throws Exception {
        return RemoteHostClient.requestAuthenticatedJson(context, host, "/v1/comfy/queue", "GET", null);
    }

    public static JSONObject submitWorkflow(Context context, RemoteHostConnection host,
            JSONObject workflow) throws Exception {
        if (workflow == null || workflow.length() == 0) {
            throw new IllegalArgumentException("A non-empty ComfyUI workflow object is required.");
        }
        byte[] encoded = workflow.toString().getBytes(StandardCharsets.UTF_8);
        try {
            if (encoded.length > MAX_WORKFLOW_BYTES) {
                throw new IllegalArgumentException("ComfyUI workflow exceeds the 4 MiB limit.");
            }
        } finally {
            java.util.Arrays.fill(encoded, (byte) 0);
        }
        JSONObject request = new JSONObject().put("workflow", workflow);
        return RemoteHostClient.requestAuthenticatedJson(context, host, "/v1/comfy/jobs", "POST", request);
    }

    public static JSONObject getJob(Context context, RemoteHostConnection host, String promptId)
            throws Exception {
        String id = validatePromptId(promptId);
        return RemoteHostClient.requestAuthenticatedJson(context, host,
                "/v1/comfy/jobs/" + URLEncoder.encode(id, StandardCharsets.UTF_8.name()), "GET", null);
    }

    public static JSONObject cancelJob(Context context, RemoteHostConnection host, String promptId)
            throws Exception {
        String id = validatePromptId(promptId);
        return RemoteHostClient.requestAuthenticatedJson(context, host,
                "/v1/comfy/jobs/" + URLEncoder.encode(id, StandardCharsets.UTF_8.name()) + "/cancel",
                "POST", new JSONObject());
    }

    public static JSONObject uploadInputImage(Context context, RemoteHostConnection host,
            String fileName, String contentType, long contentLength, InputStream image) throws Exception {
        String safeName = validateFileName(fileName);
        String safeType = validateImageContentType(contentType);
        if (contentLength <= 0 || contentLength > MAX_IMAGE_UPLOAD_BYTES) {
            throw new IllegalArgumentException("ComfyUI input images must be between 1 and 32 MiB.");
        }
        String path = "/v1/comfy/images?fileName=" +
                URLEncoder.encode(safeName, StandardCharsets.UTF_8.name());
        HttpsURLConnection connection = RemoteHostClient.openAuthenticatedUpload(
                context, host, path, safeType, contentLength, image);
        try {
            return RemoteHostClient.readJsonResponse(connection);
        } finally {
            connection.disconnect();
        }
    }

    public static ImageResponse openImage(Context context, RemoteHostConnection host,
            String fileName, String subfolder, String type) throws Exception {
        String safeName = validateFileName(fileName);
        String safeSubfolder = validateSubfolder(subfolder);
        String safeType = validateImageType(type);
        String path = "/v1/comfy/images?fileName=" +
                URLEncoder.encode(safeName, StandardCharsets.UTF_8.name()) +
                "&subfolder=" + URLEncoder.encode(safeSubfolder, StandardCharsets.UTF_8.name()) +
                "&type=" + URLEncoder.encode(safeType, StandardCharsets.UTF_8.name());

        Map<String, String> headers = new HashMap<>();
        headers.put("Accept", "image/*, application/octet-stream;q=0.8");
        HttpsURLConnection connection = RemoteHostClient.openAuthenticatedConnection(
                context, host, path, "GET", headers);
        boolean success = false;
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                String detail = readLimitedError(connection, MAX_ERROR_BYTES);
                throw new IOException("PC ComfyUI image request returned HTTP " + status +
                        (detail.isEmpty() ? "" : ": " + detail));
            }
            String contentType = connection.getContentType();
            if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("image/")) {
                throw new IOException("PC ComfyUI returned a non-image response.");
            }
            long contentLength = connection.getContentLengthLong();
            if (contentLength > MAX_IMAGE_DOWNLOAD_BYTES) {
                throw new IOException("PC ComfyUI image exceeds the 128 MiB download limit.");
            }
            ImageResponse response = new ImageResponse(connection, contentType, contentLength);
            success = true;
            return response;
        } finally {
            if (!success) connection.disconnect();
        }
    }

    /**
     * Downloads a generated ComfyUI image once into app-private cache storage.
     * promptId is part of the key so a later generation cannot reuse an older result
     * that happens to have the same output file name.
     */
    public static File getCachedImage(Context context, RemoteHostConnection host, String promptId,
            String fileName, String subfolder, String type) throws Exception {
        if (context == null) throw new IllegalArgumentException("Android Context is required.");
        String id = validatePromptId(promptId);
        String safeName = validateFileName(fileName);
        String safeSubfolder = validateSubfolder(subfolder);
        String safeType = validateImageType(type);
        String key = cacheKey(host.hostId, id, safeName, safeSubfolder, safeType);
        File directory = new File(context.getApplicationContext().getCacheDir(), "wayward-remote-comfy-v1");
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("Could not create the app-private ComfyUI image cache.");
        }
        File target = new File(directory, key + ".image");
        Object lock = CACHE_LOCKS.computeIfAbsent(key, ignored -> new Object());
        synchronized (lock) {
            if (target.isFile() && target.length() > 0 && target.length() <= MAX_IMAGE_DOWNLOAD_BYTES) {
                target.setLastModified(System.currentTimeMillis());
                return target;
            }

            File temporary = new File(directory, key + "." + android.os.Process.myPid() + ".part");
            long downloaded = 0;
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            try (ImageResponse response = openImage(context, host, safeName, safeSubfolder, safeType);
                 InputStream input = response.getStream();
                 FileOutputStream output = new FileOutputStream(temporary, false)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (read == 0) continue;
                    downloaded += read;
                    if (downloaded > MAX_IMAGE_DOWNLOAD_BYTES) {
                        throw new IOException("Generated ComfyUI image exceeds the 128 MiB download limit.");
                    }
                    digest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                }
                output.flush();
                output.getFD().sync();
                if (response.getContentLength() >= 0 && downloaded != response.getContentLength()) {
                    throw new IOException("Generated ComfyUI image length did not match Content-Length.");
                }
                if (downloaded <= 0) throw new IOException("Generated ComfyUI image response was empty.");
                output.close();

                delete(target);
                if (!temporary.renameTo(target)) throw new IOException("Could not finalize ComfyUI image cache file.");
                target.setLastModified(System.currentTimeMillis());
                String checksum = hex(digest.digest());
                File checksumFile = new File(directory, key + ".sha256");
                File checksumTemp = new File(directory, key + ".sha256.part");
                try (FileOutputStream checksumOutput = new FileOutputStream(checksumTemp, false)) {
                    checksumOutput.write(checksum.getBytes(StandardCharsets.US_ASCII));
                    checksumOutput.write('\\n');
                    checksumOutput.getFD().sync();
                }
                delete(checksumFile);
                if (!checksumTemp.renameTo(checksumFile)) delete(checksumTemp);
                purgeComfyCache(directory, target);
                return target;
            } catch (Exception error) {
                delete(temporary);
                throw error;
            }
        }
    }

    private static String cacheKey(String hostId, String promptId, String fileName,
            String subfolder, String type) throws Exception {
        String value = hostId + "\\n" + promptId + "\\n" + fileName + "\\n" + subfolder + "\\n" + type;
        byte[] bytes = java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        try { return hex(bytes); }
        finally { java.util.Arrays.fill(bytes, (byte) 0); }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return result.toString();
    }

    private static void purgeComfyCache(File directory, File keep) {
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".image"));
        if (files == null) return;
        long total = 0;
        for (File file : files) total += file.length();
        java.util.Arrays.sort(files, java.util.Comparator.comparingLong(File::lastModified));
        for (File file : files) {
            if (total <= MAX_COMFY_CACHE_BYTES) break;
            if (file.equals(keep)) continue;
            long size = file.length();
            if (file.delete()) {
                total -= size;
                String base = file.getName().substring(0, file.getName().length() - ".image".length());
                delete(new File(directory, base + ".sha256"));
            }
        }
    }

    private static void delete(File file) {
        if (file != null && file.exists()) {
            try { file.delete(); } catch (SecurityException ignored) { }
        }
    }

    private static String readLimitedError(HttpsURLConnection connection, int maximumBytes) {
        InputStream source = connection.getErrorStream();
        if (source == null) return "";
        try (InputStream input = source; java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[2048];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                int accepted = Math.min(read, maximumBytes - total);
                if (accepted <= 0) break;
                output.write(buffer, 0, accepted);
                total += accepted;
                if (total >= maximumBytes) break;
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8)
                    .replace('\r', ' ').replace('\n', ' ').replaceAll("\\s+", " ").trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String validatePromptId(String promptId) {
        try {
            java.util.UUID parsed = java.util.UUID.fromString(promptId);
            if (parsed.equals(new java.util.UUID(0L, 0L))) throw new IllegalArgumentException();
            return parsed.toString();
        } catch (Exception error) {
            throw new IllegalArgumentException("ComfyUI prompt ID must be a non-empty UUID.", error);
        }
    }

    private static String validateFileName(String fileName) {
        if (fileName == null || fileName.trim().isEmpty()) {
            throw new IllegalArgumentException("ComfyUI image file name is required.");
        }
        String value = fileName.trim();
        if (value.length() > 255 || value.equals(".") || value.equals("..") ||
                value.indexOf('/') >= 0 || value.indexOf('\\') >= 0 ||
                value.indexOf(':') >= 0 || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("ComfyUI accepts a file name only, not a file path.");
        }
        return value;
    }

    private static String validateSubfolder(String subfolder) {
        if (subfolder == null || subfolder.trim().isEmpty()) return "";
        String value = subfolder.replace('\\', '/').trim();
        if (value.startsWith("/") || value.split("/", -1).length > 16) {
            throw new IllegalArgumentException("ComfyUI subfolder must be a short relative path.");
        }
        for (String segment : value.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..") ||
                    segment.indexOf(':') >= 0 || segment.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("ComfyUI subfolder contains an unsafe path segment.");
            }
        }
        return value;
    }

    private static String validateImageType(String type) {
        if ("input".equals(type) || "output".equals(type) || "temp".equals(type)) return type;
        throw new IllegalArgumentException("ComfyUI image type must be input, output, or temp.");
    }

    private static String validateImageContentType(String type) {
        if (type == null) throw new IllegalArgumentException("Image content type is required.");
        String normalized = type.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if ("image/png".equals(normalized) || "image/jpeg".equals(normalized) ||
                "image/webp".equals(normalized) || "image/gif".equals(normalized)) return normalized;
        throw new IllegalArgumentException("Only PNG, JPEG, WebP and GIF inputs are accepted.");
    }

    /** Owns its HTTPS connection; close the response after streaming is complete. */
    public static final class ImageResponse implements AutoCloseable {
        private final HttpsURLConnection connection;
        private final String contentType;
        private final long contentLength;
        private InputStream stream;

        private ImageResponse(HttpsURLConnection connection, String contentType, long contentLength)
                throws IOException {
            this.connection = connection;
            this.contentType = contentType;
            this.contentLength = contentLength;
            this.stream = connection.getInputStream();
        }

        public String getContentType() { return contentType; }
        public long getContentLength() { return contentLength; }

        public synchronized InputStream getStream() throws IOException {
            if (stream == null) throw new IOException("ComfyUI image response is already closed.");
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
