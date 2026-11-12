package com.wayward.launcher;

import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.os.Process;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.regex.Pattern;

/** Bounded, redacted local-only NDJSON journal for launcher diagnostics. */
final class DiagnosticsLogger {
    private static final long MAX_FILE_BYTES = 512L * 1024L;
    private static final String ACTIVE = "launcher.ndjson";
    private static final String PREVIOUS = "launcher.previous.ndjson";
    private static final Object LOCK = new Object();
    private static final Pattern BEARER = Pattern.compile("(?i)(Bearer\\s+)[A-Za-z0-9._~+/-]+=*");
    private static final Pattern SECRET = Pattern.compile("(?i)((?:access|refresh)?_?(?:token|api[_-]?key|secret|password|cookie)\\s*[:=]\\s*[\\\"']?)[^\\s,;\\\"'}&]+");
    private static final Pattern QUERY_SECRET = Pattern.compile("(?i)([?&](?:token|key|secret|password)=)[^&\\s]+");

    private DiagnosticsLogger() {}

    static void event(Context context, String level, String component, String name, JSONObject details) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        try {
            JSONObject event = new JSONObject();
            event.put("id", UUID.randomUUID().toString());
            event.put("timestamp", System.currentTimeMillis());
            event.put("level", clean(level, 12));
            event.put("component", clean(component, 48));
            event.put("event", clean(name, 80));
            event.put("appVersion", appVersion(app));
            event.put("sdk", Build.VERSION.SDK_INT);
            event.put("model", clean(Build.MANUFACTURER + " " + Build.MODEL, 80));
            event.put("process", clean(Application.getProcessName(), 80));
            event.put("pid", Process.myPid());
            String safeDetails = details == null ? "" : redact(details.toString());
            event.put("details", safeDetails.substring(0, Math.min(4200, safeDetails.length())));
            boolean urgent = "ERROR".equalsIgnoreCase(level) || "WARN".equalsIgnoreCase(level);
            append(app, redact(event.toString()) + "\n", urgent);
        } catch (Throwable ignored) {
            // Diagnostics must never crash or block the launcher.
        }
    }

    static void error(Context context, String component, String name, Throwable error) {
        JSONObject details = new JSONObject();
        try {
            details.put("exception", error == null ? "Unknown" : error.getClass().getName());
            if (error != null) {
                java.io.StringWriter buffer = new java.io.StringWriter();
                error.printStackTrace(new java.io.PrintWriter(buffer));
                String stack = redact(buffer.toString());
                details.put("stack", stack.substring(0, Math.min(3600, stack.length())));
            }
        } catch (Exception ignored) {}
        event(context, "ERROR", component, name, details);
    }

    /**
     * Returns a bounded local diagnostic snapshot for explicit user sharing.
     * The journal is already redacted at write time; no auth token is stored.
     */
    static String snapshot(Context context) {
        if (context == null) return "Wayward diagnostics unavailable.";
        StringBuilder report = new StringBuilder();
        File dir = new File(context.getFilesDir(), "diagnostics");
        String[] names = { PREVIOUS, ACTIVE };
        for (String name : names) {
            File file = new File(dir, name);
            if (!file.isFile()) continue;
            try {
                byte[] bytes = Files.readAllBytes(file.toPath());
                if (bytes.length == 0) continue;
                if (report.length() > 0) report.append('\n');
                report.append("=== ").append(name).append(" ===\n");
                report.append(new String(bytes, StandardCharsets.UTF_8));
            } catch (Throwable error) {
                report.append("[diagnostic read failed: ")
                      .append(error.getClass().getSimpleName()).append("]\n");
            }
        }
        if (report.length() == 0) return "Wayward diagnostics: no events recorded yet.";
        int limit = 240 * 1024;
        if (report.length() > limit) {
            return "[Earlier log lines omitted; latest 240 KiB follow.]\n"
                    + report.substring(report.length() - limit);
        }
        return report.toString();
    }

    private interface LockedAction { void run() throws Exception; }

    private static void withFileLock(Context context, LockedAction action) throws Exception {
        File dir = new File(context.getFilesDir(), "diagnostics");
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("diagnostics directory unavailable");
        File lockFile = new File(dir, "journal.lock");
        try (RandomAccessFile raf = new RandomAccessFile(lockFile, "rw");
             FileChannel channel = raf.getChannel();
             FileLock ignored = channel.lock()) {
            action.run();
        }
    }

    private static void append(Context context, String line, boolean durable) throws Exception {
        byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
        synchronized (LOCK) {
            withFileLock(context, () -> {
                File dir = new File(context.getFilesDir(), "diagnostics");
                File active = new File(dir, ACTIVE);
                File previous = new File(dir, PREVIOUS);
                if (active.isFile() && active.length() + bytes.length > MAX_FILE_BYTES) {
                    if (previous.exists()) previous.delete();
                    if (active.exists()) Files.move(active.toPath(), previous.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                try (FileOutputStream out = new FileOutputStream(active, true)) {
                    out.write(bytes);
                    out.flush();
                    if (durable) out.getFD().sync();
                }
            });
        }
    }

    private static String appVersion(Context context) {
        try { return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName; }
        catch (Exception ignored) { return "unknown"; }
    }

    private static String clean(String value, int max) {
        if (value == null) return "";
        String out = value.replace('\n', ' ').replace('\r', ' ').trim();
        return out.length() > max ? out.substring(0, max) : out;
    }

    private static String redact(String value) {
        if (value == null) return "";
        String out = BEARER.matcher(value).replaceAll("$1[REDACTED]");
        out = SECRET.matcher(out).replaceAll("$1[REDACTED]");
        out = QUERY_SECRET.matcher(out).replaceAll("$1[REDACTED]");
        return out.length() > 12000 ? out.substring(0, 12000) : out;
    }
}
