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
    final String hostId;
    final String publicKeyBase64;
    final String publicKeyFingerprint;
    final String certificateSha256;
    final String signedPayloadBase64;
    final String signatureBase64;

    RemoteHostConnection(String baseUrl, String hostId, String publicKeyBase64,
            String publicKeyFingerprint, String certificateSha256,
            String signedPayloadBase64, String signatureBase64) {
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.hostId = UUID.fromString(hostId).toString();
        this.publicKeyBase64 = requireText(publicKeyBase64, "Host public key");
        this.publicKeyFingerprint = normalizeFingerprint(publicKeyFingerprint);
        this.certificateSha256 = normalizeFingerprint(certificateSha256);
        this.signedPayloadBase64 = requireText(signedPayloadBase64, "Host signed payload");
        this.signatureBase64 = requireText(signatureBase64, "Host signature");
    }

    static RemoteHostConnection fromQrJson(String json) throws Exception {
        JSONObject value = new JSONObject(json);
        if (value.optInt("protocolVersion", 0) != 1) {
            throw new IllegalArgumentException("지원하지 않는 Remote Host protocol입니다.");
        }
        return new RemoteHostConnection(
                value.getString("baseUrl"),
                value.getString("hostId"),
                value.getString("publicKeyBase64"),
                value.getString("publicKeyFingerprint"),
                value.getString("certificateSha256"),
                value.getString("signedPayloadBase64"),
                value.getString("signatureBase64"));
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
            requireSignedLine(signedText, "publicKeyFingerprint=" + actualFingerprint, false);
            requireSignedLine(signedText, "certificateSha256=" + certificateSha256, false);

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
        value.put("hostId", hostId);
        value.put("publicKeyBase64", publicKeyBase64);
        value.put("publicKeyFingerprint", publicKeyFingerprint);
        value.put("certificateSha256", certificateSha256);
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

    private static String normalizeBaseUrl(String value) {
        String trimmed = requireText(value, "Host URL").trim();
        try {
            URI uri = new URI(trimmed);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null ||
                    uri.getRawUserInfo() != null || uri.getRawQuery() != null ||
                    uri.getRawFragment() != null ||
                    (uri.getRawPath() != null && !uri.getRawPath().isEmpty() && !"/".equals(uri.getRawPath())) ||
                    (uri.getPort() != -1 && (uri.getPort() < 1 || uri.getPort() > 65535))) {
                throw new IllegalArgumentException("Remote Host 주소는 HTTPS 서버 주소만 허용합니다.");
            }
            return trimmed.endsWith("/") ? trimmed : trimmed + "/";
        } catch (java.net.URISyntaxException error) {
            throw new IllegalArgumentException("Remote Host URL 형식이 올바르지 않습니다.", error);
        }
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
