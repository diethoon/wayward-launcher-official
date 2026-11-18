package com.wayward.launcher;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Dependency-free tests for original chooser request and result semantics. */
public final class WebFileChooserRequestPolicyTest {
    private static void expect(boolean actual, boolean expected, String label) {
        if (actual != expected) throw new AssertionError(label + ": expected " + expected + " but was " + actual);
    }
    private static void expectKind(WebFileChooserRequestPolicy.Kind actual,
            WebFileChooserRequestPolicy.Kind expected, String label) {
        if (actual != expected) throw new AssertionError(label + ": expected " + expected + " but was " + actual);
    }
    private static void expectTokens(List<String> actual, String... expected) {
        if (!actual.equals(Arrays.asList(expected))) {
            throw new AssertionError("accept tokens: expected " + Arrays.toString(expected) + " but was " + actual);
        }
    }
    public static void main(String[] args) {
        expectKind(WebFileChooserRequestPolicy.classify(0), WebFileChooserRequestPolicy.Kind.OPEN_SINGLE, "MODE_OPEN");
        expectKind(WebFileChooserRequestPolicy.classify(1), WebFileChooserRequestPolicy.Kind.OPEN_MULTIPLE, "MODE_OPEN_MULTIPLE");
        expectKind(WebFileChooserRequestPolicy.classify(2), WebFileChooserRequestPolicy.Kind.UNSUPPORTED, "folder mode is not routed through generic file chooser");
        expectKind(WebFileChooserRequestPolicy.classify(3), WebFileChooserRequestPolicy.Kind.SAVE, "MODE_SAVE");
        expectKind(WebFileChooserRequestPolicy.classify(-1), WebFileChooserRequestPolicy.Kind.UNSUPPORTED, "negative unknown mode");
        expectKind(WebFileChooserRequestPolicy.classify(4), WebFileChooserRequestPolicy.Kind.UNSUPPORTED, "future unknown mode");
        expect(WebFileChooserRequestPolicy.allowsMultipleFiles(WebFileChooserRequestPolicy.Kind.OPEN_MULTIPLE), true, "multiple files");
        expect(WebFileChooserRequestPolicy.allowsMultipleFiles(WebFileChooserRequestPolicy.Kind.OPEN_SINGLE), false, "single file");
        expect(WebFileChooserRequestPolicy.allowsMultipleFiles(WebFileChooserRequestPolicy.Kind.SAVE), false, "save is not multiple files");

        List<String> filters = WebFileChooserRequestPolicy.parseAcceptTypes(
                new String[] { "image/*,.PNG,.unknown", "application/pdf,.png" });
        expectTokens(filters, "image/*", ".png", ".unknown", "application/pdf");
        expect(WebFileChooserRequestPolicy.acceptsFile(filters, "image/jpeg", "photo.jpg"), true,
                "MIME wildcard is ORed with extension filters");
        expect(WebFileChooserRequestPolicy.acceptsFile(filters, "application/octet-stream", "payload.unknown"), true,
                "unmapped extension remains enforceable by returned display name");
        expect(WebFileChooserRequestPolicy.acceptsFile(filters, "text/plain", "notes.txt"), false,
                "unmatched MIME and extension are rejected");
        expect(WebFileChooserRequestPolicy.acceptsFile(Collections.emptyList(), "text/plain", "notes.txt"), true,
                "empty accept list means unrestricted selection");
        expect(WebFileChooserRequestPolicy.acceptsFile(Arrays.asList(".tar.gz"), null, "backup.tar.gz"), true,
                "compound extension is matched as a suffix");
        List<String> waywardSaveImport = WebFileChooserRequestPolicy.parseAcceptTypes(
                new String[] { "application/json,.json" });
        expectTokens(waywardSaveImport, "application/json", ".json");
        expect(WebFileChooserRequestPolicy.acceptsFile(waywardSaveImport,
                "application/json", "savegame.json"), true, "Wayward single JSON save import");
        expect(WebFileChooserRequestPolicy.acceptsFile(waywardSaveImport,
                "application/octet-stream", "savegame.json"), true, "Wayward extension remains authoritative when provider MIME is generic");
        List<String> waywardPackImport = WebFileChooserRequestPolicy.parseAcceptTypes(
                new String[] { ".json,application/json" });
        expectTokens(waywardPackImport, ".json", "application/json");
        expect(WebFileChooserRequestPolicy.allowsMultipleFiles(
                WebFileChooserRequestPolicy.classify(1)), true, "Wayward pack import retains multiple selection");
        expect(WebFileChooserRequestPolicy.allowsMultipleFiles(
                WebFileChooserRequestPolicy.classify(0)), false, "Wayward save import remains single selection");
        boolean invalidRejected = false;
        try { WebFileChooserRequestPolicy.parseAcceptTypes(new String[] { "not-a-filter" }); }
        catch (IllegalArgumentException expected) { invalidRejected = true; }
        expect(invalidRejected, true, "invalid non-empty accept tokens fail explicitly");
        System.out.println("WebFileChooserRequestPolicyTest passed");
    }
}