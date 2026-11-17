package com.wayward.launcher;

/** Dependency-free tests for WebView file/folder request-mode semantics. */
public final class WebFileChooserRequestPolicyTest {
    private static void expect(boolean actual, boolean expected, String label) {
        if (actual != expected) throw new AssertionError(label + ": expected " + expected + " but was " + actual);
    }
    private static void expectKind(WebFileChooserRequestPolicy.Kind actual,
            WebFileChooserRequestPolicy.Kind expected, String label) {
        if (actual != expected) throw new AssertionError(label + ": expected " + expected + " but was " + actual);
    }
    public static void main(String[] args) {
        expectKind(WebFileChooserRequestPolicy.classify(0), WebFileChooserRequestPolicy.Kind.OPEN_SINGLE, "MODE_OPEN");
        expectKind(WebFileChooserRequestPolicy.classify(1), WebFileChooserRequestPolicy.Kind.OPEN_MULTIPLE, "MODE_OPEN_MULTIPLE");
        expectKind(WebFileChooserRequestPolicy.classify(2), WebFileChooserRequestPolicy.Kind.OPEN_FOLDER, "API 37 MODE_OPEN_FOLDER");
        expectKind(WebFileChooserRequestPolicy.classify(3), WebFileChooserRequestPolicy.Kind.SAVE, "MODE_SAVE");
        expectKind(WebFileChooserRequestPolicy.classify(-1), WebFileChooserRequestPolicy.Kind.UNSUPPORTED, "negative unknown mode");
        expectKind(WebFileChooserRequestPolicy.classify(4), WebFileChooserRequestPolicy.Kind.UNSUPPORTED, "future unknown mode");
        expect(WebFileChooserRequestPolicy.allowsMultipleFiles(WebFileChooserRequestPolicy.Kind.OPEN_MULTIPLE), true, "multiple files");
        expect(WebFileChooserRequestPolicy.isFolder(WebFileChooserRequestPolicy.classify(1)), false, "MODE_OPEN_MULTIPLE is never a folder");
        expect(WebFileChooserRequestPolicy.isFolder(WebFileChooserRequestPolicy.classify(2)), true, "only explicit MODE_OPEN_FOLDER is a folder");
        expect(WebFileChooserRequestPolicy.allowsMultipleFiles(WebFileChooserRequestPolicy.Kind.OPEN_SINGLE), false, "single file");
        expect(WebFileChooserRequestPolicy.allowsMultipleFiles(WebFileChooserRequestPolicy.Kind.OPEN_FOLDER), false, "folder is not multiple files");
        expect(WebFileChooserRequestPolicy.allowsMultipleFiles(WebFileChooserRequestPolicy.Kind.SAVE), false, "save is not multiple files");
        System.out.println("WebView file chooser request policy tests passed.");
    }
}
