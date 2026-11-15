package com.wayward.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONObject;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.spec.ECGenParameterSpec;
import java.util.UUID;

/** Non-secret Remote Host selection state. Access tokens are intentionally never persisted here. */
final class RemoteHostStore {
    private static final String PREFS = "wayward_launcher";
    private static final String KEY_HOST = "remote_host_connection_v1";
    private static final String KEY_DEVICE_ID = "remote_host_device_id";
    private static final String KEY_DEVICE_NAME = "remote_host_device_name";
    private static final String KEY_ROOT_ID = "remote_host_root_id";
    private static final String KEY_ROOT_LABEL = "remote_host_root_label";
    private static final Object LOCK = new Object();

    private RemoteHostStore() {}

    static void saveConnection(Context context, RemoteHostConnection connection) throws Exception {
        synchronized (LOCK) {
            preferences(context).edit().putString(KEY_HOST, connection.toJson().toString()).commit();
        }
    }

    static RemoteHostConnection loadConnection(Context context) throws Exception {
        String raw = preferences(context).getString(KEY_HOST, null);
        if (raw == null || raw.trim().isEmpty()) return null;
        RemoteHostConnection connection = RemoteHostConnection.fromStoredJson(raw);
        connection.verifySignedManifest();
        return connection;
    }

    static void clearConnection(Context context) {
        synchronized (LOCK) {
            preferences(context).edit()
                    .remove(KEY_HOST)
                    .remove(KEY_ROOT_ID)
                    .remove(KEY_ROOT_LABEL)
                    .commit();
        }
        RemoteHostClient.clearSession();
    }

    static String getDeviceName(Context context) {
        return preferences(context).getString(KEY_DEVICE_NAME, "Wayward Android").trim();
    }

    static void saveDeviceName(Context context, String value) {
        String safe = value == null ? "" : value.trim();
        if (safe.isEmpty()) safe = "Wayward Android";
        if (safe.length() > 64) safe = safe.substring(0, 64);
        preferences(context).edit().putString(KEY_DEVICE_NAME, safe).apply();
    }

    static String getDeviceId(Context context) throws Exception {
        getDeviceKeyPair(context);
        return preferences(context).getString(KEY_DEVICE_ID, "");
    }

    static KeyPair getDeviceKeyPair(Context context) throws Exception {
        synchronized (LOCK) {
            SharedPreferences prefs = preferences(context);
            KeyStore store = KeyStore.getInstance("AndroidKeyStore");
            store.load(null);

            String deviceId = prefs.getString(KEY_DEVICE_ID, "");
            if (!deviceId.isEmpty()) {
                String alias = keyAlias(deviceId);
                if (store.containsAlias(alias)) {
                    KeyStore.Entry entry = store.getEntry(alias, null);
                    if (entry instanceof KeyStore.PrivateKeyEntry) {
                        KeyStore.PrivateKeyEntry value = (KeyStore.PrivateKeyEntry) entry;
                        return new KeyPair(value.getCertificate().getPublicKey(), value.getPrivateKey());
                    }
                }
                // A persisted Device ID without its Keystore key cannot prove the old
                // enrollment. Rotate the local ID instead of silently reusing the old one.
            }

            deviceId = UUID.randomUUID().toString();
            String alias = keyAlias(deviceId);
            KeyPairGenerator generator = KeyPairGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
            generator.initialize(new KeyGenParameterSpec.Builder(
                    alias, KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setUserAuthenticationRequired(false)
                    .build());
            KeyPair pair = generator.generateKeyPair();
            if (!prefs.edit().putString(KEY_DEVICE_ID, deviceId).commit()) {
                store.deleteEntry(alias);
                throw new IllegalStateException("Remote Host Device ID를 안전하게 저장하지 못했습니다.");
            }
            return pair;
        }
    }

    static String getSelectedRootId(Context context) {
        return preferences(context).getString(KEY_ROOT_ID, "");
    }

    static String getSelectedRootLabel(Context context) {
        return preferences(context).getString(KEY_ROOT_LABEL, "");
    }

    static void saveSelectedRoot(Context context, String rootId, String label) {
        if (rootId == null || rootId.trim().isEmpty()) {
            throw new IllegalArgumentException("PC 게임 폴더 ID가 없습니다.");
        }
        preferences(context).edit()
                .putString(KEY_ROOT_ID, rootId.trim())
                .putString(KEY_ROOT_LABEL, label == null ? "" : label.trim())
                .apply();
    }

    static boolean isReady(Context context) {
        try {
            return loadConnection(context) != null && !getSelectedRootId(context).isEmpty();
        } catch (Exception ignored) {
            return false;
        }
    }

    static String publicKeyBase64(Context context) throws Exception {
        return Base64.encodeToString(getDeviceKeyPair(context).getPublic().getEncoded(), Base64.NO_WRAP);
    }

    private static String keyAlias(String deviceId) {
        return "wayward.remotehost.device." + deviceId;
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
