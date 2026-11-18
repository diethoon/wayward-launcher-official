package com.wayward.launcher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/** File-only WebView chooser semantics; folder selection belongs to the session SAF bridge. */
final class WebFileChooserRequestPolicy {
    static final int MODE_OPEN = 0;
    static final int MODE_OPEN_MULTIPLE = 1;
    static final int MODE_SAVE = 3;

    enum Kind { OPEN_SINGLE, OPEN_MULTIPLE, SAVE, UNSUPPORTED }

    private WebFileChooserRequestPolicy() {}

    static Kind classify(int mode) {
        switch (mode) {
            case MODE_OPEN: return Kind.OPEN_SINGLE;
            case MODE_OPEN_MULTIPLE: return Kind.OPEN_MULTIPLE;
            case MODE_SAVE: return Kind.SAVE;
            default: return Kind.UNSUPPORTED;
        }
    }

    static boolean allowsMultipleFiles(Kind kind) {
        return kind == Kind.OPEN_MULTIPLE;
    }

    static boolean isSave(Kind kind) {
        return kind == Kind.SAVE;
    }

    static List<String> parseAcceptTypes(String[] acceptTypes) {
        if (acceptTypes == null || acceptTypes.length == 0) return Collections.emptyList();
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (String accept : acceptTypes) {
            if (accept == null) continue;
            for (String raw : accept.split("[,;\\s]+")) {
                String value = raw.trim().toLowerCase(Locale.ROOT);
                if (value.isEmpty()) continue;
                boolean extension = value.matches("\\.[a-z0-9][a-z0-9._+-]*");
                boolean mime = value.matches("[a-z0-9!#$&^_.+-]+/(?:[a-z0-9!#$&^_.+-]+|\\*)");
                if (!extension && !mime) {
                    throw new IllegalArgumentException("Unsupported accept filter token.");
                }
                values.add(value);
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    static boolean acceptsFile(List<String> acceptTypes, String actualMime, String displayName) {
        if (acceptTypes == null || acceptTypes.isEmpty()) return true;
        String mime = actualMime == null ? "" : actualMime.trim().toLowerCase(Locale.ROOT);
        String name = displayName == null ? "" : displayName.toLowerCase(Locale.ROOT);
        for (String accepted : acceptTypes) {
            if (accepted.startsWith(".")) {
                if (!name.isEmpty() && name.endsWith(accepted)) return true;
                continue;
            }
            if ("*/*".equals(accepted)) return true;
            if (accepted.equals(mime)) return true;
            int slash = accepted.indexOf('/');
            if (slash > 0 && accepted.endsWith("/*")
                    && mime.startsWith(accepted.substring(0, slash + 1))) return true;
        }
        return false;
    }

    static String label(Kind kind) {
        switch (kind) {
            case OPEN_SINGLE: return "single_file";
            case OPEN_MULTIPLE: return "multiple_files";
            case SAVE: return "save_file";
            default: return "unsupported";
        }
    }
}