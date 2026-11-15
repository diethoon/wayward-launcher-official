package com.wayward.launcher;

import android.content.Context;
import android.net.Uri;
import android.util.Base64;

import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Maps the legacy Wayward HTML ImageGen API to the paired Host's authenticated game-image API. */
final class RemoteGameImageApiClient {
    private RemoteGameImageApiClient() {}

    static JSONObject execute(Context context, String method, String rawPath, byte[] requestBytes) throws Exception {
        if (context == null) throw new IllegalArgumentException("Launcher context is required.");
        String safeMethod = method == null ? "GET" : method.toUpperCase(Locale.ROOT);
        Uri uri = Uri.parse("https://wayward.invalid" + rawPath);
        String path = uri.getPath();
        if (path == null || !path.startsWith("/api/") || path.contains("\\") ||
                path.contains("/../") || path.endsWith("/..")) {
            throw new SecurityException("Remote ImageGen API path is invalid.");
        }

        RemoteHostConnection host = RemoteHostStore.loadConnection(context);
        String rootId = RemoteHostStore.getSelectedRootId(context);
        if (!RemoteHostStore.isPaired(context) || host == null ||
                rootId == null || !rootId.matches("(?i)[0-9a-f]{16}")) {
            throw new IllegalStateException("페어링된 PC와 승인된 PC 게임 폴더를 먼저 선택하세요.");
        }

        String rootQuery = "rootId=" + URLEncoder.encode(rootId, StandardCharsets.UTF_8.name());
        String target;
        String expectedMethod;
        if ("/api/image/health".equals(path)) {
            target = "/v1/game-image-api/health?" + rootQuery;
            expectedMethod = "GET";
        } else if ("/api/image/generate".equals(path)) {
            target = "/v1/game-image-api/image/generate?" + rootQuery;
            expectedMethod = "POST";
        } else if ("/api/image/cancel".equals(path)) {
            target = "/v1/game-image-api/image/cancel?" + rootQuery;
            expectedMethod = "POST";
        } else if ("/api/image-hit".equals(path)) {
            target = "/v1/game-image-api/image-hit";
            expectedMethod = "POST";
        } else if ("/api/batch/enqueue".equals(path)) {
            target = "/v1/game-image-api/batch/enqueue?" + rootQuery;
            expectedMethod = "POST";
        } else if ("/api/batch/status".equals(path)) {
            target = "/v1/game-image-api/batch/status?" + rootQuery;
            expectedMethod = "GET";
        } else if (path.matches("^/api/image/status/[0-9a-fA-F-]{36}$") &&
                path.substring("/api/image/status/".length()).matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            String promptId = path.substring("/api/image/status/".length());
            target = "/v1/game-image-api/image/status/" + promptId + "?" + rootQuery;
            expectedMethod = "GET";
        } else if (path.matches("^/api/batch/(pause|resume|clear)$")) {
            String action = path.substring("/api/batch/".length());
            target = "/v1/game-image-api/batch/" + action + "?" + rootQuery;
            expectedMethod = "POST";
        } else {
            throw new SecurityException("Remote ImageGen API route is not on the allowlist.");
        }

        if (!expectedMethod.equals(safeMethod)) {
            throw new SecurityException("Remote ImageGen API method does not match the allowlist.");
        }
        JSONObject body = null;
        if (requestBytes != null && requestBytes.length > 0) {
            if (requestBytes.length > 4 * 1024 * 1024) {
                throw new IllegalArgumentException("Remote ImageGen JSON body exceeds the 4 MiB limit.");
            }
            body = new JSONObject(new String(requestBytes, StandardCharsets.UTF_8));
        }
        return RemoteHostClient.requestAuthenticatedJsonEnvelope(context, host, target, safeMethod, body);
    }

    static JSONObject offlineResponse(String message) {
        JSONObject result = new JSONObject();
        try {
            JSONObject body = new JSONObject().put("error", message == null ? "Remote PC Host 연결 오류" : message);
            result.put("status", 503);
            result.put("statusText", "Remote Host Unavailable");
            result.put("headers", new JSONObject().put("Content-Type", "application/json; charset=utf-8"));
            result.put("bodyBase64", Base64.encodeToString(body.toString().getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
        } catch (Exception ignored) {}
        return result;
    }
}
