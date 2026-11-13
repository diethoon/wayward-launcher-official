package com.wayward.launcher;

/** Plain-JDK contract test for the ExternalStorageProvider image document-ID fast path. */
public final class SafImageDocumentResolverTest {
    public static void main(String[] args) {
        expect("primary:Download/Wayward/images/illustrious/characters/pippa/a.webp",
                SafImageDocumentResolver.buildDocumentId(
                        "com.android.externalstorage.documents",
                        "primary:Download/Wayward",
                        "images/illustrious/characters/pippa/a.webp"));
        expect("ABCD-1234:Wayward/images/test.webp",
                SafImageDocumentResolver.buildDocumentId(
                        "com.android.externalstorage.documents",
                        "ABCD-1234:Wayward/",
                        "images/test.webp"));
        expect("primary:images/test.webp",
                SafImageDocumentResolver.buildDocumentId(
                        "com.android.externalstorage.documents", "primary:", "images/test.webp"));

        expect(null, SafImageDocumentResolver.buildDocumentId(
                "com.google.android.apps.docs.storage", "root-id", "images/test.webp"));
        expect(null, SafImageDocumentResolver.buildDocumentId(
                "com.android.externalstorage.documents", "invalid-root", "images/test.webp"));
        expect(null, SafImageDocumentResolver.buildDocumentId(
                "com.android.externalstorage.documents", "primary:Wayward", "wml/mods/a/mod.js"));
        expect(null, SafImageDocumentResolver.buildDocumentId(
                "com.android.externalstorage.documents", "primary:Wayward", "images/a/../b.webp"));
        expect(null, SafImageDocumentResolver.buildDocumentId(
                "com.android.externalstorage.documents", "primary:Wayward", "images//a.webp"));
        expect(null, SafImageDocumentResolver.buildDocumentId(
                "com.android.externalstorage.documents", "primary:Wayward",
                "images/" + (char) 92 + "a.webp"));

        System.out.println("SafImageDocumentResolverTest: PASS");
    }

    private static void expect(String expected, String actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError("expected=" + expected + ", actual=" + actual);
        }
    }
}
