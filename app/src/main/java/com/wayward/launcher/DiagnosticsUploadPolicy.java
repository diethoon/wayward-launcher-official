package com.wayward.launcher;

/** Restricts automatic diagnostics uploads to explicitly urgent launcher events. */
final class DiagnosticsUploadPolicy {
    private DiagnosticsUploadPolicy() {}

    static boolean isUploadableLevel(String level) {
        return "WARN".equalsIgnoreCase(level) || "ERROR".equalsIgnoreCase(level);
    }
}
