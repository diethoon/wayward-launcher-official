package com.wayward.launcher;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Canonical-path boundary for every entry below the selected image-pack folder. */
final class ImagePackPathGuard {
    private final File selectedRoot;
    private final Path selectedRootPath;

    ImagePackPathGuard(File root) throws IOException {
        if (root == null) throw new SecurityException("No image-pack folder is selected.");
        File absolute = root.getAbsoluteFile();
        File canonical = absolute.getCanonicalFile();
        Path absolutePath = absolute.toPath().normalize();
        Path canonicalPath = canonical.toPath();
        if (!absolutePath.equals(canonicalPath)) {
            throw new SecurityException("The selected image-pack folder changed through a symbolic link.");
        }
        if (!Files.isDirectory(canonicalPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("The selected image-pack folder is unavailable.");
        }
        selectedRoot = absolute;
        selectedRootPath = canonicalPath;
    }

    File rootDirectory() throws IOException {
        ensureRootStable();
        return selectedRootPath.toFile();
    }

    File resolveDirectory(String relativePath) throws IOException {
        ensureRootStable();
        Path current = selectedRootPath;
        for (String component : splitPath(relativePath)) {
            Path candidate = current.resolve(component).normalize();
            if (Files.isSymbolicLink(candidate)) {
                throw new SecurityException("Symbolic-link directory traversal is not allowed.");
            }
            Path canonical = candidate.toFile().getCanonicalFile().toPath();
            requireInsideSelectedRoot(canonical);
            if (!Files.isDirectory(canonical, LinkOption.NOFOLLOW_LINKS) || !Files.isReadable(canonical)) {
                throw new IOException("Directory not found or unreadable.");
            }
            current = canonical;
        }
        return current.toFile();
    }

    File resolveFile(String relativePath) throws IOException {
        File target = resolveTarget(relativePath);
        Path path = target.toPath();
        if (Files.isSymbolicLink(path)
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || !Files.isReadable(path)) {
            throw new IOException("File not found or unreadable.");
        }
        requireInsideSelectedRoot(target.getCanonicalFile().toPath());
        return target;
    }

    File resolveTarget(String relativePath) throws IOException {
        String[] components = splitPath(relativePath);
        if (components.length == 0) throw new IllegalArgumentException("A relative file or directory name is required.");
        File parent = resolveDirectory(join(components, components.length - 1));
        Path target = parent.toPath().resolve(validateName(components[components.length - 1])).normalize();
        requireInsideSelectedRoot(target);
        if (Files.isSymbolicLink(target)) {
            throw new SecurityException("Symbolic-link entries cannot be modified.");
        }
        requireInsideSelectedRoot(target.toFile().getCanonicalFile().toPath());
        return target.toFile();
    }

    boolean isSafeListedEntry(File child) {
        try {
            ensureRootStable();
            Path path = child.getAbsoluteFile().toPath().normalize();
            if (Files.isSymbolicLink(path)) return false;
            Path canonical = child.getCanonicalFile().toPath();
            if (!isInsideSelectedRoot(canonical)) return false;
            return Files.isDirectory(canonical, LinkOption.NOFOLLOW_LINKS)
                    || Files.isRegularFile(canonical, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException | SecurityException error) {
            return false;
        }
    }

    private void ensureRootStable() throws IOException {
        Path current = selectedRoot.getCanonicalFile().toPath();
        if (!current.equals(selectedRootPath)
                || Files.isSymbolicLink(selectedRootPath)
                || !Files.isDirectory(selectedRootPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new SecurityException("The selected image-pack folder is no longer the same directory.");
        }
    }

    private void requireInsideSelectedRoot(Path candidate) {
        if (!isInsideSelectedRoot(candidate)) throw new SecurityException("Path escapes the selected image-pack folder.");
    }

    private boolean isInsideSelectedRoot(Path candidate) {
        return candidate != null && (candidate.equals(selectedRootPath) || candidate.startsWith(selectedRootPath));
    }

    private static String[] splitPath(String path) {
        if (path == null || path.length() > 4096 || path.startsWith("/")
                || path.indexOf('\\') >= 0 || path.indexOf(0) >= 0) {
            throw new IllegalArgumentException("Invalid relative image-pack path.");
        }
        if (path.isEmpty()) return new String[0];
        String[] components = path.split("/", -1);
        for (String component : components) validateName(component);
        return components;
    }

    private static String validateName(String value) {
        if (value == null || value.isEmpty() || value.length() > 255
                || ".".equals(value) || "..".equals(value)
                || value.indexOf('/') >= 0 || value.indexOf('\\') >= 0 || value.indexOf(0) >= 0) {
            throw new IllegalArgumentException("Invalid file or directory name.");
        }
        return value;
    }

    private static String join(String[] components, int count) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) result.append('/');
            result.append(components[i]);
        }
        return result.toString();
    }
}
