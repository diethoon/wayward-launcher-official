package com.wayward.launcher;

import android.util.Base64;

import org.json.JSONObject;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Locale;
import java.util.UUID;

/** Verified, public connection metadata for one Windows Remote Host. */
final class RemoteHostConnection {
    final String baseUrl;
    final String externalBaseUrl;
    final String hostId;
    final String publicKeyBase64;
    final String publicKeyFingerprint;
    final String certificateSha256;
    final String qrNonce;
    final String signedPayloadBase64;
    final String signatureBase64;

    RemoteHostConnection(String baseUrl, String hostId, String publicKeyBase64,
            String publicKeyFingerprint, String certificateSha256, String qrNonce,
            String signedPayloadBase64, String signatureBase64) {
        this(baseUrl, "", hostId, publicKeyBase64, publicKeyFingerprint,
                certificateSha256, qrNonce, signedPayloadBase64, signatureBase64);
    }

    RemoteHostConnection(String baseUrl, String externalBaseUrl, String hostId,
            String publicKeyBase64, String publicKeyFingerprint, String certificateSha256,
            String qrNonce, String signedPayloadBase64, String signatureBase64) {
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.externalBaseUrl = normalizeExternalBaseUrl(externalBaseUrl);
        this.hostId = UUID.fromString(hostId).toString();
        this.publicKeyBase64 = requireText(publicKeyBase64, "Host public key");
        this.publicKeyFingerprint = normalizeFingerprint(publicKeyFingerprint);
        this.certificateSha256 = normalizeFingerprint(certificateSha256);
        this.qrNonce = requireText(qrNonce, "QR enrollment nonce").trim().toUpperCase(Locale.ROOT);
        if (!this.qrNonce.matches("[0-9A-F]{64}")) {
            throw new IllegalArgumentException("QR enrollment nonce format is invalid.");
        }
        this.signedPayloadBase64 = requireText(signedPayloadBase64, "Host signed payload");
        this.signatureBase64 = requireText(signatureBase64, "Host signature");
    }

    /**
     * The compact QR carries only the bootstrap endpoint and the trust pin/nonce.
     * It is not a trusted Host identity until the pinned HTTPS manifest is fetched
     * and its signature and fields are verified.
     */
    static final class BootstrapInfo {
        final int protocolVersion;
        final String baseUrl;
        final String certificateSha256;
        final String qrNonce;

        private BootstrapInfo(int protocolVersion, String baseUrl,
                String certificateSha256, String qrNonce) {
            if (protocolVersion != 1) {
                throw new IllegalArgumentException("지원하지 않는 Remote Host protocol입니다.");
            }
            this.protocolVersion = protocolVersion;
            this.baseUrl = normalizeBootstrapBaseUrl(baseUrl);
            this.certificateSha256 = normalizeFingerprint(certificateSha256);
            String nonce = requireText(qrNonce, "QR enrollment nonce")
                    .trim().toUpperCase(Locale.ROOT);
            if (!nonce.matches("[0-9A-F]{64}")) {
                throw new IllegalArgumentException("QR enrollment nonce format is invalid.");
            }
            this.qrNonce = nonce;
        }
    }

    static boolean isBootstrapQrJson(String json) throws Exception {
        JSONObject value = new JSONObject(json);
        return !hasFullManifestField(value);
    }

    private static boolean hasFullManifestField(JSONObject value) {
        return value.has("hostId") && value.has("publicKeyBase64") &&
                value.has("publicKeyFingerprint") && value.has("signedPayloadBase64") &&
                value.has("signatureBase64");
    }

    static BootstrapInfo fromBootstrapQrJson(String json) throws Exception {
        JSONObject value = new JSONObject(json);
        if (value.length() != 4 ||
                !value.has("protocolVersion") || !value.has("baseUrl") ||
                !value.has("certificateSha256") || !value.has("qrNonce")) {
            throw new IllegalArgumentException(
                    "부트스트랩 QR에는 protocolVersion, baseUrl, certificateSha256, qrNonce만 있어야 합니다.");
        }
        return new BootstrapInfo(
                value.getInt("protocolVersion"),
                value.getString("baseUrl"),
                value.getString("certificateSha256"),
                value.getString("qrNonce"));
    }

    static RemoteHostConnection fromManifestJson(BootstrapInfo bootstrap, String json)
            throws Exception {
        if (bootstrap == null) throw new IllegalArgumentException("Bootstrap QR 정보가 없습니다.");
        RemoteHostConnection manifest = fromQrJson(json);
        if (!bootstrap.baseUrl.equals(manifest.baseUrl)) {
            throw new SecurityException("매니페스트의 PC 주소가 QR 주소와 일치하지 않습니다.");
        }
        if (!bootstrap.certificateSha256.equals(manifest.certificateSha256)) {
            throw new SecurityException("매니페스트의 TLS 인증서 지문이 QR과 일치하지 않습니다.");
        }
        if (!bootstrap.qrNonce.equals(manifest.qrNonce)) {
            throw new SecurityException("매니페스트의 QR nonce가 스캔한 QR과 일치하지 않습니다.");
        }
        return manifest;
    }

    static RemoteHostConnection fromQrJson(String json) throws Exception {
        JSONObject value = new JSONObject(json);
        if (value.optInt("protocolVersion", 0) != 1) {
            throw new IllegalArgumentException("지원하지 않는 Remote Host protocol입니다.");
        }
        return new RemoteHostConnection(
                value.getString("baseUrl"),
                value.optString("externalBaseUrl", ""),
                value.getString("hostId"),
                value.getString("publicKeyBase64"),
                value.getString("publicKeyFingerprint"),
                value.getString("certificateSha256"),
                value.getString("qrNonce"),
                value.getString("signedPayloadBase64"),
                value.getString("signatureBase64"));
    }

    /** Minimal QR trust anchor used to retrieve the full signed Host manifest. */
    static final class BootstrapInfo {
        final int protocolVersion;
        final String baseUrl;
        final String certificateSha256;
        final String qrNonce;

        BootstrapInfo(int protocolVersion, String baseUrl, String certificateSha256, String qrNonce) {
            if (protocolVersion != 1) {
                throw new IllegalArgumentException("지원하지 않는 Remote Host protocol입니다.");
            }
            this.protocolVersion = protocolVersion;
            this.baseUrl = normalizeBaseUrl(baseUrl);
            this.certificateSha256 = normalizeFingerprint(certificateSha256);
            this.qrNonce = requireText(qrNonce, "QR enrollment nonce").trim().toUpperCase(Locale.ROOT);
            if (!this.qrNonce.matches("[0-9A-F]{64}")) {
                throw new IllegalArgumentException("QR enrollment nonce format is invalid.");
            }
        }
    }

    static boolean isBootstrapQrJson(String json) throws Exception {
        JSONObject value = new JSONObject(json);
        boolean hasBootstrapMarker = value.has("protocolVersion") || value.has("baseUrl") ||
                value.has("certificateSha256") || value.has("qrNonce");
        boolean hasFullManifestField = value.has("hostId") || value.has("publicKeyBase64") ||
                value.has("publicKeyFingerprint") || value.has("signedPayloadBase64") ||
                value.has("signatureBase64");
        return hasBootstrapMarker && !hasFullManifestField;
    }

    static BootstrapInfo fromBootstrapQrJson(String json) throws Exception {
        JSONObject value = new JSONObject(json);
        if (!isBootstrapQrJson(json)) {
            throw new IllegalArgumentException("부트스트랩 QR 형식이 아닙니다.");
        }
        Object version = value.get("protocolVersion");
        if (!(version instanceof Number) || ((Number) version).intValue() != 1 ||
                ((Number) version).doubleValue() != 1.0d) {
            throw new IllegalArgumentException("지원하지 않는 Remote Host protocol입니다.");
        }
        return new BootstrapInfo(1, value.getString("baseUrl"),
                value.getString("certificateSha256"), value.getString("qrNonce"));
    }

    static RemoteHostConnection fromManifestJson(JSONObject manifest, BootstrapInfo bootstrap)
            throws Exception {
        if (manifest == null || bootstrap == null) {
            throw new IllegalArgumentException("Host pairing manifest or bootstrap QR is missing.");
        }
        Object version = manifest.get("protocolVersion");
        if (!(version instanceof Number) || ((Number) version).intValue() != bootstrap.protocolVersion ||
                ((Number) version).doubleValue() != bootstrap.protocolVersion) {
            throw new SecurityException("Host manifest protocol does not match the QR.");
        }
        String manifestBaseUrl = normalizeBaseUrl(manifest.getString("baseUrl"));
        String manifestCertificate = normalizeFingerprint(manifest.getString("certificateSha256"));
        String manifestNonce = requireText(manifest.getString("qrNonce"), "Manifest QR nonce")
                .trim().toUpperCase(Locale.ROOT);
        if (!bootstrap.baseUrl.equals(manifestBaseUrl)) {
            throw new SecurityException("Host manifest address does not match the QR.");
        }
        if (!bootstrap.certificateSha256.equals(manifestCertificate)) {
            throw new SecurityException("Host manifest certificate fingerprint does not match the QR.");
        }
        if (!bootstrap.qrNonce.equals(manifestNonce)) {
            throw new SecurityException("Host manifest nonce does not match the QR.");
        }
        return fromQrJson(manifest.toString());
    }

    void verifySignedManifest() throws Exception {
        verifySignedManifest(true);
    }

    void verifyStoredIdentity() throws Exception {
        // The QR has a short registration lifetime; once the user has verified and
        // stored this Host identity, the signed Host/key/certificate binding remains
        // useful after the QR itself expires.
        verifySignedManifest(false);
    }

    private void verifySignedManifest(boolean requireFreshQr) throws Exception {
        byte[] encodedPublicKey = Base64.decode(publicKeyBase64, Base64.DEFAULT);
        byte[] signedPayload = Base64.decode(signedPayloadBase64, Base64.DEFAULT);
        byte[] signatureBytes = Base64.decode(signatureBase64, Base64.DEFAULT);
        try {
            String actualFingerprint = sha256Hex(encodedPublicKey);
            if (!actualFingerprint.equalsIgnoreCase(publicKeyFingerprint)) {
                throw new SecurityException("Host 공개키 fingerprint가 QR 내용과 일치하지 않습니다.");
            }

            ECPublicKey publicKey = (ECPublicKey) KeyFactory.getInstance("EC")
                    .generatePublic(new X509EncodedKeySpec(encodedPublicKey));
            if (publicKey.getParams().getCurve().getField().getFieldSize() != 256) {
                throw new SecurityException("Host Identity 키는 P-256이어야 합니다.");
            }

            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(publicKey);
            verifier.update(signedPayload);
            if (!verifier.verify(p1363ToDer(signatureBytes))) {
                throw new SecurityException("Host 서명 검증에 실패했습니다.");
            }

            String signedText = new String(signedPayload, StandardCharsets.UTF_8);
            String newline = "\n";
            requireSignedLine(signedText, "Wayward-Remote-Host-Connection", true);
            requireSignedLine(signedText, "protocol=1", false);
            requireSignedLine(signedText, "host=" + hostId, false);
            requireSignedLine(signedText, "baseUrl=" + baseUrl, false);
            boolean hasExternalEndpoint = signedText.contains("externalBaseUrl=");
            if (hasExternalEndpoint) {
                requireSignedLine(signedText, "externalBaseUrl=" + externalBaseUrl, false);
            } else if (!externalBaseUrl.isEmpty()) {
                throw new SecurityException("서명되지 않은 외부 Remote Host 주소가 포함되어 있습니다.");
            }
            requireSignedLine(signedText, "publicKeyFingerprint=" + actualFingerprint, false);
            requireSignedLine(signedText, "certificateSha256=" + certificateSha256, false);
            requireSignedLine(signedText, "qrNonce=" + qrNonce, false);

            long issued = parseSignedLong(signedText, "issued");
            long expires = parseSignedLong(signedText, "expires");
            long now = System.currentTimeMillis();
            if (issued > now + 5 * 60_000L || expires <= issued ||
                    (requireFreshQr && expires <= now) || expires - issued > 10 * 60_000L) {
                throw new SecurityException("Host QR이 만료되었거나 유효 시간 범위가 잘못되었습니다.");
            }
        } finally {
            java.util.Arrays.fill(encodedPublicKey, (byte) 0);
            java.util.Arrays.fill(signedPayload, (byte) 0);
            java.util.Arrays.fill(signatureBytes, (byte) 0);
        }
    }

    void verifyPairingApprovalReceipt(byte[] payload, byte[] signatureP1363,
            String expectedRequestId, String expectedDeviceId,
            String expectedDevicePublicKeyFingerprint, int expectedPermissions) throws Exception {
        verifyHostSignature(payload, signatureP1363);
        String text = new String(payload, StandardCharsets.UTF_8);
        requireSignedLine(text, "Wayward-Remote-Host-Pairing-Approval", true);
        requireSignedLine(text, "protocol=1", false);
        requireSignedLine(text, "host=" + hostId, false);
        requireSignedLine(text, "request=" + UUID.fromString(expectedRequestId), false);
        requireSignedLine(text, "device=" + UUID.fromString(expectedDeviceId), false);
        requireSignedLine(text, "fingerprint=" +
                normalizeFingerprint(expectedDevicePublicKeyFingerprint), false);
        long permissions = parseSignedLong(text, "permissions");
        if (permissions != expectedPermissions) {
            throw new SecurityException("Pairing receipt permission flags do not match the signed Host payload.");
        }
        long registeredAt = parseSignedLong(text, "registered");
        if (registeredAt <= 0L || registeredAt > System.currentTimeMillis() + 5 * 60_000L) {
            throw new SecurityException("Pairing receipt registration timestamp is invalid.");
        }
    }

    void verifyHostSignature(byte[] payload, byte[] signatureP1363) throws Exception {
        byte[] encodedPublicKey = Base64.decode(publicKeyBase64, Base64.DEFAULT);
        try {
            ECPublicKey publicKey = (ECPublicKey) KeyFactory.getInstance("EC")
                    .generatePublic(new X509EncodedKeySpec(encodedPublicKey));
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(publicKey);
            verifier.update(payload);
            if (!verifier.verify(p1363ToDer(signatureP1363))) {
                throw new SecurityException("PC Host 서명 또는 Host Identity 검증에 실패했습니다.");
            }
        } finally {
            java.util.Arrays.fill(encodedPublicKey, (byte) 0);
        }
    }

    JSONObject toJson() throws Exception {
        JSONObject value = new JSONObject();
        value.put("protocolVersion", 1);
        value.put("baseUrl", baseUrl);
        if (!externalBaseUrl.isEmpty()) value.put("externalBaseUrl", externalBaseUrl);
        value.put("hostId", hostId);
        value.put("publicKeyBase64", publicKeyBase64);
        value.put("publicKeyFingerprint", publicKeyFingerprint);
        value.put("certificateSha256", certificateSha256);
        value.put("qrNonce", qrNonce);
        value.put("signedPayloadBase64", signedPayloadBase64);
        value.put("signatureBase64", signatureBase64);
        return value;
    }

    static RemoteHostConnection fromStoredJson(String json) throws Exception {
        return fromQrJson(json);
    }

    private static void requireSignedLine(String payload, String line, boolean first) {
        if (first) {
            if (!payload.startsWith(line + "\n")) {
                throw new SecurityException("Host 서명 매니페스트 형식이 올바르지 않습니다.");
            }
        } else if (!payload.contains(line + "\n")) {
            throw new SecurityException("Host 서명 매니페스트에 필수 항목이 없습니다: " + line);
        }
    }

    private static long parseSignedLong(String payload, String name) {
        String prefix = name + "=";
        for (String line : payload.split("\n")) {
            if (line.startsWith(prefix)) {
                return Long.parseLong(line.substring(prefix.length()));
            }
        }
        throw new SecurityException("Host 서명 매니페스트에 " + name + " 시간이 없습니다.");
    }

    static String sha256Hex(byte[] bytes) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
        try {
            StringBuilder out = new StringBuilder(hash.length * 2);
            for (byte value : hash) out.append(String.format(Locale.ROOT, "%02X", value & 0xff));
            return out.toString();
        } finally {
            java.util.Arrays.fill(hash, (byte) 0);
        }
    }

    static String normalizeFingerprint(String value) {
        String normalized = requireText(value, "SHA-256 fingerprint")
                .replace(":", "").trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[0-9A-F]{64}")) {
            throw new IllegalArgumentException("SHA-256 fingerprint 형식이 올바르지 않습니다.");
        }
        return normalized;
    }

    private static String normalizeBootstrapBaseUrl(String value) {
        String trimmed = requireText(value, "Bootstrap Host URL").trim();
        try {
            URI uri = new URI(trimmed);
            String path = uri.getRawPath();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null ||
                    uri.getPort() < 1 || uri.getPort() > 65535 ||
                    uri.getRawUserInfo() != null || uri.getRawQuery() != null ||
                    uri.getRawFragment() != null ||
                    (path != null && !path.isEmpty() && !"/".equals(path)) ||
                    !isPrivateLanIpv4(uri.getHost())) {
                throw new IllegalArgumentException(
                        "부트스트랩 PC 주소는 명시적 포트가 있는 HTTPS 사설 IPv4 루트 주소만 허용합니다.");
            }
            return normalizeBaseUrl(trimmed);
        } catch (java.net.URISyntaxException error) {
            throw new IllegalArgumentException("부트스트랩 PC 주소 형식이 올바르지 않습니다.", error);
        }
    }

    private static String normalizeBaseUrl(String value) {
        String trimmed = requireText(value, "Host URL").trim();
        try {
            URI uri = new URI(trimmed);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null ||
                    uri.getRawUserInfo() != null || uri.getRawQuery() != null ||
                    uri.getRawFragment() != null ||
                    (uri.getRawPath() != null && !uri.getRawPath().isEmpty() && !"/".equals(uri.getRawPath())) ||
                    (uri.getPort() != -1 && (uri.getPort() < 1 || uri.getPort() > 65535)) ||
                    !isPrivateLanIpv4(uri.getHost())) {
                throw new IllegalArgumentException("Remote Play v1은 HTTPS 및 PC의 RFC1918 사설 IPv4 주소만 허용합니다.");
            }
            return trimmed.endsWith("/") ? trimmed : trimmed + "/";
        } catch (java.net.URISyntaxException error) {
            throw new IllegalArgumentException("Remote Host URL 형식이 올바르지 않습니다.", error);
        }
    }

    static String normalizeExternalBaseUrl(String value) {
        if (value == null || value.trim().isEmpty()) return "";
        String trimmed = value.trim();
        try {
            URI uri = new URI(trimmed);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null ||
                    uri.getPort() < 1 || uri.getPort() > 65535 ||
                    uri.getRawUserInfo() != null || uri.getRawQuery() != null ||
                    uri.getRawFragment() != null ||
                    (uri.getRawPath() != null && !uri.getRawPath().isEmpty() && !"/".equals(uri.getRawPath())) ||
                    !isGloballyRoutableIpv4(uri.getHost())) {
                throw new IllegalArgumentException("외부 Remote Host 주소는 HTTPS와 공인 IPv4 주소·포트만 허용합니다.");
            }
            return trimmed.endsWith("/") ? trimmed : trimmed + "/";
        } catch (java.net.URISyntaxException error) {
            throw new IllegalArgumentException("외부 Remote Host URL 형식이 올바르지 않습니다.", error);
        }
    }

    private static boolean isGloballyRoutableIpv4(String host) {
        if (host == null || !host.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) return false;
        String[] octets = host.split("\\.", -1);
        int[] b = new int[4];
        for (int i = 0; i < 4; i++) {
            try { b[i] = Integer.parseInt(octets[i]); }
            catch (NumberFormatException error) { return false; }
            if (b[i] < 0 || b[i] > 255 ||
                    (octets[i].length() > 1 && octets[i].startsWith("0"))) return false;
        }
        if (b[0] == 0 || b[0] == 10 || b[0] == 127 || b[0] >= 224) return false;
        if (b[0] == 100 && b[1] >= 64 && b[1] <= 127) return false;
        if (b[0] == 169 && b[1] == 254) return false;
        if (b[0] == 172 && b[1] >= 16 && b[1] <= 31) return false;
        if (b[0] == 192 && (b[1] == 168 || b[1] == 0 && b[2] <= 2)) return false;
        if (b[0] == 198 && (b[1] == 18 || b[1] == 19 || b[1] == 51 && b[2] == 100)) return false;
        if (b[0] == 203 && b[1] == 0 && b[2] == 113) return false;
        return true;
    }

    private static boolean isPrivateLanIpv4(String host) {
        if (host == null || !host.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) return false;
        String[] octets = host.split("\\.", -1);
        int[] values = new int[4];
        for (int i = 0; i < 4; i++) {
            try { values[i] = Integer.parseInt(octets[i]); }
            catch (NumberFormatException error) { return false; }
            if (values[i] < 0 || values[i] > 255 ||
                    (octets[i].length() > 1 && octets[i].startsWith("0"))) return false;
        }
        return values[0] == 10 ||
                (values[0] == 172 && values[1] >= 16 && values[1] <= 31) ||
                (values[0] == 192 && values[1] == 168);
    }

    private static String requireText(String value, String label) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(label + " 값이 없습니다.");
        }
        return value.trim();
    }

    private static byte[] p1363ToDer(byte[] raw) {
        if (raw == null || raw.length != 64) {
            throw new SecurityException("Host ECDSA signature length is invalid.");
        }
        byte[] r = encodeDerInteger(java.util.Arrays.copyOfRange(raw, 0, 32));
        byte[] s = encodeDerInteger(java.util.Arrays.copyOfRange(raw, 32, 64));
        byte[] sequence = new byte[r.length + s.length];
        System.arraycopy(r, 0, sequence, 0, r.length);
        System.arraycopy(s, 0, sequence, r.length, s.length);
        byte[] result = new byte[sequence.length + 2];
        result[0] = 0x30;
        result[1] = (byte) sequence.length;
        System.arraycopy(sequence, 0, result, 2, sequence.length);
        return result;
    }

    private static byte[] encodeDerInteger(byte[] source) {
        int first = 0;
        while (first < source.length - 1 && source[first] == 0) first++;
        int length = source.length - first;
        boolean prefixZero = (source[first] & 0x80) != 0;
        byte[] value = new byte[length + (prefixZero ? 1 : 0)];
        System.arraycopy(source, first, value, prefixZero ? 1 : 0, length);
        byte[] result = new byte[value.length + 2];
        result[0] = 0x02;
        result[1] = (byte) value.length;
        System.arraycopy(value, 0, result, 2, value.length);
        return result;
    }
}
