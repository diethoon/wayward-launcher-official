package com.wayward.launcher;

import android.content.Context;
import android.net.Uri;
import android.os.Build;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Durable, privacy-filtered journal for one image-pack picker request end-to-end. */
final class ImagePackDiagnostics {
    private static final long MAX_FILE_BYTES = 512L * 1024L;
    private static final String ACTIVE = "imagepack.ndjson";
    private static final String PREVIOUS = "imagepack.previous.ndjson";
    private static final Object LOCK = new Object();
    private static final Set<String> ALLOWED = new HashSet<>(Arrays.asList(
            "origin", "frameOrigin", "topOrigin", "isTopFrame", "mode", "result",
            "resultCode", "resultFlags", "readGranted", "writeGranted", "persistableGranted",
            "persistedRead", "persistedWrite", "uriScheme", "uriAuthority", "permissionGranted",
            "permissionAction", "documentStartSupported", "registered", "installed", "bridgeAvailable",
            "directoryHandle", "handleKind", "entryCount", "fileSize", "method", "exception",
            "stack", "durationMs", "timedOut", "dispatchCount", "sameOriginFrameCount",
            "errorName", "errorMessage", "apiLevel", "pagePath", "writable", "mainFrame",
            "webViewAlive", "scriptBytes", "pendingRequest", "frameDepth", "frameCount",
            "acceptTypes", "clipItemCount", "selectedCount", "dataPresent",
            "permissionBefore", "permissionAfter", "provider", "cancelled", "ok", "stage", "errorCode"
    ));
    private static final Pattern BEARER = Pattern.compile("(?i)(Bearer\\s+)[A-Za-z0-9._~+/-]+=*");
    private static final Pattern SECRET = Pattern.compile("(?i)((?:access|refresh)?_?(?:token|api[_-]?key|secret|password|cookie)\\s*[:=]\\s*[\\\"']?)[^\\s,;\\\"'}&]+");
    private static final Pattern ABSOLUTE_PATH = Pattern.compile(
            "(?i)(?:/storage/emulated/\\d+|/sdcard|/mnt/user/\\d+/primary"
                    + "|/data/(?:user(?:_de)?/\\d+|data)|/data/local/tmp)/[^\\s,;\\\"']+");
    private static final Pattern CONTENT_URI = Pattern.compile("content://[^\\s,;\\\"']+");

    private ImagePackDiagnostics() {}

    static String newSessionId() {
        return UUID.randomUUID().toString();
    }

    static void event(Context context, String sessionId, String requestId,
                      String stage, String status, JSONObject details) {
        if (context == null) return;
        try {
            JSONObject item = new JSONObject();
            item.put("timestamp", System.currentTimeMillis());
            item.put("sessionId", safeId(sessionId));
            item.put("requestId", safeId(requestId));
            item.put("stage", safeStage(stage));
            item.put("status", safeStage(status).toLowerCase(java.util.Locale.ROOT));
            item.put("sdk", Build.VERSION.SDK_INT);
            item.put("details", filter(details));
            append(context.getApplicationContext(), item.toString() + "\n");
        } catch (Throwable ignored) {
            // Diagnostics are best-effort and must never break a game session.
        }
    }

    static void javascriptEvent(Context context, String sessionId, String requestId,
                                 String stage, String detailsJson) {
        JSONObject details = new JSONObject();
        try {
            if (detailsJson != null && detailsJson.length() <= 8192) {
                JSONObject supplied = new JSONObject(detailsJson);
                details = filter(supplied);
            }
        } catch (Exception ignored) {
            try { details.put("result", "invalid_details"); } catch (Exception ignoredAgain) {}
        }
        String normalized = stage == null ? "" : stage.toLowerCase(java.util.Locale.ROOT);
        String status = normalized.contains("failed") || normalized.contains("failure")
                || normalized.contains("timeout") || normalized.contains("timed_out")
                ? "error"
                : normalized.contains("cancelled") || normalized.contains("rejected")
                    ? "warn" : "info";
        event(context, sessionId, requestId, stage, status, details);
    }

    static void error(Context context, String sessionId, String requestId,
                      String stage, Throwable error) {
        JSONObject details = new JSONObject();
        try {
            details.put("exception", error == null ? "Unknown" : error.getClass().getSimpleName());
            if (error != null) {
                java.io.StringWriter buffer = new java.io.StringWriter();
                error.printStackTrace(new java.io.PrintWriter(buffer));
                details.put("stack", safeText(buffer.toString(), 3600));
            }
        } catch (Exception ignored) {}
        event(context, sessionId, requestId, stage, "error", details);
    }

    static JSONObject details(Object... values) {
        JSONObject result = new JSONObject();
        for (int i = 0; i + 1 < values.length; i += 2) {
            try { result.put(String.valueOf(values[i]), values[i + 1]); }
            catch (Exception ignored) {}
        }
        return result;
    }

    static JSONObject pageDetails(String url, boolean mainFrame) {
        JSONObject details = new JSONObject();
        try {
            Uri parsed = Uri.parse(url == null ? "" : url);
            String scheme = parsed.getScheme();
            String authority = parsed.getEncodedAuthority();
            String origin = scheme == null || authority == null ? "" : scheme + "://" + authority;
            details.put("origin", safeText(origin, 240));
            details.put("pagePath", safeText(parsed.getPath(), 240));
            details.put("mainFrame", mainFrame);
        } catch (Exception ignored) {}
        return details;
    }

    private static JSONObject filter(JSONObject supplied) {
        JSONObject safe = new JSONObject();
        if (supplied == null) return safe;
        java.util.Iterator<String> keys = supplied.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!ALLOWED.contains(key)) continue;
            Object value = supplied.opt(key);
            try {
                if (value instanceof Boolean || value instanceof Integer || value instanceof Long
                        || value instanceof Double) {
                    safe.put(key, value);
                } else if (value instanceof String) {
                    safe.put(key, safeText((String) value, key.equals("stack") ? 3600 : 500));
                }
            } catch (Exception ignored) {}
        }
        return safe;
    }

    private static String safeText(String value, int max) {
        if (value == null) return "";
        String safe = BEARER.matcher(value).replaceAll("$1[REDACTED]");
        safe = SECRET.matcher(safe).replaceAll("$1[REDACTED]");
        safe = ABSOLUTE_PATH.matcher(safe).replaceAll("[PATH]");
        safe = CONTENT_URI.matcher(safe).replaceAll("[URI]");
        safe = safe.replace('\n', ' ').replace('\r', ' ');
        return safe.length() > max ? safe.substring(0, max) : safe;
    }

    private static String safeId(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._:-]{0,128}")) return "";
        return value;
    }

    private static String safeStage(String value) {
        if (value == null) return "unknown";
        String safe = value.replaceAll("[^A-Za-z0-9._-]", "_");
        return safe.length() > 80 ? safe.substring(0, 80) : safe;
    }

    static File directory(Context context) {
        return new File(context.getFilesDir(), "diagnostics");
    }

    private static void append(Context context, String line) throws Exception {
        byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
        synchronized (LOCK) {
            File dir = directory(context);
            if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("diagnostics directory unavailable");
            File lockFile = new File(dir, "imagepack.lock");
            try (RandomAccessFile raf = new RandomAccessFile(lockFile, "rw");
                 FileChannel channel = raf.getChannel();
                 FileLock ignored = channel.lock()) {
                File active = new File(dir, ACTIVE);
                File previous = new File(dir, PREVIOUS);
                if (active.isFile() && active.length() + bytes.length > MAX_FILE_BYTES) {
                    if (previous.exists() && !previous.delete()) {
                        throw new IllegalStateException("previous image-pack log could not be rotated");
                    }
                    Files.move(active.toPath(), previous.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                try (FileOutputStream output = new FileOutputStream(active, true)) {
                    output.write(bytes);
                    output.flush();
                    output.getFD().sync();
                }
            }
        }
    }
}
