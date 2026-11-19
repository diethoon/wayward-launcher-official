package com.wayward.launcher;

/**
 * Builds document IDs for Android's built-in ExternalStorageProvider only.
 *
 * DocumentsContract document IDs are opaque for arbitrary providers. The
 * ExternalStorageProvider implementation is the supported exception: IDs use
 * the form "<volume-root-id>:<path-relative-to-volume-root>". A selected tree
 * document ID can therefore be extended with a validated relative child path.
 */
final class SafImageDocumentResolver {
    static final String EXTERNAL_STORAGE_AUTHORITY =
            "com.android.externalstorage.documents";

    private SafImageDocumentResolver() {}

    static String buildDocumentId(String authority, String treeDocumentId,
                                  String normalizedRelativePath) {
        if (!EXTERNAL_STORAGE_AUTHORITY.equals(authority)) return null;
        if (treeDocumentId == null || normalizedRelativePath == null) return null;

        int volumeSeparator = treeDocumentId.indexOf(':');
        if (volumeSeparator <= 0) return null;
        if (treeDocumentId.indexOf((char) 92) >= 0 || treeDocumentId.indexOf((char) 0) >= 0) return null;

        String rootPath = treeDocumentId.substring(volumeSeparator + 1);
        while (rootPath.endsWith("/")) rootPath = rootPath.substring(0, rootPath.length() - 1);
        if (!validSegments(rootPath, true)) return null;

        // Image requests must remain inside the selected game folder's images/.
        if (!normalizedRelativePath.startsWith("images/")) return null;
        if (!validSegments(normalizedRelativePath, false)) return null;

        String base = treeDocumentId;
        while (base.length() > volumeSeparator + 1 && base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String separator = (base.endsWith(":") || base.endsWith("/")) ? "" : "/";
        return base + separator + normalizedRelativePath;
    }

    private static boolean validSegments(String path, boolean allowEmptyRoot) {
        if (path == null) return false;
        if (path.isEmpty()) return allowEmptyRoot;
        String[] segments = path.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) return false;
            if (segment.indexOf((char) 92) >= 0 || segment.indexOf((char) 0) >= 0) return false;
        }
        return true;
    }
}
