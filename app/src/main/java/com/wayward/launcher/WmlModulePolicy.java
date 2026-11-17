package com.wayward.launcher;

/** Blocks the optional Image Studio generator module while preserving local image-pack resources. */
final class WmlModulePolicy {
    private static final String IMAGE_STUDIO_ID = "imagestudio";
    private static final String IMAGE_STUDIO_PATH = "wml/mods/imagestudio";

    private WmlModulePolicy() {}

    static boolean isBlockedModId(String id) {
        return id != null && IMAGE_STUDIO_ID.equalsIgnoreCase(id.trim());
    }

    static boolean isBlockedResourcePath(String path) {
        if (path == null) return false;
        String normalized = path.replace((char) 92, '/');
        while (normalized.startsWith("/")) normalized = normalized.substring(1);
        return IMAGE_STUDIO_PATH.equalsIgnoreCase(normalized)
                || normalized.regionMatches(true, 0, IMAGE_STUDIO_PATH + "/", 0,
                        IMAGE_STUDIO_PATH.length() + 1);
    }
}
