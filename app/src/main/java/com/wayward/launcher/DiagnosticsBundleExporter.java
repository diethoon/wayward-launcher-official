package com.wayward.launcher;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Creates and shares a privacy-filtered diagnostic ZIP directly from the launcher home screen. */
final class DiagnosticsBundleExporter {
    private DiagnosticsBundleExporter() {}

    static File create(Activity activity) throws Exception {
        long now = System.currentTimeMillis();
        File directory = new File(activity.getCacheDir(), "diagnostics");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("진단 ZIP 임시 폴더를 만들 수 없습니다.");
        }
        prune(directory);
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date(now));
        File archive = new File(directory, "wayward-diagnostics-" + stamp + ".zip");
        JSONObject metadata = metadata(activity, now);
        File logs = ImagePackDiagnostics.directory(activity);
        File launcherActive = new File(logs, "launcher.ndjson");
        File launcherPrevious = new File(logs, "launcher.previous.ndjson");
        File active = new File(logs, "imagepack.ndjson");
        File previous = new File(logs, "imagepack.previous.ndjson");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(archive))) {
            putText(zip, "metadata.json", metadata.toString(2));
            putText(zip, "README.txt",
                    "Wayward Launcher diagnostic bundle.\n"
                    + "Contains bounded launcher and image-pack event journals, redacted ImageGen server log tails, and app/device metadata. "
                    + "It does not attach source game files, image files, or selected folder contents. "
                    + "Event details may contain diagnostic context; review before sharing.\n");
            addFile(zip, "launcher.ndjson", launcherActive);
            addFile(zip, "launcher.previous.ndjson", launcherPrevious);
            addFile(zip, "imagepack.ndjson", active);
            addFile(zip, "imagepack.previous.ndjson", previous);
            File imageGenLogs = new File(activity.getFilesDir(), "imagegen");
            addRedactedTail(zip, "imagegen/startup.log", new File(imageGenLogs, "startup.log"));
            addRedactedTail(zip, "imagegen/server.log", new File(imageGenLogs, "server.log"));
            zip.finish();
        } catch (Exception error) {
            if (archive.exists()) archive.delete();
            ImagePackDiagnostics.error(activity, "", "", "diagnostics_zip_create_failed", error);
            throw error;
        }
        if (!archive.isFile() || archive.length() == 0L) {
            throw new IllegalStateException("진단 ZIP이 비어 있습니다.");
        }
        ImagePackDiagnostics.event(activity, "", "", "diagnostics_zip_created", "info",
                new JSONObject().put("fileSize", archive.length()));
        return archive;
    }

    static void share(Activity activity) throws Exception {
        share(activity, create(activity));
    }

    static void share(Activity activity, File archive) throws Exception {
        if (archive == null || !archive.isFile() || archive.length() <= 0L) {
            throw new IllegalArgumentException("진단 ZIP 파일을 찾을 수 없습니다.");
        }
        Uri contentUri = FileProvider.getUriForFile(activity,
                activity.getPackageName() + ".diagnostics", archive);
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("application/zip");
        send.putExtra(Intent.EXTRA_STREAM, contentUri);
        send.putExtra(Intent.EXTRA_SUBJECT, "Wayward Launcher 진단 로그");
        send.putExtra(Intent.EXTRA_TEXT, "이미지팩 폴더 선택 장애 진단 로그입니다.");
        send.setClipData(ClipData.newUri(activity.getContentResolver(), "Wayward diagnostics", contentUri));
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        activity.startActivity(Intent.createChooser(send, "진단 ZIP 공유"));
    }

    private static JSONObject metadata(Activity activity, long now) throws Exception {
        PackageInfo info = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
        long versionCode = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
        JSONObject meta = new JSONObject();
        meta.put("product", "Wayward Launcher");
        meta.put("package", activity.getPackageName());
        meta.put("appVersion", info.versionName == null ? "unknown" : info.versionName);
        meta.put("versionCode", versionCode);
        meta.put("buildType",
                (activity.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
                        ? "debug" : "release");
        meta.put("androidRelease", Build.VERSION.RELEASE == null ? "unknown" : Build.VERSION.RELEASE);
        meta.put("androidSdk", Build.VERSION.SDK_INT);
        meta.put("exportedAt", now);
        JSONObject failure = lastFailure(ImagePackDiagnostics.directory(activity));
        meta.put("lastFailureStage", failure.optString("stage", "none"));
        meta.put("lastFailureAt", failure.optLong("timestamp", 0L));
        meta.put("journalPresent", new File(ImagePackDiagnostics.directory(activity), "imagepack.ndjson").isFile());
        meta.put("launcherJournalPresent", new File(ImagePackDiagnostics.directory(activity), "launcher.ndjson").isFile());
        return meta;
    }

    private static JSONObject lastFailure(File directory) {
        JSONObject latest = new JSONObject();
        for (String name : new String[]{"imagepack.previous.ndjson", "imagepack.ndjson"}) {
            File file = new File(directory, name);
            if (!file.isFile()) continue;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    new FileInputStream(file), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    try {
                        JSONObject item = new JSONObject(line);
                        if ("error".equalsIgnoreCase(item.optString("status"))
                                || "warn".equalsIgnoreCase(item.optString("status"))) {
                            if (item.optLong("timestamp") >= latest.optLong("timestamp")) latest = item;
                        }
                    } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
        }
        JSONObject result = new JSONObject();
        try {
            result.put("stage", latest.optString("stage", "none"));
            result.put("timestamp", latest.optLong("timestamp", 0L));
        } catch (Exception ignored) {}
        return result;
    }

    private static void putText(ZipOutputStream zip, String name, String content) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static void addFile(ZipOutputStream zip, String name, File file) throws Exception {
        if (!file.isFile() || file.length() == 0L) return;
        zip.putNextEntry(new ZipEntry(name));
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int count;
            long copied = 0L;
            while ((count = input.read(buffer)) != -1 && copied < 512L * 1024L) {
                int allowed = (int) Math.min(count, 512L * 1024L - copied);
                zip.write(buffer, 0, allowed);
                copied += allowed;
                if (allowed < count) break;
            }
        }
        zip.closeEntry();
    }

    private static void addRedactedTail(ZipOutputStream zip, String entryName, File file) throws Exception {
        final int maxBytes = 128 * 1024;
        if (file == null || !file.isFile() || file.length() <= 0L) return;
        long length = file.length();
        long offset = Math.max(0L, length - maxBytes);
        int count = (int) (length - offset);
        byte[] bytes = new byte[count];
        try (java.io.RandomAccessFile input = new java.io.RandomAccessFile(file, "r")) {
            input.seek(offset);
            input.readFully(bytes);
        }
        String content = new String(bytes, StandardCharsets.UTF_8);
        if (offset > 0L) {
            int newline = content.indexOf('\n');
            if (newline >= 0) content = content.substring(newline + 1);
        }
        content = redactSecrets(content);
        zip.putNextEntry(new ZipEntry(entryName));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String redactSecrets(String value) {
        if (value == null) return "";
        String safe = value.replaceAll("(?i)(Bearer\\s+)[A-Za-z0-9._~+/-]+=*", "$1[REDACTED]");
        safe = safe.replaceAll("(?i)(\\b(?:NAI_TOKEN|TOKEN|API[_-]?KEY|APIKEY|ACCESS[_-]?TOKEN|REFRESH[_-]?TOKEN|AUTHORIZATION|PASSWORD|SECRET)\\b\\s*[=:]\\s*)[\\\"']?[^\\s,;\\\"']+", "$1[REDACTED]");
        safe = safe.replaceAll("(?i)(\\b(?:cookie|set-cookie)\\s*[:=]\\s*)[^\\r\\n]+", "$1[REDACTED]");
        return safe;
    }

    private static void prune(File directory) {
        File[] files = directory.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isFile() && file.getName().startsWith("wayward-diagnostics-")
                    && System.currentTimeMillis() - file.lastModified() > 24L * 60L * 60L * 1000L) {
                file.delete();
            }
        }
    }
}
