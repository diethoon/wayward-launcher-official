package com.wayward.launcher;

/** Dependency-free regression test for the automatic diagnostics severity policy. */
public final class DiagnosticsUploadPolicyTest {
    private static void expect(boolean actual, boolean expected, String label) {
        if (actual != expected) {
            throw new AssertionError(label + ": expected " + expected + " but was " + actual);
        }
    }

    public static void main(String[] args) {
        expect(DiagnosticsUploadPolicy.isUploadableLevel("WARN"), true, "WARN");
        expect(DiagnosticsUploadPolicy.isUploadableLevel("ERROR"), true, "ERROR");
        expect(DiagnosticsUploadPolicy.isUploadableLevel("warn"), true, "lowercase WARN");
        expect(DiagnosticsUploadPolicy.isUploadableLevel("error"), true, "lowercase ERROR");
        expect(DiagnosticsUploadPolicy.isUploadableLevel("INFO"), false, "INFO");
        expect(DiagnosticsUploadPolicy.isUploadableLevel("DEBUG"), false, "DEBUG");
        expect(DiagnosticsUploadPolicy.isUploadableLevel("TRACE"), false, "TRACE");
        expect(DiagnosticsUploadPolicy.isUploadableLevel(""), false, "empty");
        expect(DiagnosticsUploadPolicy.isUploadableLevel(null), false, "null");
        System.out.println("Diagnostics upload severity policy tests passed.");
    }
}
