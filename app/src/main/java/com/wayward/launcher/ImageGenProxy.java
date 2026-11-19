package com.wayward.launcher;

import android.net.Uri;
import android.util.Base64;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Narrow WebView-to-loopback proxy for the Wayward ImageGen API only. */
final class ImageGenProxy {
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 15 * 60 * 1000;
    private static final int MAX_REQUEST_BYTES = 8 * 1024 * 1024;
    private static final int MAX_RESPONSE_BYTES = 64 * 1024 * 1024;

    private ImageGenProxy() {}

    static void dispatch(WebView web, String requestId, String method,
                         String pathAndQuery, String headersJson, String bodyBase64,
                         boolean remotePcMode) {
        if (web == null || requestId == null || !requestId.matches("[A-Za-z0-9._-]{1,80}")) return;
        final String safeMethod = method == null ? "GET" : method.toUpperCase(Locale.ROOT);
        final String safePath = validatePath(pathAndQuery);
        if (safePath == null || !allowedMethod(safeMethod)) {
            deliver(web, requestId, errorResponse(400, "허용되지 않은 ImageGen 요청입니다."));
            return;
        }
        final android.content.Context appContext=web.getContext().getApplicationContext();
        final String apiPath=Uri.parse(safePath).getPath();
        if (remotePcMode) {
            if (!RemoteHostStore.isReady(appContext)) {
                deliver(web, requestId, errorResponse(503,
                        "PC 페어링과 승인된 PC 폴더 선택이 필요합니다."));
                return;
            }
            new Thread(() -> {
                byte[] requestBody = null;
                try {
                    requestBody = bodyBase64 == null || bodyBase64.isEmpty()
                            ? new byte[0] : Base64.decode(bodyBase64, Base64.DEFAULT);
                    if (requestBody.length > 4 * 1024 * 1024) {
                        deliver(web, requestId, errorResponse(413, "Remote ImageGen 요청이 4 MiB 제한을 초과했습니다."));
                        return;
                    }
                    JSONObject envelope = RemoteGameImageApiClient.execute(
                            appContext, safeMethod, safePath, requestBody);
                    deliver(web, requestId, envelope.toString());
                    JSONObject diagnostic = new JSONObject();
                    try {
                        diagnostic.put("method", safeMethod);
                        diagnostic.put("path", apiPath);
                        diagnostic.put("status", envelope.optInt("status", 502));
                    } catch (Exception ignored) {}
                    DiagnosticsLogger.event(appContext, "INFO", "remote_game_image_api",
                            "authenticated_host_api_response", diagnostic);
                } catch (Exception error) {
                    JSONObject diagnostic = new JSONObject();
                    try {
                        diagnostic.put("method", safeMethod);
                        diagnostic.put("path", apiPath);
                        diagnostic.put("exception", error.getClass().getSimpleName());
                    } catch (Exception ignored) {}
                    DiagnosticsLogger.event(appContext, "ERROR", "remote_game_image_api",
                            "authenticated_host_api_error", diagnostic);
                    deliver(web, requestId, RemoteGameImageApiClient.offlineResponse(
                            error.getMessage() == null ? "Remote PC 이미지 API에 연결하지 못했습니다." : error.getMessage()).toString());
                } finally {
                    if (requestBody != null) java.util.Arrays.fill(requestBody, (byte) 0);
                }
            }, "wayward-remote-game-image-api").start();
            return;
        }
        ImageGenStatusStore.State runtime=ImageGenStatusStore.read(appContext.getFilesDir());
        boolean healthRequest="/api/image/health".equals(apiPath);
        if(healthRequest){
            if(!runtime.isAvailable()&&!runtime.isStarting()){
                deliver(web,requestId,errorResponse(503,"이미지 생성 서버가 실행되지 않았습니다. 이미지팩 전용 모드입니다."));
                return;
            }
        }else if(!runtime.isReady()){
            deliver(web,requestId,errorResponse(503,"이미지 생성 서버가 꺼져 있습니다. 외부 생성 서버로 전환하지 않습니다."));
            return;
        }
        final long startedAt=System.currentTimeMillis();
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                byte[] requestBody = bodyBase64 == null || bodyBase64.isEmpty()
                        ? new byte[0] : Base64.decode(bodyBase64, Base64.DEFAULT);
                if (requestBody.length > MAX_REQUEST_BYTES) {
                    deliver(web, requestId, errorResponse(413, "ImageGen 요청 본문이 너무 큽니다."));
                    return;
                }
                if ("PUT".equals(safeMethod) && "/api/generation/settings".equals(Uri.parse(safePath).getPath())
                        && requestBody.length > 0) {
                    try {
                        // The launcher owns the provider and credential. Game-side
                        // EasyGen settings may tune generation options, but cannot
                        // switch the engine or replace/clear the AndroidKeyStore token.
                        JSONObject settings = new JSONObject(new String(requestBody, StandardCharsets.UTF_8));
                        settings.remove("naiToken");
                        settings.remove("naiTokenClear");
                        settings.put("imageProvider", "nai");
                        requestBody = settings.toString().getBytes(StandardCharsets.UTF_8);
                        DiagnosticsLogger.event(appContext, "INFO", "imagegen_proxy",
                                "game_generation_settings_locked_to_novelai", null);
                    } catch (Exception settingsError) {
                        DiagnosticsLogger.error(appContext, "imagegen_proxy",
                                "generation_settings_policy_failed", settingsError);
                        deliver(web, requestId, errorResponse(400, "게임 이미지 설정을 안전하게 처리하지 못했습니다."));
                        return;
                    }
                }

                URL url = new URL("http://127.0.0.1:8189" + safePath);
                connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
                connection.setReadTimeout(READ_TIMEOUT_MS);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestMethod(safeMethod);
                connection.setUseCaches(false);

                JSONObject requestHeaders = headersJson == null || headersJson.isEmpty()
                        ? new JSONObject() : new JSONObject(headersJson);
                Iterator<String> keys = requestHeaders.keys();
                while (keys.hasNext()) {
                    String name = keys.next();
                    String lower = name.toLowerCase(Locale.ROOT);
                    if (lower.equals("host") || lower.equals("origin") || lower.equals("referer")
                            || lower.equals("cookie") || lower.equals("connection")
                            || lower.equals("content-length") || lower.equals("accept-encoding")
                            || lower.equals("upgrade")) continue;
                    Object value = requestHeaders.opt(name);
                    if (value != null && value != JSONObject.NULL) {
                        connection.setRequestProperty(name, String.valueOf(value));
                    }
                }

                if (requestBody.length > 0 && !safeMethod.equals("GET") && !safeMethod.equals("HEAD")) {
                    connection.setDoOutput(true);
                    connection.setFixedLengthStreamingMode(requestBody.length);
                    try (OutputStream out = connection.getOutputStream()) {
                        out.write(requestBody);
                        out.flush();
                    }
                }

                int status = connection.getResponseCode();
                JSONObject diagnostic=new JSONObject();
                try{diagnostic.put("method",safeMethod);diagnostic.put("path",Uri.parse(safePath).getPath());diagnostic.put("status",status);diagnostic.put("elapsedMs",System.currentTimeMillis()-startedAt);}catch(Exception ignored){}
                DiagnosticsLogger.event(appContext,status>=400?"WARN":"INFO","imagegen_proxy","api_response",diagnostic);
                InputStream responseStream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
                byte[] responseBody = responseStream == null ? new byte[0]
                        : readLimited(responseStream, MAX_RESPONSE_BYTES);
                if (responseStream != null) responseStream.close();
                if ("GET".equals(safeMethod) && apiPath != null
                        && apiPath.startsWith("/api/image/status/")) {
                    recordImageJobStatus(appContext, apiPath, responseBody, status,
                            System.currentTimeMillis() - startedAt);
                }

                JSONObject payload = new JSONObject();
                payload.put("status", status);
                payload.put("statusText", safeStatus(connection.getResponseMessage()));
                payload.put("headers", responseHeaders(connection.getHeaderFields()));
                payload.put("bodyBase64", Base64.encodeToString(responseBody, Base64.NO_WRAP));
                deliver(web, requestId, payload.toString());
            } catch (Exception e) {
                JSONObject diagnostic=new JSONObject();
                try{diagnostic.put("method",safeMethod);diagnostic.put("exception",e.getClass().getSimpleName());diagnostic.put("elapsedMs",System.currentTimeMillis()-startedAt);}catch(Exception ignored){}
                DiagnosticsLogger.event(appContext,"ERROR","imagegen_proxy","api_transport_error",diagnostic);
                deliver(web, requestId, errorResponse(502, e.getMessage() == null
                        ? "ImageGen 서버에 연결하지 못했습니다." : e.getMessage()));
            } finally {
                if (connection != null) connection.disconnect();
            }
        }, "wayward-imagegen-proxy").start();
    }

    private static void recordImageJobStatus(android.content.Context context, String path,
                                             byte[] responseBody, int httpStatus, long elapsedMs) {
        JSONObject diagnostic = new JSONObject();
        try {
            JSONObject response = new JSONObject(new String(responseBody, StandardCharsets.UTF_8));
            JSONObject job = response.optJSONObject("job");
            if (job == null) job = response;
            String promptId = response.optString("promptId", "");
            if (promptId.isEmpty()) promptId = job.optString("promptId", "");
            if (promptId.isEmpty()) {
                int slash = path.lastIndexOf('/');
                promptId = slash >= 0 ? path.substring(slash + 1) : "";
            }
            String status = job.optString("status", response.optString("status", "unknown"))
                    .toLowerCase(Locale.ROOT);
            if (!("completed".equals(status) || "error".equals(status)
                    || "queued".equals(status) || "processing".equals(status))) {
                status = "unknown";
            }
            Object errorValue = job.opt("error");
            if (errorValue == null || errorValue == JSONObject.NULL) errorValue = response.opt("error");
            String error = errorValue == null || errorValue == JSONObject.NULL ? "" : String.valueOf(errorValue);
            error = redactDiagnosticError(error);
            diagnostic.put("promptId", promptId);
            diagnostic.put("workflow", job.optString("workflow", response.optString("workflow", "")));
            diagnostic.put("provider", job.optString("provider", response.optString("provider", "")));
            diagnostic.put("status", status);
            diagnostic.put("error", error.length() > 1024 ? error.substring(0, 1024) : error);
            diagnostic.put("httpStatus", httpStatus);
            diagnostic.put("elapsedMs", elapsedMs);
        } catch (Exception parseError) {
            try {
                diagnostic.put("status", "unparseable");
                diagnostic.put("httpStatus", httpStatus);
                diagnostic.put("elapsedMs", elapsedMs);
                diagnostic.put("parseError", parseError.getClass().getSimpleName());
            } catch (Exception ignored) {}
        }
        String state = "error".equals(diagnostic.optString("status")) || httpStatus >= 400
                ? "WARN" : "INFO";
        DiagnosticsLogger.event(context, state, "imagegen_proxy", "image_job_status", diagnostic);
    }

    private static String redactDiagnosticError(String value) {
        if (value == null) return "";
        String safe = value.replaceAll("(?i)(Bearer\\s+)[A-Za-z0-9._~+/-]+=*", "$1[REDACTED]");
        safe = safe.replaceAll("(?i)(\\b(?:NAI_TOKEN|TOKEN|API[_-]?KEY|APIKEY|ACCESS[_-]?TOKEN|REFRESH[_-]?TOKEN|AUTHORIZATION|PASSWORD|SECRET)\\b\\s*[=:]\\s*)[^\\s,;]+", "$1[REDACTED]");
        return safe.replace('\\r', ' ').replace('\\n', ' ');
    }

    private static String validatePath(String raw) {
        if (raw == null || raw.length() > 4096 || !raw.startsWith("/api/")
                || raw.indexOf('\\') >= 0 || raw.indexOf('\0') >= 0) return null;
        Uri uri = Uri.parse("http://127.0.0.1" + raw);
        for (String segment : uri.getPathSegments()) {
            if (".".equals(segment) || "..".equals(segment)) return null;
        }
        return uri.getEncodedPath() + (uri.getEncodedQuery() == null ? "" : "?" + uri.getEncodedQuery());
    }

    private static boolean allowedMethod(String method) {
        return method.equals("GET") || method.equals("POST") || method.equals("PUT")
                || method.equals("PATCH") || method.equals("DELETE") || method.equals("HEAD")
                || method.equals("OPTIONS");
    }

    private static byte[] readLimited(InputStream in, int max) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[32768];
        int total = 0;
        while (true) {
            int count = in.read(buffer);
            if (count < 0) break;
            total += count;
            if (total > max) throw new IllegalStateException("ImageGen 응답이 64MB 제한을 초과했습니다.");
            out.write(buffer, 0, count);
        }
        return out.toByteArray();
    }

    private static JSONObject responseHeaders(Map<String, List<String>> source) throws Exception {
        JSONObject result = new JSONObject();
        for (Map.Entry<String, List<String>> entry : source.entrySet()) {
            String name = entry.getKey();
            if (name == null) continue;
            String lower = name.toLowerCase(Locale.ROOT);
            if (!(lower.equals("content-type") || lower.equals("cache-control")
                    || lower.equals("etag") || lower.equals("retry-after")
                    || lower.equals("content-disposition") || lower.equals("x-request-id"))) continue;
            List<String> values = entry.getValue();
            if (values != null && !values.isEmpty()) result.put(name, values.get(0));
        }
        return result;
    }

    private static String safeStatus(String value) {
        if (value == null) return "";
        return value.replace('\r', ' ').replace('\n', ' ');
    }

    private static String errorResponse(int status, String message) {
        try {
            JSONObject body = new JSONObject();
            body.put("error", message == null ? "ImageGen proxy error" : message);
            JSONObject headers = new JSONObject();
            headers.put("Content-Type", "application/json; charset=utf-8");
            JSONObject response = new JSONObject();
            response.put("status", status);
            response.put("statusText", "Proxy Error");
            response.put("headers", headers);
            response.put("bodyBase64", Base64.encodeToString(
                    body.toString().getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
            return response.toString();
        } catch (Exception ignored) {
            return "{\"status\":502,\"statusText\":\"Proxy Error\",\"headers\":{},\"bodyBase64\":\"\"}";
        }
    }

    private static void deliver(WebView web, String requestId, String payload) {
        String js = "window.__waywardImageGenProxyResolve&&window.__waywardImageGenProxyResolve("
                + JSONObject.quote(requestId) + "," + JSONObject.quote(payload) + ");";
        web.post(() -> {
            try { web.evaluateJavascript(js, null); } catch (Exception ignored) {}
        });
    }
}
