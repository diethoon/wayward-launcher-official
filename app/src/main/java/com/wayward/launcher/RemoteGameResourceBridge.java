package com.wayward.launcher;

import android.content.Context;
import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.HttpsURLConnection;

/** Fetches PC-hosted image-pack assets for the existing per-game localhost WebView origin. */
final class RemoteGameResourceBridge {
    private RemoteGameResourceBridge() {}

    static WebResourceResponse serve(Context context, String requestPath,
            Map<String, String> requestHeaders, AtomicInteger diagnostics) {
        HttpsURLConnection connection = null;
        try {
            RemoteHostConnection host = RemoteHostStore.loadConnection(context);
            if (host == null) return error(503, "Service Unavailable", "PC Host가 등록되지 않았습니다.");
            String rootId = RemoteHostStore.getSelectedRootId(context);
            if (rootId == null || rootId.isEmpty()) {
                return error(503, "Service Unavailable", "PC 게임 폴더를 먼저 선택하세요.");
            }

            String relativePath = normalizeImagePath(requestPath);
            connection = RemoteHostClient.openGameResource(context, host, rootId, relativePath, requestHeaders);
            int status = connection.getResponseCode();
            String reason = reasonPhrase(status);
            String rawContentType = connection.getContentType();
            String mime = "application/octet-stream";
            String encoding = null;
            if (rawContentType != null) {
                String[] parts = rawContentType.split(";", 2);
                if (!parts[0].trim().isEmpty()) mime = parts[0].trim().toLowerCase(Locale.ROOT);
                if (parts.length > 1) {
                    for (String parameter : parts[1].split(";")) {
                        String value = parameter.trim();
                        if (value.toLowerCase(Locale.ROOT).startsWith("charset=")) {
                            encoding = value.substring("charset=".length()).replace("\"", "").trim();
                        }
                    }
                }
            }

            Map<String, String> headers = filteredResponseHeaders(connection);
            InputStream body;
            if (status == 304 || status == 204) {
                body = new ByteArrayInputStream(new byte[0]);
            } else if (status >= 200 && status < 400) {
                InputStream input = connection.getInputStream();
                HttpsURLConnection owned = connection;
                body = new FilterInputStream(input) {
                    @Override public void close() throws IOException {
                        try { super.close(); }
                        finally { owned.disconnect(); }
                    }
                };
                connection = null; // the stream now owns the connection
            } else {
                // Do not forward Host-side exception text or Windows paths into game content.
                InputStream source = connection.getErrorStream();
                if (source != null) {
                    try { source.close(); } catch (IOException ignored) { }
                }
                body = new ByteArrayInputStream(
                        "Remote image unavailable".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }

            if (status >= 400 && diagnostics != null && diagnostics.getAndIncrement() < 16) {
                android.util.Log.w("WaywardRemotePlay", "Remote image response status=" + status);
            }
            return new WebResourceResponse(mime, encoding, status, reason, headers, body);
        } catch (Exception error) {
            if (diagnostics != null && diagnostics.getAndIncrement() < 16) {
                android.util.Log.w("WaywardRemotePlay",
                        "Remote image request failed: " + error.getClass().getSimpleName());
            }
            return error(502, "Bad Gateway", "PC 이미지 리소스를 가져오지 못했습니다.");
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String normalizeImagePath(String rawPath) throws IOException {
        if (rawPath == null || !rawPath.startsWith("/images/") ||
                rawPath.contains("\\") || rawPath.startsWith("//")) {
            throw new IOException("Invalid remote image path.");
        }
        String relative = rawPath.substring("/images/".length());
        String decoded = android.net.Uri.decode(relative);
        if (decoded.isEmpty() || decoded.startsWith("/") || decoded.contains("\\") ||
                decoded.split("/", -1).length == 0) {
            throw new IOException("Invalid remote image path.");
        }
        for (String segment : decoded.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment) ||
                    segment.indexOf(':') >= 0 || segment.chars().anyMatch(Character::isISOControl)) {
                throw new IOException("Invalid remote image path segment.");
            }
        }
        // Keep the original URI-encoded path so Unicode and reserved filename characters
        // are not double-encoded when appended to /v1/game.
        return "/images/" + relative;
    }

    private static Map<String, String> filteredResponseHeaders(HttpsURLConnection connection) {
        Map<String, String> result = new HashMap<>();
        String[] allowed = {
                "Accept-Ranges", "Content-Range", "ETag", "Last-Modified",
                "Cache-Control", "Expires", "Pragma", "Vary", "X-Content-Type-Options"
        };
        for (String name : allowed) {
            String value = connection.getHeaderField(name);
            if (value != null && value.length() <= 4096) result.put(name, value);
        }
        return result;
    }

    private static String reasonPhrase(int status) {
        switch (status) {
            case 200: return "OK";
            case 206: return "Partial Content";
            case 304: return "Not Modified";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 409: return "Conflict";
            case 416: return "Range Not Satisfiable";
            case 429: return "Too Many Requests";
            case 500: return "Internal Server Error";
            case 502: return "Bad Gateway";
            case 503: return "Service Unavailable";
            case 504: return "Gateway Timeout";
            default: return status >= 200 && status < 300 ? "OK" : "Remote Host Error";
        }
    }

    private static WebResourceResponse error(int status, String reason, String message) {
        byte[] bytes = message.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Map<String, String> headers = new HashMap<>();
        headers.put("Cache-Control", "no-store");
        headers.put("X-Content-Type-Options", "nosniff");
        return new WebResourceResponse("text/plain", "UTF-8", status, reason, headers,
                new ByteArrayInputStream(bytes));
    }
}
