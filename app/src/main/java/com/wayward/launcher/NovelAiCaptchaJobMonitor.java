package com.wayward.launcher;

import android.content.Context;
import android.net.Uri;
import android.webkit.WebView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Watches an accepted asynchronous NovelAI job when the game client does not
 * poll /api/image/status. It only reads job status; it never retries generation.
 */
final class NovelAiCaptchaJobMonitor {
    private static final int MAX_POLLS = 120;
    private static volatile int statusPort = 8189;
    private static final long POLL_INTERVAL_MS = 2500L;
    private static final int CONNECT_TIMEOUT_MS = 4000;
    private static final int READ_TIMEOUT_MS = 5000;
    private static final int MAX_STATUS_BYTES = 1024 * 1024;
    private static final ConcurrentHashMap<String, Boolean> ACTIVE =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Boolean> CLIENT_POLLED =
            new ConcurrentHashMap<>();

    private NovelAiCaptchaJobMonitor() {}

    static void setStatusPortForTesting(int port) {
        if (port < 1 || port > 65535) throw new IllegalArgumentException("invalid port");
        statusPort = port;
    }

    static void noteClientPoll(String promptId, boolean remotePcMode) {
        if (!isSafePromptId(promptId)) return;
        String key = monitorKey(promptId, remotePcMode);
        if (ACTIVE.containsKey(key)) CLIENT_POLLED.put(key, Boolean.TRUE);
    }

    static void startFromGenerateResponse(Context context, WebView sourceWeb,
                                           byte[] responseBody, boolean remotePcMode) {
        if (responseBody == null || responseBody.length == 0) return;
        try {
            JSONObject response = new JSONObject(new String(responseBody, StandardCharsets.UTF_8));
            JSONObject job = response.optJSONObject("job");
            if (job == null) job = response;
            String workflow = response.optString("workflow", job.optString("workflow", ""))
                    .toLowerCase(java.util.Locale.ROOT);
            if (!"nai".equals(workflow)) return;
            String state = job.optString("status", response.optString("status", ""))
                    .toLowerCase(java.util.Locale.ROOT);
            String promptId = response.optString("promptId", "");
            if (promptId.isEmpty()) promptId = job.optString("promptId", "");
            boolean validId = remotePcMode ? isUuidPromptId(promptId) : isSafePromptId(promptId);
            if (("queued".equals(state) || "processing".equals(state))
                    && validId) start(context, sourceWeb, promptId, remotePcMode);
        } catch (Exception ignored) {
            DiagnosticsLogger.event(context.getApplicationContext(), "WARN", "novelai_captcha",
                    "generate_response_unparseable_for_status_watch", null);
        }
    }

    static void start(Context context, WebView sourceWeb, String promptId, boolean remotePcMode) {
        String key = monitorKey(promptId, remotePcMode);
        if (CLIENT_POLLED.remove(key) != null) {
            DiagnosticsLogger.event(context.getApplicationContext(), "INFO", "novelai_captcha",
                    "status_watch_not_needed_client_already_polling", null);
            return;
        }
        if (context == null || sourceWeb == null || !isSafePromptId(promptId)) return;
        if (ACTIVE.putIfAbsent(key, Boolean.TRUE) != null) return;
        Context app = context.getApplicationContext();
        JSONObject startDetails = new JSONObject();
        try { startDetails.put("remoteMode", remotePcMode); } catch (Exception ignored) {}
        DiagnosticsLogger.event(app, "INFO", "novelai_captcha",
                "status_watch_started", startDetails);
        Thread worker = new Thread(() -> {
            try {
                int consecutiveFailures = 0;
                for (int attempt = 0; attempt < MAX_POLLS; attempt++) {
                    if (CLIENT_POLLED.containsKey(key)) {
                        DiagnosticsLogger.event(app, "INFO", "novelai_captcha",
                                "status_watch_delegated_to_game_poll", null);
                        return;
                    }
                    try { Thread.sleep(POLL_INTERVAL_MS); }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (CLIENT_POLLED.containsKey(key)) {
                        JSONObject details = new JSONObject();
                        try { details.put("remoteMode", remotePcMode); } catch (Exception ignored) {}
                        DiagnosticsLogger.event(app, "INFO", "novelai_captcha",
                                "status_watch_delegated_to_game_poll", details);
                        return;
                    }
                    JSONObject response = fetchStatus(promptId, app, attempt, remotePcMode);
                    if (response == null) {
                        consecutiveFailures++;
                        if (consecutiveFailures >= 6) {
                            DiagnosticsLogger.event(app, "WARN", "novelai_captcha",
                                    "status_watch_transport_exhausted", null);
                            return;
                        }
                        continue;
                    }
                    consecutiveFailures = 0;
                    byte[] body = response.toString().getBytes(StandardCharsets.UTF_8);
                    String path = "/api/image/status/" + Uri.encode(promptId);
                    if (NovelAiCaptchaErrorDetector.detect(path, body) != null) {
                        DiagnosticsLogger.event(app, "WARN", "novelai_captcha",
                                "status_watch_detected_trial_recaptcha", null);
                        NovelAiCaptchaActivity.openFrom(sourceWeb);
                        return;
                    }
                    JSONObject job = response.optJSONObject("job");
                    if (job == null) job = response;
                    String state = job.optString("status",
                            response.optString("status", "")).toLowerCase(java.util.Locale.ROOT);
                    if ("completed".equals(state) || "error".equals(state)) {
                        DiagnosticsLogger.event(app, "INFO", "novelai_captcha",
                                "status_watch_terminal_without_captcha", null);
                        return;
                    }
                }
                DiagnosticsLogger.event(app, "WARN", "novelai_captcha",
                        "status_watch_timeout", null);
            } finally {
                ACTIVE.remove(key);
                CLIENT_POLLED.remove(key);
            }
        }, "wayward-nai-captcha-status-watch");
        worker.setDaemon(true);
        worker.start();
    }

    private static JSONObject fetchStatus(String promptId, Context context, int attempt,
                                           boolean remotePcMode) {
        if (remotePcMode) return fetchRemoteStatus(promptId, context, attempt);
        HttpURLConnection connection = null;
        try {
            String encoded = Uri.encode(promptId);
            connection = (HttpURLConnection) new URL(
                    "http://127.0.0.1:" + statusPort + "/api/image/status/" + encoded).openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestMethod("GET");
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            int status = connection.getResponseCode();
            JSONObject diagnostic = new JSONObject();
            try {
                diagnostic.put("method", "GET");
                diagnostic.put("path", "/api/image/status/:promptId");
                diagnostic.put("httpStatus", status);
                diagnostic.put("attempt", attempt + 1);
                diagnostic.put("remoteMode", false);
            } catch (Exception ignored) {}
            DiagnosticsLogger.event(context, status >= 400 ? "WARN" : "INFO",
                    "novelai_captcha", "status_watch_http_response", diagnostic);
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (stream == null) return null;
            byte[] body;
            try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int total = 0;
                int count;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > MAX_STATUS_BYTES) return null;
                    output.write(buffer, 0, count);
                }
                body = output.toByteArray();
            }
            if (status < 200 || status >= 300) return null;
            return new JSONObject(new String(body, StandardCharsets.UTF_8));
        } catch (Exception error) {
            JSONObject diagnostic = new JSONObject();
            try { diagnostic.put("exception", error.getClass().getSimpleName()); } catch (Exception ignored) {}
            DiagnosticsLogger.event(context, "WARN", "novelai_captcha",
                    "status_watch_transport_error", diagnostic);
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static JSONObject fetchRemoteStatus(String promptId, Context context, int attempt) {
        try {
            JSONObject envelope = RemoteGameImageApiClient.execute(context, "GET",
                    "/api/image/status/" + promptId, new byte[0]);
            int status = envelope.optInt("status", 502);
            JSONObject diagnostic = new JSONObject();
            try {
                diagnostic.put("method", "GET");
                diagnostic.put("path", "/api/image/status/:promptId");
                diagnostic.put("httpStatus", status);
                diagnostic.put("attempt", attempt + 1);
                diagnostic.put("remoteMode", true);
            } catch (Exception ignored) {}
            DiagnosticsLogger.event(context, status >= 400 ? "WARN" : "INFO",
                    "novelai_captcha", "status_watch_http_response", diagnostic);
            if (status < 200 || status >= 300) return null;
            String encoded = envelope.optString("bodyBase64", "");
            if (encoded.isEmpty() || encoded.length() > (MAX_STATUS_BYTES * 4 / 3) + 16) return null;
            byte[] body = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT);
            if (body.length > MAX_STATUS_BYTES) return null;
            return new JSONObject(new String(body, StandardCharsets.UTF_8));
        } catch (Exception error) {
            JSONObject diagnostic = new JSONObject();
            try {
                diagnostic.put("exception", error.getClass().getSimpleName());
                diagnostic.put("remoteMode", true);
            } catch (Exception ignored) {}
            DiagnosticsLogger.event(context, "WARN", "novelai_captcha",
                    "status_watch_transport_error", diagnostic);
            return null;
        }
    }

    private static String monitorKey(String promptId, boolean remotePcMode) {
        return (remotePcMode ? "remote:" : "local:") + promptId;
    }

    private static boolean isUuidPromptId(String value) {
        return value != null && value.matches(
                "(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    private static boolean isSafePromptId(String value) {
        return value != null && value.matches("[A-Za-z0-9._-]{1,128}");
    }
}
