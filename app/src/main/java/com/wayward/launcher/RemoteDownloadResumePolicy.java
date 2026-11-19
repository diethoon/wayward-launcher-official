package com.wayward.launcher;

import java.io.IOException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Validates a resumed Host download before appending bytes to an existing partial file. */
public final class RemoteDownloadResumePolicy {
    private static final Pattern CONTENT_RANGE =
            Pattern.compile("(?i)^bytes ([0-9]+)-([0-9]+)/([0-9]+)$");

    private RemoteDownloadResumePolicy() {}

    public static final class Decision {
        private final boolean append;
        private final long responseStart;
        private final long responseEnd;
        private final long totalBytes;

        private Decision(boolean append, long responseStart, long responseEnd, long totalBytes) {
            this.append = append;
            this.responseStart = responseStart;
            this.responseEnd = responseEnd;
            this.totalBytes = totalBytes;
        }

        public boolean shouldAppend() { return append; }
        public long getResponseStart() { return responseStart; }
        public long getResponseEnd() { return responseEnd; }
        public long getTotalBytes() { return totalBytes; }
    }

    public static Decision validate(long requestedOffset, long expectedTotalBytes,
            String expectedSha256, int statusCode, String contentRange,
            long responseContentLength, String responseSha256) throws IOException {
        if (expectedTotalBytes < 0 || expectedTotalBytes > 512L * 1024 * 1024) {
            throw new IOException("Remote download size is outside the supported 0-byte~512-MiB range.");
        }
        if (requestedOffset < 0 || requestedOffset > expectedTotalBytes) {
            throw new IOException("Saved download offset is outside the expected file size.");
        }
        String expectedHash = normalizeHash(expectedSha256);
        String actualHash = normalizeHash(responseSha256);
        if (!expectedHash.equals(actualHash)) {
            throw new IOException("The PC file changed after the interrupted download; discard the partial download and start again.");
        }

        if (statusCode == 200) {
            if (responseContentLength >= 0 && responseContentLength != expectedTotalBytes) {
                throw new IOException("The PC returned a different file size for the resumed download.");
            }
            return new Decision(false, 0, expectedTotalBytes - 1, expectedTotalBytes);
        }

        if (statusCode != 206) {
            throw new IOException("Unexpected HTTP status for resumed download: " + statusCode);
        }
        Matcher matcher = CONTENT_RANGE.matcher(contentRange == null ? "" : contentRange.trim());
        if (!matcher.matches()) {
            throw new IOException("The PC returned an invalid Content-Range for the resumed download.");
        }

        final long start;
        final long end;
        final long total;
        try {
            start = Long.parseLong(matcher.group(1));
            end = Long.parseLong(matcher.group(2));
            total = Long.parseLong(matcher.group(3));
        } catch (NumberFormatException error) {
            throw new IOException("The PC returned an invalid Content-Range number.", error);
        }
        if (start != requestedOffset || end < start || end >= total || total != expectedTotalBytes) {
            throw new IOException("The PC Content-Range does not continue the saved download offset.");
        }
        long expectedRangeLength = end - start + 1;
        if (responseContentLength >= 0 && responseContentLength != expectedRangeLength) {
            throw new IOException("The PC Content-Length does not match its Content-Range.");
        }
        return new Decision(requestedOffset > 0, start, end, total);
    }

    private static String normalizeHash(String value) throws IOException {
        if (value == null) throw new IOException("Remote download SHA-256 is missing.");
        String normalized = value.replace(":", "").trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[0-9A-F]{64}")) {
            throw new IOException("Remote download SHA-256 is invalid.");
        }
        return normalized;
    }
}
