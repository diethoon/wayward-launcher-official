package com.wayward.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

/** JVM regression tests for Android WebView file chooser request policy. */
public final class WebFileChooserRequestPolicyUnitTest {
    @Test
    public void classifiesOnlyPublicApi35FileModes() {
        assertEquals(WebFileChooserRequestPolicy.Kind.OPEN_SINGLE,
                WebFileChooserRequestPolicy.classify(0));
        assertEquals(WebFileChooserRequestPolicy.Kind.OPEN_MULTIPLE,
                WebFileChooserRequestPolicy.classify(1));
        assertEquals(WebFileChooserRequestPolicy.Kind.UNSUPPORTED,
                WebFileChooserRequestPolicy.classify(2));
        assertEquals(WebFileChooserRequestPolicy.Kind.SAVE,
                WebFileChooserRequestPolicy.classify(3));
        assertEquals(WebFileChooserRequestPolicy.Kind.UNSUPPORTED,
                WebFileChooserRequestPolicy.classify(-1));
        assertEquals(WebFileChooserRequestPolicy.Kind.UNSUPPORTED,
                WebFileChooserRequestPolicy.classify(4));
    }

    @Test
    public void onlyExplicitMultipleModeAllowsMultipleFiles() {
        assertFalse(WebFileChooserRequestPolicy.allowsMultipleFiles(
                WebFileChooserRequestPolicy.Kind.OPEN_SINGLE));
        assertTrue(WebFileChooserRequestPolicy.allowsMultipleFiles(
                WebFileChooserRequestPolicy.Kind.OPEN_MULTIPLE));
        assertFalse(WebFileChooserRequestPolicy.allowsMultipleFiles(
                WebFileChooserRequestPolicy.Kind.SAVE));
    }

    @Test
    public void parsesAcceptMimeAndExtensionTokensWithoutLosingExtensions() {
        List<String> filters = WebFileChooserRequestPolicy.parseAcceptTypes(
                new String[] { "image/*,.PNG,.unknown", "application/pdf,.png" });
        assertEquals(Arrays.asList("image/*", ".png", ".unknown", "application/pdf"), filters);
    }

    @Test
    public void matchesMimeWildcardOrExtensionAndGenericProviderMime() {
        List<String> filters = WebFileChooserRequestPolicy.parseAcceptTypes(
                new String[] { "image/*,.json,application/json" });
        assertTrue(WebFileChooserRequestPolicy.acceptsFile(filters, "image/jpeg", "photo.jpg"));
        assertTrue(WebFileChooserRequestPolicy.acceptsFile(filters, "application/octet-stream", "save.json"));
        assertFalse(WebFileChooserRequestPolicy.acceptsFile(filters, "text/plain", "notes.txt"));
    }

    @Test
    public void matchesCompoundExtensionsAndAcceptsEmptyFilterAsUnrestricted() {
        assertTrue(WebFileChooserRequestPolicy.acceptsFile(
                Collections.singletonList(".tar.gz"), null, "backup.tar.gz"));
        assertTrue(WebFileChooserRequestPolicy.acceptsFile(
                Collections.emptyList(), "text/plain", "notes.txt"));
    }

    @Test
    public void rejectsInvalidNonEmptyAcceptTokens() {
        assertThrows(IllegalArgumentException.class, () ->
                WebFileChooserRequestPolicy.parseAcceptTypes(new String[] { "not-a-filter" }));
    }

    @Test
    public void returnsStableSemanticLabels() {
        assertEquals("single_file", WebFileChooserRequestPolicy.label(
                WebFileChooserRequestPolicy.Kind.OPEN_SINGLE));
        assertEquals("multiple_files", WebFileChooserRequestPolicy.label(
                WebFileChooserRequestPolicy.Kind.OPEN_MULTIPLE));
        assertEquals("save_file", WebFileChooserRequestPolicy.label(
                WebFileChooserRequestPolicy.Kind.SAVE));
        assertEquals("unsupported", WebFileChooserRequestPolicy.label(
                WebFileChooserRequestPolicy.Kind.UNSUPPORTED));
    }
}
