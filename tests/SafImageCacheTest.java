package com.wayward.launcher;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class SafImageCacheTest {
    private static final byte[] IMAGE = new byte[] {
            0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x00, (byte) 0xff
    };

    public static void main(String[] args) throws Exception {
        testColdWriteThenWarmHit();
        testMetadataBypass();
        testInterruptedStreamDoesNotPublishPartialCache();
        testConcurrentMissDoesNotWaitForCache();
        testSkipStillCopiesSkippedBytes();
        testModifiedTimeInvalidatesCache();
        testStalePartCleanup();
        System.out.println("SafImageCache unit tests passed.");
    }

    private static void testColdWriteThenWarmHit() throws Exception {
        File root = Files.createTempDirectory("saf-cache-cold").toFile();
        Context context = new Context(new File(root, "cache"));
        FakeResolver resolver = new FakeResolver(IMAGE);
        SafImageCache cache = new SafImageCache(context);
        SafImageCache.OpenResult first = cache.open(resolver, uri("cold"), "doc-cold", IMAGE.length, 1000L);
        equal("loading", first.cacheState, "first request state");
        bytes(IMAGE, readAll(first.stream), "first stream bytes");
        first.stream.close();
        awaitFileCount(new File(context.getCacheDir(), "wayward-saf-images"), ".img", 1, "cold cache fill");
        equal(1, resolver.opens.get(), "first request provider open count");

        SafImageCache.OpenResult second = cache.open(resolver, uri("cold"), "doc-cold", IMAGE.length, 1000L);
        equal("hit", second.cacheState, "second request cache hit");
        bytes(IMAGE, readAll(second.stream), "cached stream bytes");
        second.stream.close();
        equal(1, resolver.opens.get(), "cache hit must not reopen SAF provider");
        require(countFiles(new File(context.getCacheDir(), "wayward-saf-images"), ".img") == 1,
                "one complete cache file must exist");

        boolean completionRecorded = false;
        synchronized (ImagePipelineDiagnostics.EVENTS) {
            for (ImagePipelineDiagnostics.RecordedEvent event : ImagePipelineDiagnostics.EVENTS) {
                if (!"saf_image_cache_completed".equals(event.event)) continue;
                completionRecorded = "cached".equals(event.details.opt("cacheState"))
                        && event.details.opt("sourceReadMs") != null
                        && event.details.opt("cacheWriteMs") != null
                        && event.details.opt("cacheFinalizeMs") != null;
            }
        }
        require(completionRecorded, "cache completion must record separate read/write/finalize timings");
        deleteTree(root);
    }

    private static void testMetadataBypass() throws Exception {
        File root = Files.createTempDirectory("saf-cache-bypass").toFile();
        Context context = new Context(new File(root, "cache"));
        FakeResolver resolver = new FakeResolver(IMAGE);
        SafImageCache cache = new SafImageCache(context);
        SafImageCache.OpenResult result = cache.open(resolver, uri("bypass"), "doc-bypass", IMAGE.length, -1L);
        equal("bypass", result.cacheState, "missing metadata must bypass cache");
        bytes(IMAGE, readAll(result.stream), "bypass stream bytes");
        result.stream.close();
        equal(1, resolver.opens.get(), "bypass direct open count");
        require(countFiles(new File(context.getCacheDir(), "wayward-saf-images"), ".img") == 0,
                "metadata bypass must not publish cache files");
        deleteTree(root);
    }

    private static void testInterruptedStreamDoesNotPublishPartialCache() throws Exception {
        File root = Files.createTempDirectory("saf-cache-abort").toFile();
        Context context = new Context(new File(root, "cache"));
        FakeResolver resolver = new FakeResolver(IMAGE);
        SafImageCache cache = new SafImageCache(context);
        SafImageCache.OpenResult partial = cache.open(resolver, uri("abort"), "doc-abort", IMAGE.length, 1000L);
        equal((int) IMAGE[0], partial.stream.read(), "first byte of partial stream");
        partial.stream.close();
        awaitNoFiles(new File(context.getCacheDir(), "wayward-saf-images"), ".img",
                "an interrupted stream must not publish an image cache file");
        awaitNoFiles(new File(context.getCacheDir(), "wayward-saf-images"), ".part",
                "an interrupted stream must delete its partial cache file");

        SafImageCache.OpenResult retry = cache.open(resolver, uri("abort"), "doc-abort", IMAGE.length, 1000L);
        bytes(IMAGE, readAll(retry.stream), "retry stream bytes");
        retry.stream.close();
        awaitFileCount(new File(context.getCacheDir(), "wayward-saf-images"), ".img", 1, "retry cache fill");
        equal(2, resolver.opens.get(), "retry should reopen the provider once");
        SafImageCache.OpenResult hit = cache.open(resolver, uri("abort"), "doc-abort", IMAGE.length, 1000L);
        equal("hit", hit.cacheState, "completed retry should be cached");
        hit.stream.close();
        deleteTree(root);
    }

    private static void testConcurrentMissDoesNotWaitForCache() throws Exception {
        File root = Files.createTempDirectory("saf-cache-concurrent").toFile();
        Context context = new Context(new File(root, "cache"));
        FakeResolver resolver = new FakeResolver(IMAGE);
        SafImageCache cache = new SafImageCache(context);
        SafImageCache.OpenResult first = cache.open(resolver, uri("concurrent"), "doc-concurrent", IMAGE.length, 1000L);

        FutureTask<SafImageCache.OpenResult> secondTask = new FutureTask<>(
                () -> cache.open(resolver, uri("concurrent"), "doc-concurrent", IMAGE.length, 1000L));
        Thread secondThread = new Thread(secondTask, "saf-cache-concurrent-test");
        secondThread.start();
        SafImageCache.OpenResult second;
        try {
            second = secondTask.get(500L, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException error) {
            throw new AssertionError("same-key image request waited for the cache writer");
        }

        equal("inflight-bypass", second.cacheState, "concurrent request must bypass in-flight cache");
        bytes(IMAGE, readAll(first.stream), "first concurrent display stream");
        bytes(IMAGE, readAll(second.stream), "second concurrent display stream");
        first.stream.close();
        second.stream.close();

        awaitFileCount(new File(context.getCacheDir(), "wayward-saf-images"), ".img", 1,
                "cache writer must complete after first display stream reaches EOF");
        SafImageCache.OpenResult hit = cache.open(resolver, uri("concurrent"), "doc-concurrent", IMAGE.length, 1000L);
        equal("hit", hit.cacheState, "third request should hit completed cache");
        hit.stream.close();
        equal(2, resolver.opens.get(), "two display requests must use only two provider streams");
        deleteTree(root);
    }

    private static void testSkipStillCopiesSkippedBytes() throws Exception {
        File root = Files.createTempDirectory("saf-cache-skip").toFile();
        Context context = new Context(new File(root, "cache"));
        FakeResolver resolver = new FakeResolver(IMAGE);
        SafImageCache cache = new SafImageCache(context);
        SafImageCache.OpenResult first = cache.open(resolver, uri("skip"), "doc-skip", IMAGE.length, 1000L);
        equal(2L, first.stream.skip(2L), "skip count");
        byte[] remaining = readAll(first.stream);
        bytes(Arrays.copyOfRange(IMAGE, 2, IMAGE.length), remaining, "post-skip stream bytes");
        first.stream.close();
        awaitFileCount(new File(context.getCacheDir(), "wayward-saf-images"), ".img", 1, "skip cache fill");

        SafImageCache.OpenResult cached = cache.open(resolver, uri("skip"), "doc-skip", IMAGE.length, 1000L);
        equal("hit", cached.cacheState, "skip path should produce a complete cache");
        bytes(IMAGE, readAll(cached.stream), "cache must retain skipped bytes too");
        cached.stream.close();
        equal(1, resolver.opens.get(), "skip cache hit provider open count");
        deleteTree(root);
    }

    private static void testModifiedTimeInvalidatesCache() throws Exception {
        File root = Files.createTempDirectory("saf-cache-mtime").toFile();
        Context context = new Context(new File(root, "cache"));
        FakeResolver resolver = new FakeResolver(IMAGE);
        SafImageCache cache = new SafImageCache(context);
        SafImageCache.OpenResult first = cache.open(resolver, uri("mtime"), "doc-mtime", IMAGE.length, 1000L);
        readAll(first.stream);
        first.stream.close();
        awaitFileCount(new File(context.getCacheDir(), "wayward-saf-images"), ".img", 1, "initial mtime cache fill");

        SafImageCache.OpenResult changed = cache.open(resolver, uri("mtime"), "doc-mtime", IMAGE.length, 2000L);
        equal("loading", changed.cacheState, "changed modified time must miss old cache");
        readAll(changed.stream);
        changed.stream.close();
        awaitFileCount(new File(context.getCacheDir(), "wayward-saf-images"), ".img", 2, "changed mtime cache fill");
        equal(2, resolver.opens.get(), "modified time change should trigger a fresh provider stream");
        deleteTree(root);
    }

    private static void testStalePartCleanup() throws Exception {
        File root = Files.createTempDirectory("saf-cache-cleanup").toFile();
        File cacheDir = new File(root, "cache/wayward-saf-images");
        require(cacheDir.mkdirs(), "create cache directory");
        File stale = new File(cacheDir, "old.part");
        require(stale.createNewFile(), "create stale part");
        stale.setLastModified(System.currentTimeMillis() - TimeUnit.HOURS.toMillis(7));
        new SafImageCache(new Context(new File(root, "cache")));
        require(!stale.exists(), "parts older than six hours should be removed");
        deleteTree(root);
    }

    private static Uri uri(String name) {
        return Uri.parse("content://test/tree/document/" + name);
    }

    private static byte[] readAll(InputStream stream) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[3];
        int count;
        while ((count = stream.read(buffer)) != -1) output.write(buffer, 0, count);
        return output.toByteArray();
    }

    private static void awaitFileCount(File directory, String suffix, int minimum, String label) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
        while (System.nanoTime() < deadline) {
            if (countFiles(directory, suffix) >= minimum) return;
            Thread.sleep(10L);
        }
        throw new AssertionError(label + " timed out");
    }

    private static void awaitNoFiles(File directory, String suffix, String label) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
        while (System.nanoTime() < deadline) {
            if (countFiles(directory, suffix) == 0) return;
            Thread.sleep(10L);
        }
        throw new AssertionError(label + " timed out");
    }

    private static int countFiles(File directory, String suffix) {
        File[] files = directory.listFiles((parent, name) -> name.endsWith(suffix));
        return files == null ? 0 : files.length;
    }

    private static void bytes(byte[] expected, byte[] actual, String label) {
        require(Arrays.equals(expected, actual), label + " (expected " + expected.length
                + " bytes, got " + actual.length + ")");
    }

    private static void equal(Object expected, Object actual, String label) {
        require(expected == null ? actual == null : expected.equals(actual),
                label + " (expected=" + expected + ", actual=" + actual + ")");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void deleteTree(File file) {
        if (!file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }

    private static final class FakeResolver extends ContentResolver {
        final AtomicInteger opens = new AtomicInteger();
        private final byte[] bytes;
        FakeResolver(byte[] source) { bytes = source.clone(); }
        @Override public InputStream openInputStream(Uri uri) {
            opens.incrementAndGet();
            return new ByteArrayInputStream(bytes);
        }
    }
}
