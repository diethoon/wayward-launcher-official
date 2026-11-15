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
        RemoteResourceCache.FetchFlight flight = null;
        try {
            RemoteHostConnection host = RemoteHostStore.loadConnection(context);
            if (host == null) return error(503, "Service Unavailable", "PC Host가 등록되지 않았습니다.");
            String rootId = RemoteHostStore.getSelectedRootId(context);
            if (rootId == null || rootId.isEmpty()) {
                return error(503, "Service Unavailable", "PC 게임 폴더를 먼저 선택하세요.");
            }

            String relativePath = normalizeImagePath(requestPath);
            boolean rangeRequest = header(requestHeaders, "Range") != null;
            boolean noStore = containsDirective(header(requestHeaders, "Cache-Control"), "no-store");
            boolean forceRefresh = containsDirective(header(requestHeaders, "Cache-Control"), "no-cache");
            RemoteResourceCache.CacheEntry cached = rangeRequest || noStore
                    ? null : RemoteResourceCache.find(context, host.hostId, rootId, relativePath);

            if (cached != null && RemoteResourceCache.isFresh(cached) && !forceRefresh) {
                return cachedResponse(cached, false);
            }

            String cacheKey = null;
            if (!rangeRequest && !noStore) {
                cacheKey = RemoteResourceCache.cacheKey(host.hostId, rootId, relativePath);
                flight = RemoteResourceCache.acquireFlight(cacheKey);
                if (!flight.isOwner()) {
                    // Another request for the same image is currently downloading it.
                    // Wait briefly for the first stream to complete, then reuse its disk file.
                    try { flight.await(5000L); }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    RemoteResourceCache.CacheEntry coalesced =
                            RemoteResourceCache.find(context, host.hostId, rootId, relativePath);
                    if (coalesced != null) return cachedResponse(coalesced, false);
                    flight = null;
                }
            }

            Map<String, String> upstreamHeaders = requestHeaders == null
                    ? new HashMap<>() : new HashMap<>(requestHeaders);
            if (cached != null && !rangeRequest) {
                // The validator must describe this exact disk body, not a different
                // WebView in-memory copy; otherwise 304 could reuse mismatched bytes.
                removeHeaderIgnoreCase(upstreamHeaders, "If-None-Match");
                removeHeaderIgnoreCase(upstreamHeaders, "If-Modified-Since");
                if (cached.etag != null && !cached.etag.isEmpty()) {
                    upstreamHeaders.put("If-None-Match", cached.etag);
                } else if (cached.lastModified != null && !cached.lastModified.isEmpty()) {
                    upstreamHeaders.put("If-Modified-Since", cached.lastModified);
                }
            }

            connection = RemoteHostClient.openGameResource(context, host, rootId, relativePath, upstreamHeaders);
            int status = connection.getResponseCode();

            if (status == 304 && cached != null) {
                connection.disconnect();
                connection = null;
                if (flight != null) flight.complete(true);
                return cachedResponse(cached, false);
            }

            // A 304 without a local copy cannot be served. Retry once without validators.
            if (status == 304) {
                connection.disconnect();
                Map<String, String> unconditionalHeaders = requestHeaders == null
                        ? new HashMap<>() : new HashMap<>(requestHeaders);
                removeHeaderIgnoreCase(unconditionalHeaders, "If-None-Match");
                removeHeaderIgnoreCase(unconditionalHeaders, "If-Modified-Since");
                connection = RemoteHostClient.openGameResource(context, host, rootId, relativePath,
                        unconditionalHeaders);
                status = connection.getResponseCode();
            }

            if (status >= 500 && cached != null) {
                connection.disconnect();
                connection = null;
                if (flight != null) flight.complete(true);
                return cachedResponse(cached, true);
            }

            String rawContentType = connection.getContentType();
            String mime = normalizeMime(rawContentType);
            String encoding = parseCharset(rawContentType);
            Map<String, String> responseHeaders = filteredResponseHeaders(connection);
            InputStream body;

            if (status == 304 || status == 204) {
                body = new ByteArrayInputStream(new byte[0]);
                if (flight != null) flight.complete(false);
            } else if (status >= 200 && status < 400) {
                InputStream input = connection.getInputStream();
                HttpsURLConnection owned = connection;
                RemoteResourceCache.CacheWriter writer = null;
                long contentLength = connection.getContentLengthLong();
                if (status == 200 && !rangeRequest && !noStore &&
                        (contentLength <= 0 || contentLength <= RemoteResourceCache.MAX_ENTRY_BYTES)) {
                    writer = RemoteResourceCache.beginWrite(context, host.hostId, rootId, relativePath,
                            mime, connection.getHeaderField("ETag"), connection.getHeaderField("Last-Modified"));
                }
                if (flight != null && writer == null) flight.complete(false);
                body = new CacheTeeInputStream(input, owned, writer, flight);
                connection = null; // the stream owns the connection
                flight = null; // the stream completes the in-flight gate
                responseHeaders.put("Cache-Control", rangeRequest || noStore ? "no-store" : "private, max-age=3");
            } else {
                if (flight != null) flight.complete(false);
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
            return new WebResourceResponse(mime, encoding, status, reasonPhrase(status), responseHeaders, body);
        } catch (Exception error) {
            if (flight != null) flight.complete(false);
            if (diagnostics != null && diagnostics.getAndIncrement() < 16) {
                android.util.Log.w("WaywardRemotePlay",
                        "Remote image request failed: " + error.getClass().getSimpleName());
            }
            return error(502, "Bad Gateway", "PC 이미지 리소스를 가져오지 못했습니다.");
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static final class CacheTeeInputStream extends FilterInputStream {
        private final HttpsURLConnection connection;
        private final RemoteResourceCache.CacheWriter writer;
        private final RemoteResourceCache.FetchFlight flight;
        private boolean endOfStream;
        private boolean closed;

        CacheTeeInputStream(InputStream input, HttpsURLConnection connection,
                RemoteResourceCache.CacheWriter writer, RemoteResourceCache.FetchFlight flight) {
            super(input);
            this.connection = connection;
            this.writer = writer;
            this.flight = flight;
        }

        @Override public int read() throws IOException {
            try {
                int value = super.read();
                if (value < 0) finish();
                else if (writer != null) {
                    byte[] one = {(byte) value};
                    writer.write(one, 0, 1);
                }
                return value;
            } catch (IOException error) {
                abort();
                throw error;
            }
        }

        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            try {
                int count = super.read(buffer, offset, length);
                if (count < 0) finish();
                else if (count > 0 && writer != null) writer.write(buffer, offset, count);
                return count;
            } catch (IOException error) {
                abort();
                throw error;
            }
        }

        private void finish() {
            if (endOfStream || closed) return;
            endOfStream = true;
            if (writer != null) writer.commit();
            if (flight != null) flight.complete(writer != null);
        }

        private void abort() {
            if (writer != null) writer.abort();
            if (flight != null) flight.complete(false);
        }

        @Override public void close() throws IOException {
            if (closed) return;
            closed = true;
            if (!endOfStream) abort();
            try { super.close(); }
            finally { connection.disconnect(); }
        }
    }

    private static WebResourceResponse cachedResponse(RemoteResourceCache.CacheEntry entry, boolean stale) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Cache-Control", "private, max-age=3");
        headers.put("Content-Length", Long.toString(entry.length()));
        headers.put("X-Content-Type-Options", "nosniff");
        if (entry.etag != null && !entry.etag.isEmpty()) headers.put("ETag", entry.etag);
        if (entry.lastModified != null && !entry.lastModified.isEmpty()) headers.put("Last-Modified", entry.lastModified);
        if (stale) headers.put("Warning", "110 - Response is stale");
        try {
            return new WebResourceResponse(entry.contentType, null, 200, "OK", headers, entry.open());
        } catch (IOException error) {
            return error(502, "Bad Gateway", "PC 이미지 캐시를 읽지 못했습니다.");
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
        return "/images/" + relative;
    }

    private static void removeHeaderIgnoreCase(Map<String, String> headers, String name) {
        if (headers == null) return;
        String remove = null;
        for (String key : headers.keySet()) {
            if (key != null && key.equalsIgnoreCase(name)) { remove = key; break; }
        }
        if (remove != null) headers.remove(remove);
    }

    private static String header(Map<String, String> headers, String name) {
        if (headers == null) return null;
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) return entry.getValue();
        }
        return null;
    }

    private static boolean containsDirective(String header, String directive) {
        return header != null && header.toLowerCase(Locale.ROOT).contains(directive);
    }

    private static String normalizeMime(String rawContentType) {
        if (rawContentType == null) return "application/octet-stream";
        String[] parts = rawContentType.split(";", 2);
        String mime = parts[0].trim().toLowerCase(Locale.ROOT);
        return mime.isEmpty() ? "application/octet-stream" : mime;
    }

    private static String parseCharset(String rawContentType) {
        if (rawContentType == null) return null;
        String[] parts = rawContentType.split(";", 2);
        if (parts.length < 2) return null;
        for (String parameter : parts[1].split(";")) {
            String value = parameter.trim();
            if (value.toLowerCase(Locale.ROOT).startsWith("charset=")) {
                return value.substring("charset=".length()).replace("\"", "").trim();
            }
        }
        return null;
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
