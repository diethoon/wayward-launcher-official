package com.wayward.launcher;

import android.content.Context;
import android.util.Base64;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.json.JSONObject;

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
    public void developerQrImageSamplingBoundsDecodedMemoryAndRejectsInvalidDimensions() {
        assertEquals(1, RemotePairingActivity.calculateQrImageSampleSize(1024, 768));
        int sample = RemotePairingActivity.calculateQrImageSampleSize(12000, 8000);
        assertTrue(sample >= 4);
        long decodedPixels = ((12000L + sample - 1L) / sample)
                * ((8000L + sample - 1L) / sample);
        assertTrue(decodedPixels <= 8_000_000L);
        assertTrue(RemotePairingActivity.calculateQrImageSampleSize(4096, 4096) >= 2);
        assertThrows(IllegalArgumentException.class,
                () -> RemotePairingActivity.calculateQrImageSampleSize(0, 100));
        assertThrows(IllegalArgumentException.class,
                () -> RemotePairingActivity.calculateQrImageSampleSize(100001, 100));
    }

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
        String qrNonce = "D".repeat(64);
        String baseUrl = "https://192.168.1.77:8765/";
        RemoteHostConnection host = createSignedHostManifest(
                firstKey, hostId, baseUrl, deviceFingerprint, certificatePin, qrNonce,
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
    public void qrNonceIsParsedFromWindowsManagerJsonAndBoundToTheHostSignature() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        KeyPair keyPair = RemoteHostStore.getDeviceKeyPair(context);
        String hostId = UUID.randomUUID().toString();
        String fingerprint = RemoteHostConnection.sha256Hex(keyPair.getPublic().getEncoded());
        String qrNonce = "A".repeat(64);
        RemoteHostConnection original = createSignedHostManifest(
                keyPair, hostId, "https://192.168.1.79:8765/", fingerprint,
                "C".repeat(64), qrNonce,
                System.currentTimeMillis(), System.currentTimeMillis() + 300_000L);

        // This is the camelCase JSON envelope emitted by Windows /v1/host/qr.
        JSONObject managerQr = original.toJson();
        RemoteHostConnection parsed = RemoteHostConnection.fromQrJson(managerQr.toString());
        assertEquals(qrNonce, parsed.qrNonce);
        assertEquals(hostId, parsed.hostId);
        parsed.verifySignedManifest();

        JSONObject tamperedNonce = new JSONObject(managerQr.toString());
        tamperedNonce.put("qrNonce", "F".repeat(64));
        RemoteHostConnection tampered = RemoteHostConnection.fromQrJson(tamperedNonce.toString());
        assertThrows(SecurityException.class, tampered::verifySignedManifest);

        JSONObject missingNonce = new JSONObject(managerQr.toString());
        missingNonce.remove("qrNonce");
        assertThrows(Exception.class,
                () -> RemoteHostConnection.fromQrJson(missingNonce.toString()));

        JSONObject malformedNonce = new JSONObject(managerQr.toString());
        malformedNonce.put("qrNonce", "not-a-64-character-hex-nonce");
        assertThrows(IllegalArgumentException.class,
                () -> RemoteHostConnection.fromQrJson(malformedNonce.toString()));
    }

    @Test
    public void bootstrapQrFetchManifestFieldsMustMatchBeforeHostSignatureIsTrusted() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        KeyPair keyPair = RemoteHostStore.getDeviceKeyPair(context);
        String hostId = UUID.randomUUID().toString();
        String fingerprint = RemoteHostConnection.sha256Hex(keyPair.getPublic().getEncoded());
        String baseUrl = "https://192.168.1.81:8765/";
        String certificatePin = "C".repeat(64);
        String nonce = "A".repeat(64);
        long issuedAt = System.currentTimeMillis();
        RemoteHostConnection fullManifest = createSignedHostManifest(
                keyPair, hostId, baseUrl, fingerprint, certificatePin, nonce,
                issuedAt, issuedAt + 300_000L);

        JSONObject bootstrapQr = new JSONObject()
                .put("protocolVersion", 1)
                .put("baseUrl", baseUrl)
                .put("certificateSha256", certificatePin)
                .put("qrNonce", nonce);
        assertTrue(RemoteHostConnection.isBootstrapQrJson(bootstrapQr.toString()));
        RemoteHostConnection.BootstrapInfo bootstrap =
                RemoteHostConnection.fromBootstrapQrJson(bootstrapQr.toString());
        RemoteHostConnection resolved = RemoteHostConnection.fromManifestJson(
                bootstrap, fullManifest.toJson().toString());
        resolved.verifySignedManifest();
        assertEquals(hostId, resolved.hostId);

        JSONObject legacyQr = fullManifest.toJson();
        assertTrue(!RemoteHostConnection.isBootstrapQrJson(legacyQr.toString()));
        assertEquals(hostId, RemoteHostConnection.fromQrJson(legacyQr.toString()).hostId);

        JSONObject wrongAddress = new JSONObject(bootstrapQr.toString())
                .put("baseUrl", "https://192.168.1.82:8765/");
        assertThrows(SecurityException.class, () ->
                RemoteHostConnection.fromManifestJson(
                        RemoteHostConnection.fromBootstrapQrJson(wrongAddress.toString()),
                        fullManifest.toJson().toString()));

        JSONObject wrongCertificate = new JSONObject(bootstrapQr.toString())
                .put("certificateSha256", "D".repeat(64));
        assertThrows(SecurityException.class, () ->
                RemoteHostConnection.fromManifestJson(
                        RemoteHostConnection.fromBootstrapQrJson(wrongCertificate.toString()),
                        fullManifest.toJson().toString()));

        JSONObject wrongNonce = new JSONObject(bootstrapQr.toString())
                .put("qrNonce", "B".repeat(64));
        assertThrows(SecurityException.class, () ->
                RemoteHostConnection.fromManifestJson(
                        RemoteHostConnection.fromBootstrapQrJson(wrongNonce.toString()),
                        fullManifest.toJson().toString()));

        for (String invalidUrl : new String[] {
                "http://192.168.1.81:8765/",
                "https://192.168.1.81/",
                "https://192.168.1.81:0/",
                "https://192.168.1.81:65536/",
                "https://192.168.1.81:8765/unexpected-path"
        }) {
            JSONObject invalid = new JSONObject(bootstrapQr.toString()).put("baseUrl", invalidUrl);
            assertThrows(IllegalArgumentException.class,
                    () -> RemoteHostConnection.fromBootstrapQrJson(invalid.toString()));
        }

        JSONObject malformedNonce = new JSONObject(bootstrapQr.toString()).put("qrNonce", "expired");
        assertThrows(IllegalArgumentException.class,
                () -> RemoteHostConnection.fromBootstrapQrJson(malformedNonce.toString()));

        JSONObject extraField = new JSONObject(bootstrapQr.toString()).put("hostHint", "untrusted");
        assertThrows(IllegalArgumentException.class,
                () -> RemoteHostConnection.fromBootstrapQrJson(extraField.toString()));

        // A stray legacy field must not make a compact QR eligible for the legacy parser.
        JSONObject mixedFormat = new JSONObject(bootstrapQr.toString()).put("hostId", hostId);
        assertTrue(RemoteHostConnection.isBootstrapQrJson(mixedFormat.toString()));
        assertThrows(IllegalArgumentException.class,
                () -> RemoteHostConnection.fromBootstrapQrJson(mixedFormat.toString()));

        JSONObject tamperedManifest = fullManifest.toJson();
        tamperedManifest.put("signatureBase64",
                Base64.encodeToString(new byte[64], Base64.NO_WRAP));
        RemoteHostConnection invalidSignature = RemoteHostConnection.fromManifestJson(bootstrap, tamperedManifest.toString());
        assertThrows(SecurityException.class, invalidSignature::verifySignedManifest);

        long expiredAt = System.currentTimeMillis() - 60_000L;
        RemoteHostConnection expiredManifest = createSignedHostManifest(
                keyPair, hostId, baseUrl, fingerprint, certificatePin, nonce,
                expiredAt - 300_000L, expiredAt);
        RemoteHostConnection expired = RemoteHostConnection.fromManifestJson(bootstrap, expiredManifest.toJson().toString());
        assertThrows(SecurityException.class, expired::verifySignedManifest);
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
                "B".repeat(64), "E".repeat(64), issuedAt, expiresAt);

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
                fingerprint, "C".repeat(64), "D".repeat(64), "payload", "signature"));
        assertThrows(IllegalArgumentException.class, () -> new RemoteHostConnection(
                "https://8.8.8.8:8765/", hostId,
                Base64.encodeToString(keyPair.getPublic().getEncoded(), Base64.NO_WRAP),
                fingerprint, "C".repeat(64), "D".repeat(64), "payload", "signature"));
    }

    @Test
    public void pairedWanEndpointIsStoredSeparatelyAndKeepsTheOriginalCertificatePin() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        KeyPair keyPair = RemoteHostStore.getDeviceKeyPair(context);
        String hostId = UUID.randomUUID().toString();
        String fingerprint = RemoteHostConnection.sha256Hex(keyPair.getPublic().getEncoded());
        RemoteHostConnection host = createSignedHostManifest(
                keyPair, hostId, "https://192.168.1.78:8765/", fingerprint,
                "B".repeat(64), "E".repeat(64),
                System.currentTimeMillis() - 1_000L, System.currentTimeMillis() + 240_000L);

        host.updateLanBaseUrl("https://192.168.1.88:8765/");
        host.updateWanBaseUrl("https://1.1.1.1:8765/");
        RemoteHostConnection restored = RemoteHostConnection.fromStoredJson(host.toJson().toString());
        restored.verifyStoredIdentity();

        assertEquals("https://192.168.1.78:8765/", restored.baseUrl);
        assertEquals("https://192.168.1.88:8765/", restored.lanBaseUrl);
        assertEquals("https://1.1.1.1:8765/", restored.wanBaseUrl);
        assertEquals(host.certificateSha256, restored.certificateSha256);
        assertArrayEquals(new String[] {
                "https://1.1.1.1:8765/",
                "https://192.168.1.88:8765/",
                "https://192.168.1.78:8765/"
        }, RemoteHostClient.endpointCandidates(restored));

        assertThrows(IllegalArgumentException.class,
                () -> RemoteHostConnection.normalizeWanBaseUrl("https://192.168.1.88:8765/"));
        assertThrows(IllegalArgumentException.class,
                () -> RemoteHostConnection.normalizeWanBaseUrl("https://100.64.0.1:8765/"));
        assertThrows(IllegalArgumentException.class,
                () -> RemoteHostConnection.normalizeWanBaseUrl("http://1.1.1.1:8765/"));
    }

    private static RemoteHostConnection createSignedHostManifest(KeyPair signer, String hostId,
            String baseUrl, String keyFingerprint, String certificatePin, String qrNonce,
            long issuedAt, long expiresAt) throws Exception {
        String payloadText = String.join("\n",
                "Wayward-Remote-Host-Connection",
                "protocol=1",
                "host=" + hostId,
                "baseUrl=" + baseUrl,
                "publicKeyFingerprint=" + keyFingerprint,
                "certificateSha256=" + certificatePin,
                "qrNonce=" + qrNonce,
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
                    qrNonce,
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
