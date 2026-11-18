package com.wayward.launcher;

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

    static String label(Kind kind) {
        switch (kind) {
            case OPEN_SINGLE: return "single_file";
            case OPEN_MULTIPLE: return "multiple_files";
            case SAVE: return "save_file";
            default: return "unsupported";
        }
    }
}
