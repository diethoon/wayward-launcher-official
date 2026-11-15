package com.wayward.launcher;

import android.content.Context;
import android.net.Uri;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.X509TrustManager;

/**
 * Native-only Remote Host API client. Device private keys stay in Android Keystore;
 * bearer tokens are held only in process memory and are never exposed through WMLHost.
 */
final class RemoteHostClient {
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 20000;
    private static final int MAX_JSON_RESPONSE_BYTES = 4 * 1024 * 1024;
    private static final Object SESSION_LOCK = new Object();
    private static volatile String sessionHostId = "";
    private static volatile String accessToken = "";
    private static volatile long tokenExpiresAtMillis;

    private RemoteHostClient() {}

    static void clearSession() {
        synchronized (SESSION_LOCK) {
            sessionHostId = "";
            accessToken = "";
            tokenExpiresAtMillis = 0L;
        }
    }

    static boolean hasSession(RemoteHostConnection host) {
        return host != null && host.hostId.equals(sessionHostId) &&
                !accessToken.isEmpty() && tokenExpiresAtMillis > System.currentTimeMillis() + 30_000L;
    }

    static JSONObject requestJson(RemoteHostConnection host, String path, String method,
            JSONObject body, String authorization) throws Exception {
        HttpsURLConnection connection = openPinned(host, path, method, body, authorization, null);
        try {
            return new JSONObject(readResponseText(connection, MAX_JSON_RESPONSE_BYTES));
        } finally {
            connection.disconnect();
        }
    }

    static String verifyAndStoreQr(Context context, String qrJson) throws Exception {
        RemoteHostConnection host = RemoteHostConnection.fromQrJson(qrJson);
        host.verifySignedManifest();
        JSONObject identity = requestJson(host, "/v1/host/identity", "GET", null, null);
        verifyIdentityResponse(host, identity);
        RemoteHostStore.saveConnection(context, host);
        clearSession();
        return "PC Host 신원과 TLS 인증서 핀 검증 완료\nHost ID: " + host.hostId;
    }

    static RemoteHostConnection discoverHost(
            RemoteHostMdnsCandidate candidate,
            RemoteHostConnection trustedHost) throws Exception {
        if (candidate == null || trustedHost == null) {
            throw new SecurityException("먼저 실제 PC 화면의 QR을 스캔해 Host를 등록해야 합니다.");
        }
        if (!trustedHost.hostId.equalsIgnoreCase(candidate.hostId) ||
                !trustedHost.publicKeyFingerprint.equalsIgnoreCase(candidate.publicKeyFingerprint) ||
                !trustedHost.certificateSha256.equalsIgnoreCase(candidate.certificateSha256)) {
            throw new SecurityException("mDNS에서 발견한 PC가 이미 등록된 Host Identity와 일치하지 않습니다.");
        }

        String address = candidate.address.getHostAddress();
        String baseUrl = "https://" + address + ":" + candidate.port + "/";
        // Pin with the previously scanned Host certificate, not the untrusted mDNS TXT
        // fingerprint. A hostile LAN peer therefore cannot replace the trust anchor.
        JSONObject identity = requestJsonPinned(baseUrl, trustedHost.certificateSha256,
                "/v1/host/identity", "GET", null, null);
        verifyIdentityResponse(trustedHost, identity);
        if (!trustedHost.hostId.equalsIgnoreCase(identity.getString("hostId")) ||
                !trustedHost.publicKeyFingerprint.equalsIgnoreCase(candidate.publicKeyFingerprint)) {
            throw new SecurityException("mDNS 주소에서 응답한 HTTPS Host가 기존 등록 Host와 다릅니다.");
        }

        String query = "/v1/host/qr?address=" + URLEncoder.encode(address, "UTF-8");
        JSONObject qr = requestJsonPinned(baseUrl, trustedHost.certificateSha256,
                query, "GET", null, null);
        RemoteHostConnection refreshed = RemoteHostConnection.fromQrJson(qr.toString());
        refreshed.verifySignedManifest();
        if (!refreshed.hostId.equalsIgnoreCase(trustedHost.hostId) ||
                !refreshed.certificateSha256.equalsIgnoreCase(trustedHost.certificateSha256) ||
                !refreshed.publicKeyFingerprint.equalsIgnoreCase(trustedHost.publicKeyFingerprint) ||
                !refreshed.publicKeyBase64.equals(trustedHost.publicKeyBase64) ||
                !refreshed.baseUrl.equalsIgnoreCase(baseUrl)) {
            throw new SecurityException("새 IP 주소가 기존 Host Identity 및 서명된 연결 매니페스트와 일치하지 않습니다.");
        }
        return refreshed;
    }

    private static void verifyIdentityResponse(RemoteHostConnection host, JSONObject identity) throws Exception {
        if (!host.hostId.equalsIgnoreCase(identity.getString("hostId")) ||
                !host.publicKeyFingerprint.equalsIgnoreCase(identity.getString("publicKeyFingerprint")) ||
                !host.certificateSha256.equalsIgnoreCase(identity.getString("certificateSha256")) ||
                !host.publicKeyBase64.equals(identity.getString("publicKeyBase64"))) {
            throw new SecurityException("QR Host 신원 정보가 TLS로 조회한 Host Identity와 다릅니다.");
        }
    }

    static String pairAndWaitForApproval(Context context, RemoteHostConnection host) throws Exception {
        String deviceId = RemoteHostStore.getDeviceId(context);
        KeyPair keyPair = RemoteHostStore.getDeviceKeyPair(context);
        String displayName = RemoteHostStore.getDeviceName(context);
        JSONObject startBody = new JSONObject()
                .put("deviceId", deviceId)
                .put("displayName", displayName)
                .put("publicKeySubjectPublicKeyInfoBase64",
                        Base64.encodeToString(keyPair.getPublic().getEncoded(), Base64.NO_WRAP));
        JSONObject start = requestJson(host, "/v1/pairing/requests", "POST", startBody, null);
        if (!host.hostId.equalsIgnoreCase(start.getString("hostId"))) {
            throw new SecurityException("Pairing 응답의 Host ID가 등록하려는 PC와 다릅니다.");
        }

        byte[] proofPayload = Base64.decode(start.getString("proofPayloadBase64"), Base64.DEFAULT);
        byte[] hostSignature = Base64.decode(start.getString("hostSignatureBase64"), Base64.DEFAULT);
        byte[] deviceSignature = null;
        try {
            host.verifyHostSignature(proofPayload, hostSignature);
            deviceSignature = signDevicePayload(keyPair, proofPayload);
            JSONObject proveBody = new JSONObject()
                    .put("pollingSecretBase64", start.getString("pollingSecretBase64"))
                    .put("signatureBase64", Base64.encodeToString(deviceSignature, Base64.NO_WRAP));
            JSONObject prove = requestJson(host,
                    "/v1/pairing/requests/" + start.getString("requestId") + "/prove",
                    "POST", proveBody, null);
            if (!prove.optBoolean("accepted")) {
                throw new SecurityException("PC Host가 Device Key 증명을 거부했습니다: " + prove.optString("failure"));
            }
        } finally {
            java.util.Arrays.fill(proofPayload, (byte) 0);
            java.util.Arrays.fill(hostSignature, (byte) 0);
            if (deviceSignature != null) java.util.Arrays.fill(deviceSignature, (byte) 0);
        }

        String pollingSecret = start.getString("pollingSecretBase64");
        long deadline = System.currentTimeMillis() + 10 * 60_000L;
        String requestId = start.getString("requestId");
        while (System.currentTimeMillis() < deadline) {
            JSONObject state = requestJson(host,
                    "/v1/pairing/requests/" + requestId + "/status",
                    "GET", null, "Pairing " + pollingSecret);
            switch (pairingState(state.opt("state"))) {
                case "Approved":
                    JSONObject receipt = state.getJSONObject("receipt");
                    if (!host.hostId.equalsIgnoreCase(receipt.getString("hostId")) ||
                            !deviceId.equalsIgnoreCase(receipt.getString("deviceId"))) {
                        throw new SecurityException("Pairing 승인 영수증의 Host/Device ID가 일치하지 않습니다.");
                    }
                    byte[] receiptPayload = Base64.decode(receipt.getString("signedPayloadBase64"), Base64.DEFAULT);
                    byte[] receiptSignature = Base64.decode(receipt.getString("signatureBase64"), Base64.DEFAULT);
                    try {
                        host.verifyHostSignature(receiptPayload, receiptSignature);
                    } finally {
                        java.util.Arrays.fill(receiptPayload, (byte) 0);
                        java.util.Arrays.fill(receiptSignature, (byte) 0);
                    }
                    authenticate(context, host);
                    return "PC Manager에서 기기 승인이 완료되었습니다.\nHost ID: " + host.hostId +
                            "\nDevice ID: " + deviceId +
                            "\n권한 비트: " + receipt.optInt("permissions");
                case "Rejected":
                    throw new IOException("PC Manager에서 Pairing 요청을 거부했습니다.");
                case "Expired":
                    throw new IOException("Pairing 요청이 만료되었습니다.");
                default:
                    Thread.sleep(2000L);
            }
        }
        throw new IOException("PC Manager 승인 대기 시간이 만료되었습니다.");
    }

    static String authenticate(Context context, RemoteHostConnection host) throws Exception {
        synchronized (SESSION_LOCK) {
            if (host.hostId.equals(sessionHostId) && !accessToken.isEmpty() &&
                    tokenExpiresAtMillis > System.currentTimeMillis() + 60_000L) {
                return accessToken;
            }

            KeyPair deviceKey = RemoteHostStore.getDeviceKeyPair(context);
            String deviceId = RemoteHostStore.getDeviceId(context);
            JSONObject challenge = requestJson(host, "/v1/auth/challenges", "POST",
                    new JSONObject().put("deviceId", deviceId), null);
            if (!host.hostId.equalsIgnoreCase(challenge.getString("hostId"))) {
                throw new SecurityException("인증 Challenge가 다른 Host에서 발급되었습니다.");
            }

            byte[] payload = Base64.decode(challenge.getString("proofPayloadBase64"), Base64.DEFAULT);
            byte[] hostSignature = Base64.decode(challenge.getString("hostSignatureBase64"), Base64.DEFAULT);
            byte[] deviceSignature = null;
            try {
                host.verifyHostSignature(payload, hostSignature);
                deviceSignature = signDevicePayload(deviceKey, payload);
                JSONObject complete = requestJson(host,
                        "/v1/auth/challenges/" + challenge.getString("challengeId") + "/complete",
                        "POST",
                        new JSONObject().put("signatureBase64",
                                Base64.encodeToString(deviceSignature, Base64.NO_WRAP)),
                        null);
                String newToken = complete.getString("accessToken");
                long expires = Instant.parse(complete.getString("expiresAtUtc")).toEpochMilli();
                if (expires <= System.currentTimeMillis() || expires > System.currentTimeMillis() + 20 * 60_000L) {
                    throw new SecurityException("PC Host가 유효하지 않은 Session 만료 시간을 반환했습니다.");
                }
                accessToken = newToken;
                sessionHostId = host.hostId;
                tokenExpiresAtMillis = expires;
                return accessToken;
            } finally {
                java.util.Arrays.fill(payload, (byte) 0);
                java.util.Arrays.fill(hostSignature, (byte) 0);
                if (deviceSignature != null) java.util.Arrays.fill(deviceSignature, (byte) 0);
            }
        }
    }

    static JSONArray getWorkspaceRoots(Context context, RemoteHostConnection host) throws Exception {
        String token = authenticate(context, host);
        HttpsURLConnection connection = openPinned(host, "/v1/files/roots", "GET", null, "Bearer " + token, null);
        try {
            return new JSONArray(readResponseText(connection, MAX_JSON_RESPONSE_BYTES));
        } catch (IOException unauthorized) {
            if (!unauthorized.getMessage().contains("HTTP 401")) throw unauthorized;
            clearSession();
            token = authenticate(context, host);
            connection.disconnect();
            connection = openPinned(host, "/v1/files/roots", "GET", null, "Bearer " + token, null);
            return new JSONArray(readResponseText(connection, MAX_JSON_RESPONSE_BYTES));
        } finally {
            connection.disconnect();
        }
    }

    static HttpsURLConnection openGameResource(Context context, RemoteHostConnection host,
            String rootId, String encodedRelativePath, Map<String, String> requestHeaders) throws Exception {
        if (rootId == null || rootId.trim().isEmpty()) {
            throw new IOException("PC 게임 폴더를 선택하지 않았습니다.");
        }
        if (encodedRelativePath == null || !encodedRelativePath.startsWith("/") ||
                encodedRelativePath.contains("\\") || encodedRelativePath.contains("/../") ||
                encodedRelativePath.endsWith("/..")) {
            throw new IOException("원격 게임 리소스 경로가 올바르지 않습니다.");
        }
        String path = "/v1/game" + encodedRelativePath + "?rootId=" +
                URLEncoder.encode(rootId, "UTF-8");
        String token = authenticate(context, host);
        HttpsURLConnection connection = openPinned(host, path, "GET", null, "Bearer " + token, requestHeaders);
        int status = connection.getResponseCode();
        if (status == HttpURLConnection.HTTP_UNAUTHORIZED) {
            connection.disconnect();
            clearSession();
            token = authenticate(context, host);
            connection = openPinned(host, path, "GET", null, "Bearer " + token, requestHeaders);
        }
        return connection;
    }

    static HttpsURLConnection openAuthenticatedConnection(Context context, RemoteHostConnection host,
            String path, String method, Map<String, String> requestHeaders) throws Exception {
        String token = authenticate(context, host);
        HttpsURLConnection connection = openPinned(
                host, path, method, null, "Bearer " + token, requestHeaders);
        if (connection.getResponseCode() == HttpURLConnection.HTTP_UNAUTHORIZED) {
            connection.disconnect();
            clearSession();
            token = authenticate(context, host);
            connection = openPinned(host, path, method, null, "Bearer " + token, requestHeaders);
        }
        return connection;
    }

    static HttpsURLConnection openAuthenticatedUpload(Context context, RemoteHostConnection host,
            String path, String contentType, long contentLength, InputStream source) throws Exception {
        if (contentLength <= 0 || source == null) {
            throw new IllegalArgumentException("A non-empty upload stream and length are required.");
        }
        String token = authenticate(context, host);
        HttpsURLConnection connection = openPinned(host, path, "PUT", null, "Bearer " + token, null);
        try {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", contentType);
            connection.setFixedLengthStreamingMode(contentLength);
            try (OutputStream output = connection.getOutputStream()) {
                byte[] buffer = new byte[64 * 1024];
                long written = 0;
                int count;
                while ((count = source.read(buffer)) != -1) {
                    if (count == 0) continue;
                    written += count;
                    if (written > contentLength) {
                        throw new IOException("Upload stream exceeded the declared Content-Length.");
                    }
                    output.write(buffer, 0, count);
                }
                if (written != contentLength) {
                    throw new IOException("Upload stream length did not match the declared Content-Length.");
                }
                output.flush();
            }
            return connection;
        } catch (Exception error) {
            connection.disconnect();
            throw error;
        }
    }

    static JSONObject readJsonResponse(HttpsURLConnection connection) throws Exception {
        return new JSONObject(readResponseText(connection, MAX_JSON_RESPONSE_BYTES));
    }

    private static JSONObject requestJsonPinned(String baseUrl, String certificatePin,
            String path, String method, JSONObject body, String authorization) throws Exception {
        HttpsURLConnection connection = openPinned(baseUrl, certificatePin, path, method, body, authorization, null);
        try {
            return new JSONObject(readResponseText(connection, MAX_JSON_RESPONSE_BYTES));
        } finally {
            connection.disconnect();
        }
    }

    private static HttpsURLConnection openPinned(RemoteHostConnection host, String path,
            String method, JSONObject body, String authorization, Map<String, String> forwardedHeaders) throws Exception {
        return openPinned(host.baseUrl, host.certificateSha256, path, method, body, authorization, forwardedHeaders);
    }

    private static HttpsURLConnection openPinned(String baseUrl, String certificatePin, String path,
            String method, JSONObject body, String authorization, Map<String, String> forwardedHeaders) throws Exception {
        if (path == null || !path.startsWith("/") || path.startsWith("//")) {
            throw new IllegalArgumentException("Host API path must be an absolute relative path.");
        }
        URL url = new URL(baseUrl.trim().replaceAll("/+$", "") + path);
        if (!"https".equalsIgnoreCase(url.getProtocol())) {
            throw new SecurityException("Remote Host transport must use HTTPS.");
        }
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestMethod(method);
        connection.setInstanceFollowRedirects(false);
        connection.setUseCaches(true);
        connection.setRequestProperty("Accept", "application/json");
        configurePinnedTls(connection, certificatePin);
        if (authorization != null) connection.setRequestProperty("Authorization", authorization);
        if (forwardedHeaders != null) {
            for (String name : new String[] {"Range", "If-Range", "If-None-Match", "If-Modified-Since", "Cache-Control", "Accept"}) {
                String value = null;
                for (Map.Entry<String, String> entry : forwardedHeaders.entrySet()) {
                    if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) {
                        value = entry.getValue();
                        break;
                    }
                }
                if (value != null && !value.isEmpty()) connection.setRequestProperty(name, value);
            }
        }
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(bytes);
                output.flush();
            } finally {
                java.util.Arrays.fill(bytes, (byte) 0);
            }
        }
        return connection;
    }

    private static String readResponseText(HttpsURLConnection connection, int maxBytes) throws Exception {
        int status = connection.getResponseCode();
        InputStream stream = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
        byte[] bytes = readLimited(stream, maxBytes);
        String result = new String(bytes, StandardCharsets.UTF_8);
        java.util.Arrays.fill(bytes, (byte) 0);
        if (status < 200 || status >= 300) {
            throw new IOException("Host API returned HTTP " + status + ": " + result);
        }
        return result;
    }

    private static byte[] readLimited(InputStream input, int maxBytes) throws Exception {
        if (input == null) return new byte[0];
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int count;
            while ((count = stream.read(buffer)) >= 0) {
                if (count == 0) continue;
                total += count;
                if (total > maxBytes) throw new IOException("Host response exceeded the configured size limit.");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static void configurePinnedTls(HttpsURLConnection connection, String fingerprint) throws Exception {
        final byte[] expected = hexBytes(RemoteHostConnection.normalizeFingerprint(fingerprint));
        X509TrustManager trustManager = new X509TrustManager() {
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {
                throw new UnsupportedOperationException("This client does not accept TLS client certificates.");
            }
            @Override public void checkServerTrusted(X509Certificate[] chain, String authType)
                    throws java.security.cert.CertificateException {
                if (chain == null || chain.length == 0) {
                    throw new java.security.cert.CertificateException("Host TLS server did not provide a certificate.");
                }
                try {
                    chain[0].checkValidity();
                    byte[] actual = MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded());
                    boolean match = MessageDigest.isEqual(expected, actual);
                    java.util.Arrays.fill(actual, (byte) 0);
                    if (!match) throw new java.security.cert.CertificateException("Remote Host TLS certificate pin mismatch.");
                } catch (java.security.cert.CertificateException error) {
                    throw error;
                } catch (Exception error) {
                    throw new java.security.cert.CertificateException("Could not validate Remote Host TLS certificate.", error);
                }
            }
        };
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new X509TrustManager[] {trustManager}, new java.security.SecureRandom());
        connection.setSSLSocketFactory(context.getSocketFactory());
        // The certificate pin is bound into the Host-Identity-signed QR manifest.
        // It replaces hostname PKI validation only after exact DER pin verification.
        connection.setHostnameVerifier((hostname, session) -> true);
    }

    private static byte[] signDevicePayload(KeyPair keyPair, byte[] payload) throws Exception {
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(keyPair.getPrivate());
        signer.update(payload);
        return derToP1363(signer.sign());
    }

    private static byte[] derToP1363(byte[] der) {
        if (der == null || der.length < 8) {
            throw new SecurityException("Invalid Android Keystore ECDSA signature.");
        }
        int[] offset = new int[] {0};
        if ((der[offset[0]++] & 0xff) != 0x30) {
            throw new SecurityException("Invalid ECDSA DER sequence.");
        }
        int sequenceLength = readDerLength(der, offset);
        if (sequenceLength != der.length - offset[0]) {
            throw new SecurityException("Invalid ECDSA DER sequence length.");
        }

        byte[] r = readDerInteger(der, offset);
        byte[] s = readDerInteger(der, offset);
        if (offset[0] != der.length) {
            throw new SecurityException("Unexpected trailing data in ECDSA signature.");
        }
        byte[] result = new byte[64];
        copyFixed(r, result, 0);
        copyFixed(s, result, 32);
        java.util.Arrays.fill(r, (byte) 0);
        java.util.Arrays.fill(s, (byte) 0);
        return result;
    }

    private static int readDerLength(byte[] bytes, int[] offset) {
        if (offset[0] >= bytes.length) throw new SecurityException("Truncated DER length.");
        int first = bytes[offset[0]++] & 0xff;
        if (first < 0x80) return first;
        int count = first & 0x7f;
        if (count < 1 || count > 2 || offset[0] + count > bytes.length) {
            throw new SecurityException("Invalid DER length encoding.");
        }
        int value = 0;
        for (int i = 0; i < count; i++) {
            value = (value << 8) | (bytes[offset[0]++] & 0xff);
        }
        if (value < 0x80) throw new SecurityException("Non-canonical DER length.");
        return value;
    }

    private static byte[] readDerInteger(byte[] bytes, int[] offset) {
        if (offset[0] >= bytes.length || (bytes[offset[0]++] & 0xff) != 0x02) {
            throw new SecurityException("Invalid ECDSA DER integer.");
        }
        int length = readDerLength(bytes, offset);
        if (length < 1 || offset[0] + length > bytes.length) {
            throw new SecurityException("Truncated ECDSA DER integer.");
        }
        int first = offset[0];
        int end = first + length;
        if ((bytes[first] & 0x80) != 0) {
            throw new SecurityException("ECDSA DER integer must be positive.");
        }
        while (first < end - 1 && bytes[first] == 0) first++;
        byte[] value = java.util.Arrays.copyOfRange(bytes, first, end);
        offset[0] = end;
        if (value.length > 32) throw new SecurityException("ECDSA P-256 integer is too large.");
        return value;
    }

    private static void copyFixed(byte[] value, byte[] target, int destination) {
        if (value.length > 32) throw new SecurityException("ECDSA P-256 integer is too large.");
        System.arraycopy(value, 0, target, destination + 32 - value.length, value.length);
    }

    private static byte[] hexBytes(String value) {
        String normalized = value.replace(":", "").trim();
        if (!normalized.matches("(?i)[0-9a-f]{64}")) {
            throw new IllegalArgumentException("TLS pin must be a SHA-256 hexadecimal fingerprint.");
        }
        byte[] result = new byte[32];
        for (int i = 0; i < 32; i++) {
            result[i] = (byte) Integer.parseInt(normalized.substring(i * 2, i * 2 + 2), 16);
        }
        return result;
    }

    private static String pairingState(Object value) {
        if (value instanceof Number) {
            switch (((Number) value).intValue()) {
                case 2: return "Approved";
                case 3: return "Rejected";
                case 4: return "Expired";
                case 1: return "AwaitingApproval";
                default: return "AwaitingProof";
            }
        }
        return value == null ? "Unknown" : value.toString();
    }
}
