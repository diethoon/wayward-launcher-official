package com.wayward.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class OfficialUpdateManager {
    private static final String TAG = "WALOfficialUpdater";
    private static final String RELEASES_API =
            "https://api.github.com/repos/wal-studio/WAL-Official-Releases/releases?per_page=30";
    private static final String SIGNER_SHA1 = "D9BE7443EB247D27C958CC4250D7F5D1E078F740";
    private static final String PREFS = "wal_official_update_state";
    private static final String LAST_CHECK = "last_check_ms";
    private static final String PENDING_APK = "pending_apk_path";
    private static final String PENDING_HASH = "pending_apk_sha256";
    private static final long CHECK_INTERVAL_MS = 6L * 60L * 60L * 1000L;
    private static final long MAX_APK_BYTES = 300L * 1024L * 1024L;
    private static final Pattern STABLE_TAG = Pattern.compile("^v?(\\d+)\\.(\\d+)\\.(\\d+)$");
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean CHECKING = new AtomicBoolean(false);

    private OfficialUpdateManager() {}

    public interface Listener { void onUpdateAvailable(Update update); }

    public static final class Update {
        public final File apkFile;
        public final String versionName;
        public final long versionCode;
        public final String sha256;
        public final String notes;

        Update(File file, String name, long code, String hash, String body) {
            apkFile = file;
            versionName = name;
            versionCode = code;
            sha256 = hash;
            notes = body == null ? "" : body;
        }
    }

    public static void checkForUpdate(Context context, Listener listener) {
        if (context == null || !BuildConfig.WAL_OFFICIAL || !CHECKING.compareAndSet(false, true)) return;
        Context app = context.getApplicationContext();
        SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (System.currentTimeMillis() - prefs.getLong(LAST_CHECK, 0L) < CHECK_INTERVAL_MS) {
            CHECKING.set(false);
            return;
        }
        EXECUTOR.execute(() -> {
            try {
                Update update = findLatest(app);
                prefs.edit().putLong(LAST_CHECK, System.currentTimeMillis()).apply();
                if (update != null && listener != null) {
                    new Handler(Looper.getMainLooper()).post(() -> listener.onUpdateAvailable(update));
                }
            } catch (Exception error) {
                Log.w(TAG, "Stable update check failed", error);
                prefs.edit().putLong(LAST_CHECK, System.currentTimeMillis()).apply();
            } finally {
                CHECKING.set(false);
            }
        });
    }

    private static Update findLatest(Context context) throws Exception {
        JSONArray releases = new JSONArray(readUrl(RELEASES_API, 2 * 1024 * 1024));
        for (int i = 0; i < releases.length(); i++) {
            JSONObject release = releases.getJSONObject(i);
            if (release.optBoolean("draft", true) || release.optBoolean("prerelease", true)) continue;
            Matcher tag = STABLE_TAG.matcher(release.optString("tag_name", ""));
            if (!tag.matches()) continue;

            JSONArray assets = release.optJSONArray("assets");
            if (assets == null) continue;
            JSONObject apkAsset = null;
            JSONObject sumsAsset = null;
            int apkCount = 0;
            for (int j = 0; j < assets.length(); j++) {
                JSONObject asset = assets.getJSONObject(j);
                String name = asset.optString("name", "");
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.endsWith(".apk") && !lower.contains("debug")) {
                    apkAsset = asset;
                    apkCount++;
                }
                if (name.equalsIgnoreCase("SHA256SUMS.txt") || lower.endsWith(".apk.sha256")) sumsAsset = asset;
            }
            if (apkAsset == null) continue;
            if (apkCount != 1) throw new SecurityException("Stable release must contain exactly one installable APK.");
            String apkName = apkAsset.optString("name", "");
            String expectedName = "WAL-Android-Launcher-" + tag.group(1) + "." + tag.group(2) + "." + tag.group(3) + ".apk";
            if (!expectedName.equals(apkName)) throw new SecurityException("Stable APK filename does not match the release tag.");
            String apkUrl = apkAsset.optString("browser_download_url", "");
            if (!apkUrl.startsWith("https://github.com/wal-studio/WAL-Official-Releases/")) {
                throw new SecurityException("Release APK URL is outside the Official Releases repository.");
            }

            String expected = digestFromAsset(apkAsset.optString("digest", ""));
            if (expected.isEmpty() && sumsAsset != null) {
                String sumsUrl = sumsAsset.optString("browser_download_url", "");
                if (!sumsUrl.startsWith("https://github.com/wal-studio/WAL-Official-Releases/")) {
                    throw new SecurityException("Checksum URL is outside the Official Releases repository.");
                }
                expected = findChecksum(readUrl(sumsUrl, 256 * 1024), apkName);
            }
            if (expected.length() != 64) throw new SecurityException("Stable release has no SHA-256 metadata.");

            File dir = new File(context.getCacheDir(), "updates");
            if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Cannot create update cache.");
            File apk = new File(dir, "wal-update-" + tag.group(1) + "-" + tag.group(2) + "-" + tag.group(3) + ".apk");
            download(apkUrl, apk);
            Update update = verifyApk(context, apk, expected, release.optString("body", ""));
            String tagVersion = tag.group(1) + "." + tag.group(2) + "." + tag.group(3);
            if (!tagVersion.equals(update.versionName)) throw new SecurityException("Release tag/APK version mismatch.");
            PackageInfo installed = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            if (update.versionCode <= installed.getLongVersionCode()) {
                apk.delete();
                return null;
            }
            return update;
        }
        return null;
    }

    public static void install(Context context, Update update) {
        if (context == null || update == null || !BuildConfig.WAL_OFFICIAL) return;
        SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit().putString(PENDING_APK, update.apkFile.getAbsolutePath())
                .putString(PENDING_HASH, update.sha256).apply();
        if (Build.VERSION.SDK_INT >= 26 && !context.getPackageManager().canRequestPackageInstalls()) {
            try {
                Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + context.getPackageName()));
                if (!(context instanceof android.app.Activity)) settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(settings);
            } catch (Exception error) {
                Toast.makeText(context, "앱 설치 권한 설정을 열 수 없습니다.", Toast.LENGTH_LONG).show();
                Log.e(TAG, "Cannot open install permission settings", error);
            }
            return;
        }
        installPendingIfAllowed(context);
    }

    public static void installPendingIfAllowed(Context context) {
        if (context == null || !BuildConfig.WAL_OFFICIAL) return;
        if (Build.VERSION.SDK_INT >= 26 && !context.getPackageManager().canRequestPackageInstalls()) return;
        Context app = context.getApplicationContext();
        SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String path = prefs.getString(PENDING_APK, null);
        String hash = prefs.getString(PENDING_HASH, null);
        if (path == null || hash == null) return;
        try {
            File apk = new File(path);
            Update verified = verifyApk(app, apk, hash, "");
            PackageInfo installed = app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
            if (verified.versionCode <= installed.getLongVersionCode()) {
                clearPending(prefs);
                return;
            }
            Uri uri = FileProvider.getUriForFile(app, app.getPackageName() + ".diagnostics", apk);
            Intent installer = new Intent(Intent.ACTION_VIEW);
            installer.setDataAndType(uri, "application/vnd.android.package-archive");
            installer.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            clearPending(prefs);
            app.startActivity(installer);
        } catch (Exception error) {
            clearPending(prefs);
            Log.e(TAG, "Verified APK could not be passed to Android installer", error);
            Toast.makeText(app, "업데이트 설치 준비에 실패했습니다: " + safeMessage(error), Toast.LENGTH_LONG).show();
        }
    }

    private static void clearPending(SharedPreferences prefs) {
        prefs.edit().remove(PENDING_APK).remove(PENDING_HASH).apply();
    }

    private static Update verifyApk(Context context, File apk, String expectedHash, String notes) throws Exception {
        if (!apk.isFile() || apk.length() <= 0 || apk.length() > MAX_APK_BYTES) {
            throw new SecurityException("Downloaded APK is missing or has an invalid size.");
        }
        String actualHash = sha256(apk);
        if (!actualHash.equalsIgnoreCase(expectedHash)) throw new SecurityException("APK SHA-256 mismatch.");
        PackageInfo info = context.getPackageManager().getPackageArchiveInfo(
                apk.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
        if (info == null || !context.getPackageName().equals(info.packageName)) {
            throw new SecurityException("APK package ID does not match this Official app.");
        }
        if (info.signingInfo == null) throw new SecurityException("APK signing identity is unavailable.");
        Signature[] signers = info.signingInfo.getApkContentsSigners();
        if (signers == null || signers.length != 1 || !SIGNER_SHA1.equals(sha1(signers[0].toByteArray()))) {
            throw new SecurityException("APK signer does not match the registered Official certificate.");
        }
        return new Update(apk, info.versionName, info.getLongVersionCode(), actualHash, notes);
    }

    private static String digestFromAsset(String digest) {
        if (digest == null || !digest.startsWith("sha256:")) return "";
        String value = digest.substring(7).trim().toLowerCase(Locale.ROOT);
        return value.matches("[0-9a-f]{64}") ? value : "";
    }

    private static String findChecksum(String text, String apkName) {
        if (text == null) return "";
        String onlyHash = text.trim();
        if (onlyHash.matches("[0-9a-fA-F]{64}")) return onlyHash.toLowerCase(Locale.ROOT);
        for (String line : text.split("\\r?\\n")) {
            Matcher m = Pattern.compile("^([0-9a-fA-F]{64})\\s+\\*?(.+)$").matcher(line.trim());
            if (!m.matches()) continue;
            String listed = m.group(2).trim().replace('\\', '/');
            int slash = listed.lastIndexOf('/');
            if (slash >= 0) listed = listed.substring(slash + 1);
            if (apkName.equals(listed)) return m.group(1).toLowerCase(Locale.ROOT);
        }
        return "";
    }

    private static void download(String url, File target) throws Exception {
        HttpURLConnection c = open(url);
        try {
            int status = c.getResponseCode();
            if (status != 200) throw new IllegalStateException("APK download HTTP " + status);
            if (c.getContentLengthLong() > MAX_APK_BYTES) throw new SecurityException("APK too large.");
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(target)) {
                byte[] buffer = new byte[64 * 1024];
                long total = 0;
                int n;
                while ((n = in.read(buffer)) != -1) {
                    total += n;
                    if (total > MAX_APK_BYTES) throw new SecurityException("APK exceeded size limit.");
                    out.write(buffer, 0, n);
                }
                out.flush();
            }
        } finally { c.disconnect(); }
    }

    private static String readUrl(String url, int maxBytes) throws Exception {
        if (url == null || !url.startsWith("https://")) throw new SecurityException("HTTPS required.");
        HttpURLConnection c = open(url);
        try {
            int status = c.getResponseCode();
            if (status != 200) throw new IllegalStateException("Update metadata HTTP " + status);
            try (InputStream in = c.getInputStream();
                 java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int total = 0;
                int n;
                while ((n = in.read(buffer)) != -1) {
                    total += n;
                    if (total > maxBytes) throw new SecurityException("Update metadata too large.");
                    out.write(buffer, 0, n);
                }
                return out.toString("UTF-8");
            }
        } finally { c.disconnect(); }
    }

    private static HttpURLConnection open(String url) throws Exception {
        if (url == null || !url.startsWith("https://")) throw new SecurityException("HTTPS required.");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setRequestProperty("User-Agent", "WAL-Android-Launcher-Updater");
        c.setRequestProperty("Accept", "application/vnd.github+json");
        return c;
    }

    private static String sha256(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) != -1) md.update(buffer, 0, n);
        }
        return hex(md.digest());
    }

    private static String sha1(byte[] cert) throws Exception {
        return hex(MessageDigest.getInstance("SHA-1").digest(cert));
    }

    private static String hex(byte[] data) {
        StringBuilder out = new StringBuilder(data.length * 2);
        for (byte b : data) out.append(String.format(Locale.ROOT, "%02X", b));
        return out.toString();
    }

    private static String safeMessage(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
