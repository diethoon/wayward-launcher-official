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
    public void dynamicExternalPortsAreAcceptedWithoutChangingTheLanListenerPort() {
        for (int port : new int[] {8765, 43210, 50000}) {
            String external = "https://8.8.8.8:" + port + "/";
            assertEquals(external, RemoteHostConnection.normalizeExternalBaseUrl(external));
        }
    }

    @Test
    public void externalEndpointRejectsMissingAndOutOfRangePorts() {
        for (String external : new String[] {
                "https://8.8.8.8/", "https://8.8.8.8:0/", "https://8.8.8.8:65536/"
        }) {
            boolean rejected = false;
            try {
                RemoteHostConnection.normalizeExternalBaseUrl(external);
            } catch (IllegalArgumentException expected) {
                rejected = true;
            }
            assertTrue("Invalid external port must be rejected: " + external, rejected);
        }
    }

    @Test
    public void externalEndpointIsHostSignedAndStillUsesThePinnedCertificate() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        KeyPair keyPair = RemoteHostStore.getDeviceKeyPair(context);
        String hostId = UUID.randomUUID().toString();
        String fingerprint = RemoteHostConnection.sha256Hex(keyPair.getPublic().getEncoded());
        String externalUrl = "https://8.8.8.8:43210/";
        String qrNonce = "9".repeat(64);
        long issuedAt = System.currentTimeMillis();
        long expiresAt = issuedAt + 300_000L;
        String payloadText = String.join("\n",
                "Wayward-Remote-Host-Connection",
                "protocol=1",
                "host=" + hostId,
                "baseUrl=https://192.168.1.77:8765/",
                "externalBaseUrl=" + externalUrl,
                "publicKeyFingerprint=" + fingerprint,
                "certificateSha256=" + "C".repeat(64),
                "qrNonce=" + qrNonce,
                "issued=" + issuedAt,
                "expires=" + expiresAt);
        byte[] payload = payloadText.getBytes(StandardCharsets.UTF_8);
        byte[] signature = RemoteHostClient.derToP1363(sign(keyPair, payload));
        try {
            JSONObject qr = new JSONObject()
                    .put("protocolVersion", 1)
                    .put("baseUrl", "https://192.168.1.77:8765/")
                    .put("externalBaseUrl", externalUrl)
                    .put("hostId", hostId)
                    .put("publicKeyBase64", Base64.encodeToString(keyPair.getPublic().getEncoded(), Base64.NO_WRAP))
                    .put("publicKeyFingerprint", fingerprint)
                    .put("certificateSha256", "C".repeat(64))
                    .put("qrNonce", qrNonce)
                    .put("signedPayloadBase64", Base64.encodeToString(payload, Base64.NO_WRAP))
                    .put("signatureBase64", Base64.encodeToString(signature, Base64.NO_WRAP));

            RemoteHostConnection parsed = RemoteHostConnection.fromQrJson(qr.toString());
            assertEquals(externalUrl, parsed.externalBaseUrl);
            assertArrayEquals(new String[] {parsed.baseUrl, parsed.externalBaseUrl},
                    RemoteHostClient.endpointCandidates(parsed));
            parsed.verifySignedManifest();
            JSONObject stored = parsed.toJson();
            assertEquals(externalUrl, stored.getString("externalBaseUrl"));
            RemoteHostConnection restored = RemoteHostConnection.fromStoredJson(stored.toString());
            assertEquals(externalUrl, restored.externalBaseUrl);
            restored.verifyStoredIdentity();

            JSONObject tampered = new JSONObject(qr.toString());
            tampered.put("externalBaseUrl", "https://1.1.1.1:8765/");
            RemoteHostConnection tamperedHost = RemoteHostConnection.fromQrJson(tampered.toString());
            assertThrows(SecurityException.class, tamperedHost::verifySignedManifest);
        } finally {
            java.util.Arrays.fill(payload, (byte) 0);
            java.util.Arrays.fill(signature, (byte) 0);
        }

        assertThrows(IllegalArgumentException.class, () ->
                RemoteHostConnection.normalizeExternalBaseUrl("http://8.8.8.8:8765/"));
        assertThrows(IllegalArgumentException.class, () ->
                RemoteHostConnection.normalizeExternalBaseUrl("https://192.168.1.2:8765/"));
        assertThrows(IllegalArgumentException.class, () ->
                RemoteHostConnection.normalizeExternalBaseUrl("https://100.64.0.1:8765/"));
        assertThrows(IllegalArgumentException.class, () ->
                RemoteHostConnection.normalizeExternalBaseUrl("https://example.com:8765/"));
        assertThrows(IllegalArgumentException.class, () ->
                RemoteHostConnection.normalizeExternalBaseUrl("https://8.8.8.8/"));
    }

    @Test
    public void publicIpv4ExternalBootstrapQrFetchesAndValidatesTheHostSignedManifest() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        KeyPair keyPair = RemoteHostStore.getDeviceKeyPair(context);
        String url = "https://8.8.8.8:43210/";
        JSONObject fullManifest = createExternalTestQr(keyPair, url, url, "192.168.1.77", 8765,
                "8.8.8.8", 43210, "active", "TCP", "wayward-auto-upnp-ledger");
        JSONObject compactQr = new JSONObject()
                .put("protocolVersion", 1)
                .put("baseUrl", url)
                .put("certificateSha256", fullManifest.getString("certificateSha256"))
                .put("qrNonce", fullManifest.getString("qrNonce"));

        RemoteHostConnection.BootstrapInfo bootstrap =
                RemoteHostConnection.fromBootstrapQrJson(compactQr.toString());
        assertEquals(url, bootstrap.baseUrl);
        RemoteHostConnection validated =
                RemoteHostConnection.fromManifestJson(bootstrap, fullManifest.toString());
        validated.verifySignedManifest();
        assertEquals(url, validated.baseUrl);
        assertEquals(url, validated.externalBaseUrl);
        assertArrayEquals(new String[] {url}, RemoteHostClient.endpointCandidates(validated));

        JSONObject wrongAddress = new JSONObject(compactQr.toString())
                .put("baseUrl", "https://1.1.1.1:43210/");
        RemoteHostConnection.BootstrapInfo wrongBootstrap =
                RemoteHostConnection.fromBootstrapQrJson(wrongAddress.toString());
        assertThrows(SecurityException.class,
                () -> RemoteHostConnection.fromManifestJson(wrongBootstrap, fullManifest.toString()));

        JSONObject reservedAddress = new JSONObject(compactQr.toString())
                .put("baseUrl", "https://192.88.99.1:43210/");
        assertThrows(IllegalArgumentException.class,
                () -> RemoteHostConnection.fromBootstrapQrJson(reservedAddress.toString()));
    }

    @Test
    public void publicIpv4ExternalTestQrRequiresActiveWaywardOwnedTcpMappingAndSignedEndpoint() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        KeyPair keyPair = RemoteHostStore.getDeviceKeyPair(context);
        String url = "https://8.8.8.8:43210/";
        JSONObject qr = createExternalTestQr(keyPair, url, url, "192.168.1.77", 8765,
                "8.8.8.8", 43210, "active", "TCP", "wayward-auto-upnp-ledger");

        RemoteHostConnection parsed = RemoteHostConnection.fromQrJson(qr.toString());
        parsed.verifySignedManifest();
        assertEquals(url, parsed.baseUrl);
        assertEquals(url, parsed.externalBaseUrl);
        assertArrayEquals(new String[] {url}, RemoteHostClient.endpointCandidates(parsed));

        JSONObject stored = parsed.toJson();
        assertTrue(stored.has("portMapping"));
        RemoteHostConnection restored = RemoteHostConnection.fromStoredJson(stored.toString());
        restored.verifyStoredIdentity();
        assertEquals(url, restored.baseUrl);
        assertArrayEquals(new String[] {url}, RemoteHostClient.endpointCandidates(restored));

        JSONObject tamperedSignature = new JSONObject(qr.toString())
                .put("signatureBase64", Base64.encodeToString(new byte[64], Base64.NO_WRAP));
        RemoteHostConnection badSignature = RemoteHostConnection.fromQrJson(tamperedSignature.toString());
        assertThrows(SecurityException.class, badSignature::verifySignedManifest);

        String[] nonPublicIpv4 = {
                "0.1.2.3", "10.0.0.1", "127.0.0.1", "100.64.0.1", "100.127.255.254",
                "169.254.1.1", "172.16.0.1", "172.31.255.254", "192.168.1.1",
                "192.0.0.1", "192.0.2.1", "198.18.0.1", "198.19.255.254",
                "192.88.99.1", "192.88.99.2",
                "198.51.100.1", "203.0.113.1", "224.0.0.1", "255.255.255.255"
        };
        for (String address : nonPublicIpv4) {
            String invalidUrl = "https://" + address + ":43210/";
            JSONObject invalid = createExternalTestQr(keyPair, invalidUrl, invalidUrl,
                    "192.168.1.77", 8765, address, 43210,
                    "active", "TCP", "wayward-auto-upnp-ledger");
            assertThrows("Reserved/CGNAT address rejected: " + address,
                    IllegalArgumentException.class,
                    () -> RemoteHostConnection.fromQrJson(invalid.toString()));
        }

        String[] invalidUrls = {
                "http://8.8.8.8:43210/", "https://8.8.8.8/",
                "https://8.8.8.8:0/", "https://8.8.8.8:65536/",
                "https://user@8.8.8.8:43210/", "https://8.8.8.8:43210/?x=1",
                "https://8.8.8.8:43210/#fragment", "https://8.8.8.8:43210/api"
        };
        for (String invalidUrl : invalidUrls) {
            JSONObject invalid = createExternalTestQr(keyPair, invalidUrl, invalidUrl,
                    "192.168.1.77", 8765, "8.8.8.8", 43210,
                    "active", "TCP", "wayward-auto-upnp-ledger");
            assertThrows("Invalid external QR URL rejected: " + invalidUrl,
                    IllegalArgumentException.class,
                    () -> RemoteHostConnection.fromQrJson(invalid.toString()));
        }

        String[][] invalidMappings = {
                {"state", "unavailable"}, {"protocol", "UDP"},
                {"ownershipState", "user-manual"}, {"internalPort", "8188"},
                {"externalPort", "43211"}, {"externalAddress", "1.1.1.1"},
                {"localAddress", "100.64.0.1"}
        };
        for (String[] mutation : invalidMappings) {
            JSONObject invalid = new JSONObject(qr.toString());
            JSONObject mapping = invalid.getJSONObject("portMapping");
            if ("internalPort".equals(mutation[0]) || "externalPort".equals(mutation[0])) {
                mapping.put(mutation[0], Integer.parseInt(mutation[1]));
            } else {
                mapping.put(mutation[0], mutation[1]);
            }
            assertThrows("Invalid port mapping field rejected: " + mutation[0],
                    IllegalArgumentException.class,
                    () -> RemoteHostConnection.fromQrJson(invalid.toString()));
        }

        JSONObject missingMapping = new JSONObject(qr.toString());
        missingMapping.remove("portMapping");
        assertThrows(IllegalArgumentException.class,
                () -> RemoteHostConnection.fromQrJson(missingMapping.toString()));
        JSONObject mismatchedAddress = new JSONObject(qr.toString())
                .put("externalBaseUrl", "https://1.1.1.1:43210/");
        assertThrows(IllegalArgumentException.class,
                () -> RemoteHostConnection.fromQrJson(mismatchedAddress.toString()));
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

    private static JSONObject createExternalTestQr(KeyPair signer, String baseUrl,
            String externalBaseUrl, String localAddress, int internalPort,
            String externalAddress, int externalPort, String mappingState,
            String protocol, String ownershipState) throws Exception {
        String hostId = UUID.randomUUID().toString();
        String fingerprint = RemoteHostConnection.sha256Hex(signer.getPublic().getEncoded());
        String certificatePin = "C".repeat(64);
        String qrNonce = UUID.randomUUID().toString().replace("-", "").toUpperCase(java.util.Locale.ROOT)
                + UUID.randomUUID().toString().replace("-", "").toUpperCase(java.util.Locale.ROOT);
        long issuedAt = System.currentTimeMillis();
        long expiresAt = issuedAt + 300_000L;
        String payloadText = String.join("\n",
                "Wayward-Remote-Host-Connection",
                "protocol=1",
                "host=" + hostId,
                "baseUrl=" + baseUrl,
                "externalBaseUrl=" + externalBaseUrl,
                "publicKeyFingerprint=" + fingerprint,
                "certificateSha256=" + certificatePin,
                "qrNonce=" + qrNonce,
                "issued=" + issuedAt,
                "expires=" + expiresAt);
        byte[] payload = payloadText.getBytes(StandardCharsets.UTF_8);
        byte[] signature = RemoteHostClient.derToP1363(sign(signer, payload));
        try {
            JSONObject mapping = new JSONObject()
                    .put("state", mappingState)
                    .put("method", "upnp")
                    .put("localAddress", localAddress)
                    .put("internalPort", internalPort)
                    .put("externalAddress", externalAddress)
                    .put("externalPort", externalPort)
                    .put("protocol", protocol)
                    .put("ownershipState", ownershipState)
                    .put("mappingDescription", "Wayward Remote Host test");
            return new JSONObject()
                    .put("protocolVersion", 1)
                    .put("baseUrl", baseUrl)
                    .put("externalBaseUrl", externalBaseUrl)
                    .put("portMapping", mapping)
                    .put("hostId", hostId)
                    .put("publicKeyBase64", Base64.encodeToString(signer.getPublic().getEncoded(), Base64.NO_WRAP))
                    .put("publicKeyFingerprint", fingerprint)
                    .put("certificateSha256", certificatePin)
                    .put("qrNonce", qrNonce)
                    .put("signedPayloadBase64", Base64.encodeToString(payload, Base64.NO_WRAP))
                    .put("signatureBase64", Base64.encodeToString(signature, Base64.NO_WRAP));
        } finally {
            java.util.Arrays.fill(payload, (byte) 0);
            java.util.Arrays.fill(signature, (byte) 0);
        }
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
