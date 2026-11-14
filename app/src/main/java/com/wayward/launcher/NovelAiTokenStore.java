package com.wayward.launcher;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Device-bound, Android Keystore-backed NovelAI credential storage.
 *
 * The file name, Keystore alias, and WIP1 AES-GCM envelope are intentionally
 * compatible with the previous ImageProviderStore so an installed user's
 * NovelAI token is migrated without re-entry. Legacy provider records are
 * discarded while the token is retained.
 */
final class NovelAiTokenStore {
    private static final String KEY_ALIAS = "wayward.image.providers.v1";
    private static final byte[] MAGIC = new byte[] { 'W', 'I', 'P', '1' };
    private static final int IV_BYTES = 12;
    private static final int MAX_FILE_BYTES = 2 * 1024 * 1024;

    private final File target;

    private NovelAiTokenStore(Context context) {
        target = new File(context.getFilesDir(), "image-providers.enc");
    }

    private static final Object FILE_LOCK = new Object();

    static String readToken(Context context) throws Exception {
        synchronized (FILE_LOCK) {
            return new NovelAiTokenStore(context).loadAndMigrate();
        }
    }

    /** Called only by the explicit token save or delete UI, and legacy-token migration. */
    static void saveToken(Context context, String token) throws Exception {
        String safeToken = token == null ? "" : token.trim();
        if (safeToken.length() > 4096) {
            throw new IllegalArgumentException("NovelAI 토큰은 4096자 이하여야 합니다.");
        }
        synchronized (FILE_LOCK) {
            new NovelAiTokenStore(context).writeToken(safeToken);
        }
    }

    private synchronized String loadAndMigrate() throws Exception {
        if (!target.isFile()) return "";
        byte[] packed = readLimited(target, MAX_FILE_BYTES);
        if (packed.length < MAGIC.length + 1 + IV_BYTES + 16) {
            throw new IllegalStateException("NovelAI 토큰 저장 파일이 손상되었습니다.");
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (packed[i] != MAGIC[i]) {
                throw new IllegalStateException("지원하지 않는 NovelAI 토큰 저장 형식입니다.");
            }
        }
        int ivLength = packed[MAGIC.length] & 0xff;
        if (ivLength != IV_BYTES || packed.length <= MAGIC.length + 1 + ivLength) {
            throw new IllegalStateException("NovelAI 토큰 암호화 헤더가 올바르지 않습니다.");
        }
        byte[] iv = new byte[ivLength];
        System.arraycopy(packed, MAGIC.length + 1, iv, 0, ivLength);
        int encryptedOffset = MAGIC.length + 1 + ivLength;
        byte[] encrypted = new byte[packed.length - encryptedOffset];
        System.arraycopy(packed, encryptedOffset, encrypted, 0, encrypted.length);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, getKey(false), new GCMParameterSpec(128, iv));
        byte[] plain = cipher.doFinal(encrypted);
        JSONObject root = new JSONObject(new String(plain, java.nio.charset.StandardCharsets.UTF_8));
        String token = root.optString("novelAiToken", "");
        if (token.length() > 4096) {
            throw new IllegalStateException("NovelAI 토큰이 저장 길이 제한을 초과했습니다.");
        }

        // Version 1 stored Cloudflare/custom provider records beside the token.
        // Rewrite that same encrypted file in token-only schema v2 without
        // changing the existing key alias or losing the user's saved token.
        if (root.optInt("schemaVersion", 0) < 2
                || root.has("providers") || root.has("defaults") || root.has("fallbacks")) {
            writeToken(token);
        }
        return token;
    }

    private synchronized void writeToken(String token) throws Exception {
        JSONObject root = new JSONObject();
        root.put("schemaVersion", 2);
        root.put("novelAiToken", token);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getKey(true));
        byte[] encrypted = cipher.doFinal(root.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] iv = cipher.getIV();
        if (iv == null || iv.length != IV_BYTES) {
            throw new IllegalStateException("NovelAI 토큰 암호화 초기화에 실패했습니다.");
        }

        ByteArrayOutputStream packed = new ByteArrayOutputStream(MAGIC.length + 1 + iv.length + encrypted.length);
        packed.write(MAGIC);
        packed.write(iv.length);
        packed.write(iv);
        packed.write(encrypted);
        byte[] bytes = packed.toByteArray();
        if (bytes.length > MAX_FILE_BYTES) {
            throw new IllegalStateException("NovelAI 토큰 저장 용량을 초과했습니다.");
        }

        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IllegalStateException("NovelAI 토큰 저장 폴더를 만들지 못했습니다.");
        }
        File temp = new File(parent, target.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp, false)) {
            out.write(bytes);
            out.flush();
            out.getFD().sync();
        }
        try {
            Files.move(temp.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            if (temp.exists()) temp.delete();
        }
    }

    private SecretKey getKey(boolean create) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (keyStore.containsAlias(KEY_ALIAS)) return (SecretKey) keyStore.getKey(KEY_ALIAS, null);
        if (!create) {
            throw new IllegalStateException("이 기기의 암호화 키를 찾지 못했습니다. 토큰을 다시 등록해야 합니다.");
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }

    private static byte[] readLimited(File file, int limit) throws Exception {
        if (file.length() > limit) {
            throw new IllegalStateException("NovelAI 토큰 저장 파일이 너무 큽니다.");
        }
        try (FileInputStream in = new FileInputStream(file);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int total = 0;
            int count;
            while ((count = in.read(buffer)) >= 0) {
                if (count == 0) continue;
                total += count;
                if (total > limit) {
                    throw new IllegalStateException("NovelAI 토큰 저장 파일이 너무 큽니다.");
                }
                out.write(buffer, 0, count);
            }
            return out.toByteArray();
        }
    }
}
