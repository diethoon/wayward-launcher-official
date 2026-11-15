package com.wayward.launcher;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Bounded, app-private disk cache for PC game image resources. */
final class RemoteResourceCache {
    static final long MAX_ENTRY_BYTES = 24L * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 256L * 1024 * 1024;
    private static final long FRESH_MILLIS = 3_000L;
    private static final String CACHE_DIR = "wayward-remote-game-v1";
    private static final ConcurrentHashMap<String, CompletableFuture<Boolean>> IN_FLIGHT = new ConcurrentHashMap<>();

    private RemoteResourceCache() {}

    static CacheEntry find(Context context, String hostId, String rootId, String relativePath) {
        try {
            File directory = directory(context);
            String key = key(hostId, rootId, relativePath);
            File body = new File(directory, key + ".body");
            File meta = new File(directory, key + ".meta");
            if (!body.isFile() || !meta.isFile()) return null;
            Properties properties = new Properties();
            try (InputStream input = new FileInputStream(meta)) {
                properties.load(input);
            }
            String contentType = properties.getProperty("contentType", "application/octet-stream");
            String etag = properties.getProperty("etag", "");
            String lastModified = properties.getProperty("lastModified", "");
            long storedAt = Long.parseLong(properties.getProperty("storedAt", "0"));
            if (body.length() <= 0 || body.length() > MAX_ENTRY_BYTES || storedAt <= 0) {
                delete(body);
                delete(meta);
                return null;
            }
            body.setLastModified(storedAt);
            return new CacheEntry(body, contentType, etag, lastModified, storedAt);
        } catch (Exception ignored) {
            return null;
        }
    }

    static boolean isFresh(CacheEntry entry) {
        return entry != null && System.currentTimeMillis() - entry.storedAtMillis <= FRESH_MILLIS;
    }

    static String cacheKey(String hostId, String rootId, String relativePath) throws Exception {
        return key(hostId, rootId, relativePath);
    }

    static FetchFlight acquireFlight(String key) {
        CompletableFuture<Boolean> created = new CompletableFuture<>();
        CompletableFuture<Boolean> active = IN_FLIGHT.putIfAbsent(key, created);
        return active == null ? new FetchFlight(key, created, true) : new FetchFlight(key, active, false);
    }

    static CacheWriter beginWrite(Context context, String hostId, String rootId, String relativePath,
            String contentType, String etag, String lastModified) {
        try {
            File directory = directory(context);
            String key = key(hostId, rootId, relativePath);
            return new CacheWriter(directory, key, contentType, etag, lastModified);
        } catch (Exception ignored) {
            return null;
        }
    }

    static final class FetchFlight {
        private final String key;
        private final CompletableFuture<Boolean> completion;
        private final boolean owner;
        private boolean completed;

        FetchFlight(String key, CompletableFuture<Boolean> completion, boolean owner) {
            this.key = key;
            this.completion = completion;
            this.owner = owner;
        }

        boolean isOwner() { return owner; }

        boolean await(long timeoutMillis) throws InterruptedException {
            if (owner) return false;
            try { return Boolean.TRUE.equals(completion.get(timeoutMillis, TimeUnit.MILLISECONDS)); }
            catch (TimeoutException ignored) { return false; }
            catch (java.util.concurrent.ExecutionException ignored) { return false; }
        }

        synchronized void complete(boolean cached) {
            if (!owner || completed) return;
            completed = true;
            completion.complete(cached);
            IN_FLIGHT.remove(key, completion);
        }
    }

    static final class CacheEntry {
        final File body;
        final String contentType;
        final String etag;
        final String lastModified;
        final long storedAtMillis;

        CacheEntry(File body, String contentType, String etag, String lastModified, long storedAtMillis) {
            this.body = body;
            this.contentType = contentType;
            this.etag = etag;
            this.lastModified = lastModified;
            this.storedAtMillis = storedAtMillis;
        }

        long length() { return body.length(); }
        InputStream open() throws IOException { return new FileInputStream(body); }
    }

    static final class CacheWriter {
        private final File directory;
        private final String key;
        private final String contentType;
        private final String etag;
        private final String lastModified;
        private final File tempBody;
        private final FileOutputStream output;
        private long bytes;
        private boolean disabled;
        private boolean finished;

        CacheWriter(File directory, String key, String contentType, String etag, String lastModified)
                throws IOException {
            this.directory = directory;
            this.key = key;
            this.contentType = contentType;
            this.etag = etag == null ? "" : etag;
            this.lastModified = lastModified == null ? "" : lastModified;
            this.tempBody = new File(directory, key + "." + android.os.Process.myPid() +
                    "." + Thread.currentThread().getId() + ".part");
            this.output = new FileOutputStream(tempBody, false);
        }

        synchronized void write(byte[] buffer, int offset, int length) {
            if (disabled || finished || length <= 0) return;
            bytes += length;
            if (bytes > MAX_ENTRY_BYTES) {
                disable();
                return;
            }
            try {
                output.write(buffer, offset, length);
            } catch (IOException ignored) {
                disable();
            }
        }

        synchronized void commit() {
            if (finished) return;
            finished = true;
            if (disabled || bytes <= 0) {
                closeOutput();
                delete(tempBody);
                return;
            }
            try {
                output.flush();
                output.getFD().sync();
                output.close();
                File targetBody = new File(directory, key + ".body");
                File targetMeta = new File(directory, key + ".meta");
                File tempMeta = new File(directory, key + ".meta.part");
                Properties metadata = new Properties();
                metadata.setProperty("contentType", contentType == null ? "application/octet-stream" : contentType);
                metadata.setProperty("etag", etag);
                metadata.setProperty("lastModified", lastModified);
                metadata.setProperty("storedAt", Long.toString(System.currentTimeMillis()));
                try (FileOutputStream stream = new FileOutputStream(tempMeta, false)) {
                    metadata.store(stream, "Wayward Remote Play image cache");
                    stream.getFD().sync();
                }
                delete(targetBody);
                delete(targetMeta);
                if (!tempBody.renameTo(targetBody)) throw new IOException("Cache body rename failed.");
                if (!tempMeta.renameTo(targetMeta)) {
                    delete(targetBody);
                    throw new IOException("Cache metadata rename failed.");
                }
                targetBody.setLastModified(System.currentTimeMillis());
                purge(directory, key);
            } catch (Exception ignored) {
                delete(tempBody);
                delete(new File(directory, key + ".meta.part"));
            }
        }

        synchronized void abort() {
            if (finished) return;
            finished = true;
            closeOutput();
            delete(tempBody);
        }

        private void disable() {
            disabled = true;
            closeOutput();
            delete(tempBody);
        }

        private void closeOutput() {
            try { output.close(); } catch (IOException ignored) { }
        }
    }

    private static File directory(Context context) throws IOException {
        File directory = new File(context.getApplicationContext().getCacheDir(), CACHE_DIR);
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("Remote image cache directory is unavailable.");
        }
        return directory;
    }

    private static String key(String hostId, String rootId, String path) throws Exception {
        String value = String.valueOf(hostId) + "\n" + String.valueOf(rootId) + "\n" + String.valueOf(path);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        try {
            StringBuilder output = new StringBuilder(digest.length * 2);
            for (byte item : digest) output.append(String.format(java.util.Locale.ROOT, "%02x", item & 0xff));
            return output.toString();
        } finally {
            Arrays.fill(digest, (byte) 0);
        }
    }

    private static void purge(File directory, String keepKey) {
        File[] bodies = directory.listFiles((dir, name) -> name.endsWith(".body"));
        if (bodies == null) return;
        long total = 0;
        for (File body : bodies) total += body.length();
        if (total <= MAX_TOTAL_BYTES) return;
        Arrays.sort(bodies, Comparator.comparingLong(File::lastModified));
        for (File body : bodies) {
            if (total <= MAX_TOTAL_BYTES) break;
            if (body.getName().equals(keepKey + ".body")) continue;
            long size = body.length();
            if (body.delete()) {
                total -= size;
                String meta = body.getName().substring(0, body.getName().length() - ".body".length()) + ".meta";
                delete(new File(directory, meta));
            }
        }
    }

    private static void delete(File file) {
        if (file != null && file.exists()) {
            try { file.delete(); } catch (SecurityException ignored) { }
        }
    }
}
