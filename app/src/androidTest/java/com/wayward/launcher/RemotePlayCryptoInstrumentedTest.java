package com.wayward.launcher;

import android.content.Context;
import android.util.Base64;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.Signature;
import java.util.UUID;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** Exercises Android Keystore and Host-signed proof verification on a real Android runtime. */
@RunWith(AndroidJUnit4.class)
public final class RemotePlayCryptoInstrumentedTest {
    @Test
    public void androidKeystoreDeviceIdentityIsStableAndCanSignHostProofs() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

        String firstDeviceId = RemoteHostStore.getDeviceId(context);
        KeyPair firstKey = RemoteHostStore.getDeviceKeyPair(context);
        String secondDeviceId = RemoteHostStore.getDeviceId(context);
        KeyPair secondKey = RemoteHostStore.getDeviceKeyPair(context);

        assertEquals(firstDeviceId, secondDeviceId);
        assertArrayEquals(firstKey.getPublic().getEncoded(), secondKey.getPublic().getEncoded());
        assertTrue(firstKey.getPrivate().getEncoded() == null ||
                firstKey.getPrivate().getEncoded().length == 0);

        byte[] challenge = "Wayward Android Keystore P-256 proof".getBytes(StandardCharsets.UTF_8);
        byte[] derSignature = sign(firstKey, challenge);
        byte[] wireSignature = RemoteHostClient.derToP1363(derSignature);
        assertEquals(64, wireSignature.length);

        String hostId = UUID.randomUUID().toString();
        String requestId = UUID.randomUUID().toString();
        String deviceFingerprint = RemoteHostConnection.sha256Hex(firstKey.getPublic().getEncoded());
        String certificatePin = "A".repeat(64);
        String baseUrl = "https://192.168.1.77:8765/";
        RemoteHostConnection host = createSignedHostManifest(
                firstKey, hostId, baseUrl, deviceFingerprint, certificatePin,
                System.currentTimeMillis(), System.currentTimeMillis() + 300_000L);

        host.verifySignedManifest();
        host.verifyHostSignature(challenge, wireSignature);

        String deviceId = firstDeviceId;
        int permissions = 15;
        long registeredAt = System.currentTimeMillis();
        byte[] receipt = String.join("\n",
                "Wayward-Remote-Host-Pairing-Approval",
                "protocol=1",
                "host=" + hostId,
                "request=" + requestId,
                "device=" + deviceId,
                "displayName=Android emulator",
                "fingerprint=" + deviceFingerprint,
                "permissions=" + permissions,
                "registered=" + registeredAt).getBytes(StandardCharsets.UTF_8);
        byte[] receiptSignature = RemoteHostClient.derToP1363(sign(firstKey, receipt));

        host.verifyPairingApprovalReceipt(
                receipt, receiptSignature, requestId, deviceId, deviceFingerprint, permissions);
        assertThrows(SecurityException.class, () ->
                host.verifyPairingApprovalReceipt(
                        receipt, receiptSignature, requestId, deviceId, deviceFingerprint, permissions + 1));
        assertThrows(SecurityException.class, () ->
                host.verifyPairingApprovalReceipt(
                        receipt, receiptSignature, requestId, UUID.randomUUID().toString(),
                        deviceFingerprint, permissions));

        java.util.Arrays.fill(challenge, (byte) 0);
        java.util.Arrays.fill(derSignature, (byte) 0);
        java.util.Arrays.fill(wireSignature, (byte) 0);
        java.util.Arrays.fill(receipt, (byte) 0);
        java.util.Arrays.fill(receiptSignature, (byte) 0);
    }

    @Test
    public void signedHostIdentityRemainsVerifiableAfterQrExpiryButFreshQrDoesNot() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        KeyPair keyPair = RemoteHostStore.getDeviceKeyPair(context);
        String hostId = UUID.randomUUID().toString();
        String fingerprint = RemoteHostConnection.sha256Hex(keyPair.getPublic().getEncoded());
        long issuedAt = System.currentTimeMillis() - 600_000L;
        long expiresAt = issuedAt + 300_000L;

        RemoteHostConnection storedHost = createSignedHostManifest(
                keyPair, hostId, "https://192.168.1.78:8765/", fingerprint,
                "B".repeat(64), issuedAt, expiresAt);

        storedHost.verifyStoredIdentity();
        assertThrows(SecurityException.class, storedHost::verifySignedManifest);
    }

    @Test
    public void remoteHostRejectsNonPrivateOrCleartextQrUrls() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        KeyPair keyPair = RemoteHostStore.getDeviceKeyPair(context);
        String fingerprint = RemoteHostConnection.sha256Hex(keyPair.getPublic().getEncoded());
        String hostId = UUID.randomUUID().toString();
        assertThrows(IllegalArgumentException.class, () -> new RemoteHostConnection(
                "http://192.168.1.90:8765/", hostId,
                Base64.encodeToString(keyPair.getPublic().getEncoded(), Base64.NO_WRAP),
                fingerprint, "C".repeat(64), "payload", "signature"));
        assertThrows(IllegalArgumentException.class, () -> new RemoteHostConnection(
                "https://8.8.8.8:8765/", hostId,
                Base64.encodeToString(keyPair.getPublic().getEncoded(), Base64.NO_WRAP),
                fingerprint, "C".repeat(64), "payload", "signature"));
    }

    private static RemoteHostConnection createSignedHostManifest(KeyPair signer, String hostId,
            String baseUrl, String keyFingerprint, String certificatePin,
            long issuedAt, long expiresAt) throws Exception {
        String payloadText = String.join("\n",
                "Wayward-Remote-Host-Connection",
                "protocol=1",
                "host=" + hostId,
                "baseUrl=" + baseUrl,
                "publicKeyFingerprint=" + keyFingerprint,
                "certificateSha256=" + certificatePin,
                "issued=" + issuedAt,
                "expires=" + expiresAt);
        byte[] payload = payloadText.getBytes(StandardCharsets.UTF_8);
        byte[] signature = RemoteHostClient.derToP1363(sign(signer, payload));
        try {
            return new RemoteHostConnection(
                    baseUrl,
                    hostId,
                    Base64.encodeToString(signer.getPublic().getEncoded(), Base64.NO_WRAP),
                    keyFingerprint,
                    certificatePin,
                    Base64.encodeToString(payload, Base64.NO_WRAP),
                    Base64.encodeToString(signature, Base64.NO_WRAP));
        } finally {
            java.util.Arrays.fill(payload, (byte) 0);
            java.util.Arrays.fill(signature, (byte) 0);
        }
    }

    private static byte[] sign(KeyPair pair, byte[] payload) throws Exception {
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign(pair.getPrivate());
        signature.update(payload);
        return signature.sign();
    }
}
