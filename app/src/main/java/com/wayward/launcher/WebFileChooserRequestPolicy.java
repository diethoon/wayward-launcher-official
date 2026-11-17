package com.wayward.launcher;

/**
 * Explicit WebView chooser-mode semantics. MODE_OPEN_FOLDER is API 37;
 * keep the numeric value here so the app can still compile against SDK 35.
 */
final class WebFileChooserRequestPolicy {
    static final int MODE_OPEN = 0;
    static final int MODE_OPEN_MULTIPLE = 1;
    static final int MODE_OPEN_FOLDER = 2;
    static final int MODE_SAVE = 3;
    static final int PERMISSION_MODE_READ = 0;
    static final int PERMISSION_MODE_READ_WRITE = 1;

    enum Kind { OPEN_SINGLE, OPEN_MULTIPLE, OPEN_FOLDER, SAVE, UNSUPPORTED }

    private WebFileChooserRequestPolicy() {}

    static Kind classify(int mode) {
        switch (mode) {
            case MODE_OPEN: return Kind.OPEN_SINGLE;
            case MODE_OPEN_MULTIPLE: return Kind.OPEN_MULTIPLE;
            case MODE_OPEN_FOLDER: return Kind.OPEN_FOLDER;
            case MODE_SAVE: return Kind.SAVE;
            default: return Kind.UNSUPPORTED;
        }
    }

    static boolean allowsMultipleFiles(Kind kind) {
        return kind == Kind.OPEN_MULTIPLE;
    }

    static boolean isFolder(Kind kind) {
        return kind == Kind.OPEN_FOLDER;
    }

    static boolean isSave(Kind kind) {
        return kind == Kind.SAVE;
    }

    static String label(Kind kind) {
        switch (kind) {
            case OPEN_SINGLE: return "single_file";
            case OPEN_MULTIPLE: return "multiple_files";
            case OPEN_FOLDER: return "folder";
            case SAVE: return "save_file";
            default: return "unsupported";
        }
    }
}
