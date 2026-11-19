package com.wayward.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Locale;
import java.util.UUID;

public final class RemotePcFileManagerActivity extends Activity {
    private static final int REQUEST_DOWNLOAD_TARGET = 8701;
    private static final int REQUEST_UPLOAD_SOURCE = 8702;
    private static final String PREFS = "wayward_launcher";
    private static final String KEY_PENDING_UPLOAD = "remote_pc_pending_upload_v1";
    private static final String KEY_PENDING_DOWNLOAD = "remote_pc_pending_download_v1";
    private static final long MAX_REMOTE_DOWNLOAD_BYTES = 512L * 1024 * 1024;

    private LinearLayout page;
    private LinearLayout itemList;
    private TextView status;
    private TextView breadcrumb;
    private RemoteHostConnection host;
    private String rootId = "";
    private String rootLabel = "";
    private String relativePath = "";
    private String pendingDownloadPath = "";
    private String pendingDownloadName = "";
    private boolean busy;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        try {
            if (!RemoteHostStore.isPaired(this)) throw new SecurityException("PC 페어링이 필요합니다.");
            host = RemoteHostStore.loadConnection(this);
            if (host == null) throw new SecurityException("등록된 PC Host가 없습니다.");
            rootId = RemoteHostStore.getSelectedRootId(this);
            rootLabel = RemoteHostStore.getSelectedRootLabel(this);
            loadRootsAndDirectory();
            promptResumeUpload();
        } catch (Exception error) {
            showError(error.getMessage());
        }
    }

    private void buildUi() {
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(18, 18, 18, 18);
        page.setBackgroundColor(0xFFF6F7F8);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        Button back = button("뒤로");
        back.setOnClickListener(v -> finish());
        header.addView(back);
        TextView title = new TextView(this);
        title.setText("원격 PC 파일매니저");
        title.setTextSize(20);
        title.setTextColor(0xFF1C252E);
        title.setPadding(12, 0, 0, 0);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        page.addView(header);

        status = new TextView(this);
        status.setText("승인된 PC 폴더 연결 중…");
        status.setTextSize(12);
        status.setTextColor(0xFF627080);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, -2);
        statusParams.topMargin = 10;
        page.addView(status, statusParams);

        LinearLayout actions = new LinearLayout(this);
        Button chooseRoot = button("PC 폴더");
        chooseRoot.setOnClickListener(v -> showRootsDialog());
        actions.addView(chooseRoot, new LinearLayout.LayoutParams(0, 46, 1f));
        Button up = button("상위 폴더");
        up.setOnClickListener(v -> navigateUp());
        LinearLayout.LayoutParams upParams = new LinearLayout.LayoutParams(0, 46, 1f);
        upParams.leftMargin = 6;
        actions.addView(up, upParams);
        Button refresh = button("새로고침");
        refresh.setOnClickListener(v -> loadDirectory());
        LinearLayout.LayoutParams refreshParams = new LinearLayout.LayoutParams(0, 46, 1f);
        refreshParams.leftMargin = 6;
        actions.addView(refresh, refreshParams);
        page.addView(actions);

        breadcrumb = new TextView(this);
        breadcrumb.setTextColor(0xFF314357);
        breadcrumb.setTextSize(13);
        LinearLayout.LayoutParams crumbParams = new LinearLayout.LayoutParams(-1, -2);
        crumbParams.topMargin = 10;
        page.addView(breadcrumb, crumbParams);

        ScrollView scroll = new ScrollView(this);
        itemList = new LinearLayout(this);
        itemList.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(itemList);
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(-1, 0, 1f);
        listParams.topMargin = 12;
        page.addView(scroll, listParams);

        Button upload = button("이 PC 폴더에 파일 업로드");
        upload.setOnClickListener(v -> pickUploadSource());
        LinearLayout.LayoutParams uploadParams = new LinearLayout.LayoutParams(-1, 48);
        uploadParams.topMargin = 10;
        page.addView(upload, uploadParams);
        setContentView(page);
    }

    private Button button(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        button.setTextSize(12);
        return button;
    }

    private void loadRootsAndDirectory() {
        setBusy(true, "PC 연결 및 허용 폴더 확인 중…");
        new Thread(() -> {
            try {
                RemoteHostClient.authenticate(this, host);
                JSONArray roots = RemoteHostClient.getWorkspaceRoots(this, host);
                if (roots.length() == 0) throw new IOException("PC에서 허용한 폴더가 없습니다.");
                boolean selectedExists = false;
                for (int i = 0; i < roots.length(); i++) {
                    JSONObject item = roots.optJSONObject(i);
                    if (item != null && rootId.equals(item.optString("rootId", ""))) {
                        selectedExists = true;
                        rootLabel = item.optString("displayName", rootId);
                        break;
                    }
                }
                if (!selectedExists) {
                    JSONObject item = roots.getJSONObject(0);
                    rootId = item.getString("rootId");
                    rootLabel = item.optString("displayName", rootId);
                    RemoteHostStore.saveSelectedRoot(this, rootId, rootLabel);
                }
                JSONArray items = RemoteFilesClient.list(this, host, rootId, relativePath);
                runOnUiThread(() -> renderItems(items));
            } catch (Exception error) {
                runOnUiThread(() -> showError(error.getMessage()));
            }
        }, "Wayward-Remote-File-List").start();
    }

    private void loadDirectory() {
        if (busy || host == null || rootId.isEmpty()) return;
        setBusy(true, "PC 폴더 목록 불러오는 중…");
        new Thread(() -> {
            try {
                RemoteHostClient.authenticate(this, host);
                JSONArray items = RemoteFilesClient.list(this, host, rootId, relativePath);
                runOnUiThread(() -> renderItems(items));
            } catch (Exception error) {
                runOnUiThread(() -> showError(error.getMessage()));
            }
        }, "Wayward-Remote-File-Refresh").start();
    }

    private void renderItems(JSONArray items) {
        busy = false;
        status.setText("PC: " + rootLabel + " · " + items.length() + "개 항목");
        breadcrumb.setText(rootLabel + (relativePath.isEmpty() ? "" : " / " + relativePath));
        itemList.removeAllViews();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            String name = item.optString("name", "이름 없는 항목");
            String path = item.optString("relativePath", "");
            boolean directory = item.optBoolean("isDirectory", false);
            long length = item.optLong("length", 0L);
            Button row = button((directory ? "📁  " : "📄  ") + name +
                    (directory ? "" : "   ·   " + readableSize(length)));
            row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            row.setPadding(16, 6, 12, 6);
            row.setOnClickListener(v -> {
                if (directory) {
                    relativePath = path;
                    loadDirectory();
                } else {
                    confirmDownload(path, name);
                }
            });
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, 52);
            rowParams.bottomMargin = 5;
            itemList.addView(row, rowParams);
        }
        if (items.length() == 0) {
            TextView empty = new TextView(this);
            empty.setText("이 폴더는 비어 있습니다.");
            empty.setTextColor(0xFF77818C);
            empty.setPadding(12, 18, 12, 18);
            itemList.addView(empty);
        }
    }

    private void showRootsDialog() {
        setBusy(true, "허용 폴더 목록 확인 중…");
        new Thread(() -> {
            try {
                JSONArray roots = RemoteHostClient.getWorkspaceRoots(this, host);
                String[] labels = new String[roots.length()];
                String[] ids = new String[roots.length()];
                for (int i = 0; i < roots.length(); i++) {
                    JSONObject item = roots.getJSONObject(i);
                    ids[i] = item.getString("rootId");
                    labels[i] = item.optString("displayName", ids[i]);
                }
                runOnUiThread(() -> {
                    busy = false;
                    new AlertDialog.Builder(this).setTitle("PC에서 허용한 폴더")
                            .setItems(labels, (dialog, which) -> {
                                rootId = ids[which];
                                rootLabel = labels[which];
                                relativePath = "";
                                RemoteHostStore.saveSelectedRoot(this, rootId, rootLabel);
                                loadDirectory();
                            }).setNegativeButton("취소", null).show();
                });
            } catch (Exception error) {
                runOnUiThread(() -> showError(error.getMessage()));
            }
        }, "Wayward-Remote-Root-List").start();
    }

    private void navigateUp() {
        if (relativePath.isEmpty()) return;
        int index = relativePath.lastIndexOf('/');
        relativePath = index < 0 ? "" : relativePath.substring(0, index);
        loadDirectory();
    }

    private void confirmDownload(String path, String name) {
        String unfinished = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_PENDING_DOWNLOAD, "");
        if (unfinished != null && !unfinished.isEmpty()) {
            promptResumeDownload();
            return;
        }
        new AlertDialog.Builder(this).setTitle("PC 파일 다운로드")
                .setMessage(name + " 파일을 이 Android 기기로 저장할까요?")
                .setNegativeButton("취소", null)
                .setPositiveButton("저장", (dialog, which) -> {
                    pendingDownloadPath = path;
                    pendingDownloadName = name;
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("application/octet-stream");
                    intent.putExtra(Intent.EXTRA_TITLE, name);
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION |
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                    startActivityForResult(intent, REQUEST_DOWNLOAD_TARGET);
                }).show();
    }

    private void pickUploadSource() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_UPLOAD_SOURCE);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQUEST_DOWNLOAD_TARGET) {
            int grantFlags = data.getFlags() &
                    (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            try {
                getContentResolver().takePersistableUriPermission(uri, grantFlags);
            } catch (Exception error) {
                showError("다운로드 저장 위치 권한을 유지할 수 없습니다: " + error.getMessage());
                return;
            }
            downloadTo(uri);
        } else if (requestCode == REQUEST_UPLOAD_SOURCE) {
            try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch (Exception ignored) { }
            uploadFrom(uri);
        }
    }

    private void downloadTo(Uri destination) {
        try {
            File temporary = createDownloadTempFile();
            JSONObject metadata = new JSONObject()
                    .put("hostId", host.hostId)
                    .put("rootId", rootId)
                    .put("path", pendingDownloadPath)
                    .put("name", pendingDownloadName)
                    .put("destinationUri", destination.toString())
                    .put("tempName", temporary.getName())
                    .put("totalBytes", -1L)
                    .put("sha256", "");
            savePendingDownload(metadata);
            downloadPending(metadata);
        } catch (Exception error) {
            showError("다운로드 준비 실패: " + error.getMessage());
        }
    }

    private void promptResumeDownload() {
        String raw = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_PENDING_DOWNLOAD, "");
        if (raw == null || raw.isEmpty()) return;
        try {
            JSONObject pending = new JSONObject(raw);
            File temporary = getDownloadTempFile(pending.getString("tempName"));
            long total = pending.optLong("totalBytes", -1L);
            String name = pending.optString("name", "PC 파일");
            String progress = readableSize(temporary.exists() ? temporary.length() : 0) +
                    (total >= 0 ? " / " + readableSize(total) : "");
            new AlertDialog.Builder(this).setTitle("미완료 다운로드")
                    .setMessage(name + " 다운로드가 중단되었습니다. 저장된 위치부터 이어 받을까요? " + progress)
                    .setNegativeButton("부분 파일 삭제", (dialog, which) -> discardPendingDownload(pending))
                    .setNeutralButton("나중에", null)
                    .setPositiveButton("이어 받기", (dialog, which) -> downloadPending(pending))
                    .show();
        } catch (Exception error) {
            showError("저장된 다운로드 재개 정보를 읽을 수 없습니다: " + error.getMessage());
        }
    }

    private void downloadPending(JSONObject metadata) {
        setBusy(true, "PC 파일 다운로드 재개 및 SHA-256 검증 중…");
        new Thread(() -> {
            try {
                if (!host.hostId.equalsIgnoreCase(metadata.getString("hostId"))) {
                    throw new SecurityException("이 다운로드는 다른 PC Host에서 시작되었습니다.");
                }
                String savedRootId = metadata.getString("rootId");
                String path = metadata.getString("path");
                String name = metadata.optString("name", "PC 파일");
                Uri destination = Uri.parse(metadata.getString("destinationUri"));
                File temporary = getDownloadTempFile(metadata.getString("tempName"));
                if (!temporary.getParentFile().exists() && !temporary.getParentFile().mkdirs()) {
                    throw new IOException("다운로드 임시 폴더를 만들 수 없습니다.");
                }

                long totalBytes = metadata.optLong("totalBytes", -1L);
                String expectedSha256 = metadata.optString("sha256", "");
                long offset = temporary.exists() ? temporary.length() : 0L;
                if (totalBytes > MAX_REMOTE_DOWNLOAD_BYTES) {
                    throw new IOException("다운로드는 0~512MiB 파일만 지원합니다.");
                }

                if (totalBytes < 0 || expectedSha256.isEmpty()) {
                    if (offset != 0 && !temporary.delete()) {
                        throw new IOException("이전 임시 다운로드를 초기화할 수 없습니다.");
                    }
                    try (RemoteFilesClient.FileDownloadResponse response =
                                 RemoteFilesClient.openDownload(this, host, savedRootId, path, null)) {
                        totalBytes = response.getContentLength();
                        if (totalBytes < 0 || totalBytes > MAX_REMOTE_DOWNLOAD_BYTES) {
                            throw new IOException("다운로드는 0~512MiB 파일만 지원합니다.");
                        }
                        expectedSha256 = response.getSha256();
                        metadata.put("totalBytes", totalBytes).put("sha256", expectedSha256);
                        savePendingDownload(metadata);
                        RemoteDownloadResumePolicy.Decision decision = RemoteDownloadResumePolicy.validate(
                                0L, totalBytes, expectedSha256, response.getStatusCode(),
                                response.getContentRange(), response.getContentLength(), response.getSha256());
                        try (InputStream input = response.getStream();
                             FileOutputStream output = new FileOutputStream(temporary, decision.shouldAppend())) {
                            long expectedResponseBytes = decision.getResponseEnd() - decision.getResponseStart() + 1L;
                            copyStream(input, output, expectedResponseBytes);
                            output.getFD().sync();
                        }
                    }
                } else {
                    if (offset > totalBytes) {
                        throw new IOException("임시 다운로드 크기가 원본 파일 크기를 초과했습니다.");
                    }
                    if (offset < totalBytes) {
                        java.util.Map<String, String> headers = offset > 0
                                ? Collections.singletonMap("Range", "bytes=" + offset + "-")
                                : null;
                        try (RemoteFilesClient.FileDownloadResponse response =
                                     RemoteFilesClient.openDownload(this, host, savedRootId, path, headers)) {
                            RemoteDownloadResumePolicy.Decision decision = RemoteDownloadResumePolicy.validate(
                                    offset, totalBytes, expectedSha256, response.getStatusCode(),
                                    response.getContentRange(), response.getContentLength(), response.getSha256());
                            try (InputStream input = response.getStream();
                                 FileOutputStream output = new FileOutputStream(temporary, decision.shouldAppend())) {
                                long expectedResponseBytes = decision.getResponseEnd() - decision.getResponseStart() + 1L;
                                copyStream(input, output, expectedResponseBytes);
                                output.getFD().sync();
                            }
                        }
                    }
                }

                if (!temporary.isFile() || temporary.length() != totalBytes) {
                    throw new IOException("다운로드가 중단되었습니다. 임시 파일은 보존했으며 다음 실행에서 이어 받을 수 있습니다.");
                }
                String actualSha256 = sha256File(temporary);
                if (!MessageDigest.isEqual(actualSha256.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                        expectedSha256.getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
                    throw new SecurityException("다운로드 파일 SHA-256이 PC Host 응답과 일치하지 않습니다.");
                }

                copyVerifiedDownloadToDestination(temporary, destination, totalBytes, expectedSha256);
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(KEY_PENDING_DOWNLOAD).commit();
                if (!temporary.delete()) temporary.deleteOnExit();
                String completedName = name;
                runOnUiThread(() -> {
                    busy = false;
                    status.setText("다운로드 완료 · " + completedName);
                    Toast.makeText(this, "PC 파일 저장 완료", Toast.LENGTH_LONG).show();
                });
            } catch (Exception error) {
                runOnUiThread(() -> showError("다운로드가 중단됐습니다. 임시 파일은 보존됩니다. " + error.getMessage()));
            }
        }, "Wayward-Remote-File-Download").start();
    }

    private void copyVerifiedDownloadToDestination(File temporary, Uri destination,
            long totalBytes, String expectedSha256) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long copied = 0L;
        try (InputStream input = new FileInputStream(temporary);
             OutputStream output = getContentResolver().openOutputStream(destination, "w")) {
            if (output == null) throw new IOException("저장 위치를 열 수 없습니다.");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (read == 0) continue;
                copied += read;
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
            output.flush();
        }
        String copiedSha256 = hex(digest.digest());
        if (copied != totalBytes || !MessageDigest.isEqual(
                copiedSha256.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                expectedSha256.getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
            throw new SecurityException("Android 저장 위치에 기록된 파일의 크기 또는 SHA-256 검증에 실패했습니다.");
        }
    }

    private static long copyStream(InputStream input, OutputStream output, long maximumBytes) throws IOException {
        if (maximumBytes < 0) throw new IOException("PC 응답의 Range 길이가 올바르지 않습니다.");
        byte[] buffer = new byte[64 * 1024];
        long total = 0L;
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (read == 0) continue;
            if (read > maximumBytes - total) {
                throw new IOException("PC 응답이 검증된 다운로드 범위를 초과했습니다.");
            }
            total += read;
            output.write(buffer, 0, read);
        }
        return total;
    }

    private static String sha256File(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (read == 0) continue;
                digest.update(buffer, 0, read);
            }
        }
        return hex(digest.digest());
    }

    private File createDownloadTempFile() throws IOException {
        File directory = new File(getFilesDir(), "remote-downloads");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("다운로드 임시 폴더를 만들 수 없습니다.");
        }
        return new File(directory, UUID.randomUUID().toString() + ".part");
    }

    private File getDownloadTempFile(String name) throws IOException {
        if (name == null || !name.matches("(?i)[0-9a-f-]{36}\\.part")) {
            throw new IOException("다운로드 임시 파일 이름이 올바르지 않습니다.");
        }
        File directory = new File(getFilesDir(), "remote-downloads");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("다운로드 임시 폴더를 만들 수 없습니다.");
        }
        File candidate = new File(directory, name).getCanonicalFile();
        if (!candidate.getParentFile().equals(directory.getCanonicalFile())) {
            throw new SecurityException("다운로드 임시 파일 경로가 앱 저장 영역을 벗어났습니다.");
        }
        return candidate;
    }

    private void savePendingDownload(JSONObject metadata) throws IOException {
        if (!getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_PENDING_DOWNLOAD, metadata.toString()).commit()) {
            throw new IOException("다운로드 재개 정보를 저장할 수 없습니다.");
        }
    }

    private void discardPendingDownload(JSONObject metadata) {
        try {
            File temporary = getDownloadTempFile(metadata.getString("tempName"));
            if (temporary.exists() && !temporary.delete()) {
                throw new IOException("임시 다운로드 파일을 삭제할 수 없습니다.");
            }
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(KEY_PENDING_DOWNLOAD).commit();
            status.setText("중단된 다운로드를 삭제했습니다.");
        } catch (Exception error) {
            showError("중단된 다운로드 삭제 실패: " + error.getMessage());
        }
    }

    private void uploadFrom(Uri uri) {
        setBusy(true, "업로드 원본 확인 중…");
        new Thread(() -> {
            try {
                String name = queryDisplayName(uri);
                long size = querySize(uri);
                if (size <= 0 || size > 512L * 1024 * 1024)
                    throw new IOException("업로드는 1바이트~512MiB 파일만 지원합니다.");
                String destination = relativePath.isEmpty() ? name : relativePath + "/" + name;
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (InputStream source = getContentResolver().openInputStream(uri)) {
                    if (source == null) throw new IOException("업로드 원본을 열 수 없습니다.");
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    long total = 0;
                    while ((read = source.read(buffer)) != -1) {
                        if (read == 0) continue;
                        total += read;
                        if (total > size) throw new IOException("업로드 원본 크기가 변경됐습니다.");
                        digest.update(buffer, 0, read);
                    }
                    if (total != size) throw new IOException("업로드 원본 크기를 확인할 수 없습니다.");
                }
                String checksum = hex(digest.digest());
                JSONObject session = RemoteFilesClient.beginUpload(this, host, rootId, destination, size, checksum, null);
                String uploadId = session.getString("uploadId");
                int maxChunk = Math.min(1024 * 1024, session.optInt("maximumChunkBytes", 1024 * 1024));
                long offset = session.optLong("nextOffset", 0L);
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_PENDING_UPLOAD,
                        new JSONObject().put("uploadId", uploadId).put("uri", uri.toString())
                                .put("rootId", rootId).put("path", destination)
                                .put("totalBytes", size).put("sha256", checksum)
                                .put("maximumChunkBytes", maxChunk).toString()).commit();
                transferUpload(uri, uploadId, destination, size, checksum, maxChunk, offset);
            } catch (Exception error) {
                runOnUiThread(() -> showError("업로드 실패: " + error.getMessage()));
            }
        }, "Wayward-Remote-File-Upload").start();
    }

    private void promptResumeUpload() {
        String pending = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_PENDING_UPLOAD, "");
        if (pending == null || pending.isEmpty()) {
            promptResumeDownload();
            return;
        }
        new AlertDialog.Builder(this).setTitle("미완료 업로드")
                .setMessage("이전에 중단된 파일 업로드가 있습니다. PC Host가 확인한 업로드 위치부터 이어서 진행할까요?")
                .setNegativeButton("나중에", (dialog, which) -> promptResumeDownload())
                .setPositiveButton("이어 하기", (dialog, which) -> resumePendingUpload(pending)).show();
    }

    private void resumePendingUpload(String raw) {
        new Thread(() -> {
            try {
                JSONObject pending = new JSONObject(raw);
                Uri uri = Uri.parse(pending.getString("uri"));
                String uploadId = pending.getString("uploadId");
                String path = pending.getString("path");
                long total = pending.getLong("totalBytes");
                String checksum = pending.getString("sha256");
                int max = pending.optInt("maximumChunkBytes", 1024 * 1024);
                JSONObject progress = RemoteFilesClient.getUploadProgress(this, host, uploadId);
                long offset = progress.getLong("nextOffset");
                if (progress.getLong("totalBytes") != total) throw new SecurityException("PC 업로드 세션의 전체 크기가 저장된 원본과 다릅니다.");
                transferUpload(uri, uploadId, path, total, checksum, max, offset);
                runOnUiThread(this::promptResumeDownload);
            } catch (Exception error) {
                runOnUiThread(() -> showError("업로드 재개 실패: " + error.getMessage()));
            }
        }, "Wayward-Remote-File-Upload-Resume").start();
    }

    private void transferUpload(Uri uri, String uploadId, String path, long total, String checksum,
            int maxChunk, long offset) throws Exception {
        setBusy(true, "PC 파일 업로드 중…");
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IOException("업로드 원본을 다시 열 수 없습니다.");
            skipFully(input, offset);
            byte[] buffer = new byte[Math.max(4096, Math.min(1024 * 1024, maxChunk))];
            while (offset < total) {
                int wanted = (int) Math.min(buffer.length, total - offset);
                int used = 0;
                while (used < wanted) {
                    int read = input.read(buffer, used, wanted - used);
                    if (read < 0) throw new IOException("업로드 원본이 중간에 끝났습니다.");
                    if (read == 0) continue;
                    used += read;
                }
                byte[] chunk = java.util.Arrays.copyOf(buffer, used);
                JSONObject response;
                try {
                    response = RemoteFilesClient.appendUploadChunk(this, host, uploadId, offset, chunk, maxChunk);
                } finally {
                    java.util.Arrays.fill(chunk, (byte) 0);
                }
                offset = response.getLong("nextOffset");
                long current = offset;
                runOnUiThread(() -> status.setText("PC 파일 업로드 중… " + current + " / " + total + " bytes"));
            }
        }
        JSONObject complete = RemoteFilesClient.completeUpload(this, host, uploadId);
        if (!checksum.equalsIgnoreCase(complete.getString("sha256")))
            throw new SecurityException("PC에서 확정한 업로드 SHA-256이 원본과 다릅니다.");
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(KEY_PENDING_UPLOAD).commit();
        runOnUiThread(() -> {
            busy = false;
            status.setText("업로드 완료 · " + path);
            Toast.makeText(this, "PC 파일 업로드 완료", Toast.LENGTH_LONG).show();
            loadDirectory();
        });
    }

    private static void skipFully(InputStream input, long amount) throws IOException {
        long left = amount;
        byte[] discard = new byte[8192];
        while (left > 0) {
            long skipped = input.skip(left);
            if (skipped > 0) { left -= skipped; continue; }
            int read = input.read(discard, 0, (int) Math.min(discard.length, left));
            if (read < 0) throw new IOException("업로드 원본이 저장된 이어하기 위치보다 짧습니다.");
            left -= read;
        }
    }

    private String queryDisplayName(Uri uri) throws IOException {
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String name = cursor.getString(index);
                    if (name != null && !name.trim().isEmpty()) return name;
                }
            }
        }
        throw new IOException("업로드 파일 이름을 확인할 수 없습니다.");
    }

    private long querySize(Uri uri) throws IOException {
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (index >= 0 && !cursor.isNull(index)) return cursor.getLong(index);
            }
        }
        throw new IOException("업로드 파일 크기를 제공하지 않는 문서는 아직 지원하지 않습니다.");
    }

    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) value.append(String.format(Locale.ROOT, "%02X", b));
        return value.toString();
    }

    private String readableSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private void setBusy(boolean value, String message) {
        busy = value;
        runOnUiThread(() -> status.setText(message));
    }

    private void showError(String message) {
        busy = false;
        if (isFinishing()) return;
        status.setText(message == null ? "원격 PC 작업에 실패했습니다." : message);
        new AlertDialog.Builder(this).setTitle("원격 PC 파일매니저")
                .setMessage(message == null ? "원격 PC 작업에 실패했습니다." : message)
                .setPositiveButton("확인", null).show();
    }
}