package com.wayward.launcher;

import java.io.IOException;

public final class RemoteDownloadResumePolicyTest {
    private static final String HASH = "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF";

    public static void main(String[] args) throws Exception {
        RemoteDownloadResumePolicy.Decision initial = RemoteDownloadResumePolicy.validate(
                0, 100, HASH, 200, null, 100, HASH.toLowerCase());
        check(!initial.shouldAppend(), "initial 200 response must truncate/start");
        RemoteDownloadResumePolicy.Decision emptyFile = RemoteDownloadResumePolicy.validate(
                0, 0, "E3B0C44298FC1C149AFBF4C8996FB92427AE41E4649B934CA495991B7852B855",
                200, null, 0, "E3B0C44298FC1C149AFBF4C8996FB92427AE41E4649B934CA495991B7852B855");
        check(!emptyFile.shouldAppend(), "zero-byte file must be supported");

        RemoteDownloadResumePolicy.Decision resumed = RemoteDownloadResumePolicy.validate(
                40, 100, HASH, 206, "bytes 40-99/100", 60, HASH);
        check(resumed.shouldAppend(), "206 response at saved offset must append");
        check(resumed.getResponseStart() == 40 && resumed.getResponseEnd() == 99,
                "206 range bounds must be preserved");

        RemoteDownloadResumePolicy.Decision ignoredRange = RemoteDownloadResumePolicy.validate(
                40, 100, HASH, 200, null, 100, HASH);
        check(!ignoredRange.shouldAppend(), "200 response to Range must restart from byte zero");

        expectFailure(() -> RemoteDownloadResumePolicy.validate(
                40, 100, HASH, 206, "bytes 39-99/100", 61, HASH), "wrong range start");
        expectFailure(() -> RemoteDownloadResumePolicy.validate(
                40, 100, HASH, 206, "bytes 40-99/101", 60, HASH), "wrong total size");
        expectFailure(() -> RemoteDownloadResumePolicy.validate(
                40, 100, HASH, 206, "bytes 40-99/100", 59, HASH), "wrong range length");
        expectFailure(() -> RemoteDownloadResumePolicy.validate(
                40, 100, HASH, 206, "bytes 40-99/100", 60,
                "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF"), "changed file hash");
        expectFailure(() -> RemoteDownloadResumePolicy.validate(
                100, 100, HASH, 206, "bytes 100-100/100", 1, HASH), "range beyond EOF");
        expectFailure(() -> RemoteDownloadResumePolicy.validate(
                0, 100, HASH, 416, null, 0, HASH), "unexpected status");
        System.out.println("Remote download resume policy tests passed.");
    }

    private static void expectFailure(CheckedAction action, String label) throws Exception {
        try {
            action.run();
            throw new AssertionError("Expected failure: " + label);
        } catch (IOException expected) {
            // Expected validation rejection.
        }
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }

    @FunctionalInterface
    private interface CheckedAction { void run() throws Exception; }
}
