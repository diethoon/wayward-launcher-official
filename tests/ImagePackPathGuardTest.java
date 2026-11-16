package com.wayward.launcher;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ImagePackPathGuardTest {
    public static void main(String[] args) throws Exception {
        Path external = Files.createTempDirectory("wayward-selected-root-");
        Path selected = Files.createDirectories(external.resolve("Download/image-packs"));
        Path siblingDocuments = Files.createDirectories(external.resolve("Documents"));
        Path allowed = Files.createDirectories(selected.resolve("nested"));
        Path allowedFile = Files.write(allowed.resolve("inside.png"), new byte[] {1, 2, 3});
        Path outsideFile = Files.write(siblingDocuments.resolve("private.txt"), new byte[] {9, 8, 7});

        ImagePackPathGuard guard = new ImagePackPathGuard(selected.toFile());
        check(guard.resolveDirectory("nested").equals(allowed.toFile()), "selected subfolder must resolve");
        check(guard.resolveFile("nested/inside.png").equals(allowedFile.toFile()), "selected file must resolve");
        check(!guard.isSafeListedEntry(outsideFile.toFile()), "sibling internal-storage files must not be listable");

        expectRejected(() -> guard.resolveDirectory("../Documents"), "parent traversal to sibling folder");
        expectRejected(() -> guard.resolveFile("../Documents/private.txt"), "read from sibling folder");
        expectRejected(() -> guard.resolveTarget("../Documents/injected.txt"), "write into sibling folder");
        expectRejected(() -> ImagePackAtomicFileWriter.write(guard, "../Documents/injected.txt",
                new byte[] {7, 7, 7}), "atomic writer into sibling folder");
        check(!Files.exists(siblingDocuments.resolve("injected.txt")),
                "the atomic writer must not create files outside the selected root");
        expectRejected(() -> guard.resolveTarget("nested/../../Documents/injected.txt"), "nested traversal");
        expectRejected(() -> guard.resolveDirectory(selected.toAbsolutePath().toString()), "absolute path");

        Path link = selected.resolve("linked-documents");
        try {
            Files.createSymbolicLink(link, siblingDocuments);
            check(!guard.isSafeListedEntry(link.toFile()), "escaping symbolic links must be hidden from listings");
            expectRejected(() -> guard.resolveDirectory("linked-documents"), "traverse symbolic link to sibling");
            expectRejected(() -> guard.resolveFile("linked-documents/private.txt"), "read through symbolic link");
            expectRejected(() -> guard.resolveTarget("linked-documents/injected.txt"), "write through symbolic link");
        } catch (UnsupportedOperationException | SecurityException error) {
            throw new AssertionError("The test environment must support symbolic links for this security test.", error);
        }

        Path inRootLink = selected.resolve("linked-inside");
        Files.createSymbolicLink(inRootLink, allowed);
        check(!guard.isSafeListedEntry(inRootLink.toFile()), "symbolic-link entries must not be exposed");

        Path movedRoot = external.resolve("moved-image-packs");
        Files.move(selected, movedRoot);
        Files.createSymbolicLink(selected, siblingDocuments);
        expectRejected(() -> guard.rootDirectory(), "selected-root replacement with an escaping symlink");

        System.out.println("ImagePackPathGuardTest passed: selected-root boundary, traversal, and symlink escape blocked.");
    }

    private static void expectRejected(CheckedAction action, String label) throws Exception {
        try {
            action.run();
            throw new AssertionError("Expected rejection: " + label);
        } catch (SecurityException | IllegalArgumentException | java.io.IOException expected) {
            // Expected: the path is outside the selected directory or not a valid entry.
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private interface CheckedAction { void run() throws Exception; }
}
