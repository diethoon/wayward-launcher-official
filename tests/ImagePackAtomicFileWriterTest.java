package com.wayward.launcher;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Stream;

public final class ImagePackAtomicFileWriterTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("wayward-atomic-write-");
        Path original = Files.write(root.resolve("image.png"), new byte[] {1, 2, 3, 4});
        ImagePackPathGuard guard = new ImagePackPathGuard(root.toFile());

        byte[] replacement = new byte[] {9, 8, 7, 6, 5};
        ImagePackAtomicFileWriter.write(guard, "image.png", replacement);
        check(Arrays.equals(Files.readAllBytes(original), replacement),
                "successful replacement must install the complete new content");

        byte[] stableContent = new byte[] {4, 3, 2, 1};
        Files.write(original, stableContent);
        expectWriteFailure(() -> ImagePackAtomicFileWriter.writeForTest(
                guard, "image.png", new byte[] {0, 0, 0, 0, 0},
                (temporary, bytes) -> {
                    try (FileOutputStream output = new FileOutputStream(temporary)) {
                        output.write(bytes, 0, 2);
                        output.flush();
                    }
                    throw new IOException("simulated short write");
                },
                (temporary, target) -> { throw new AssertionError("replacement must not run after write failure"); }
        ), "temporary write failure");

        check(Arrays.equals(Files.readAllBytes(original), stableContent),
                "a failed temporary write must preserve the previous file");
        check(countTemporaryFiles(root) == 0, "failed writes must remove their temporary files");

        expectWriteFailure(() -> ImagePackAtomicFileWriter.writeForTest(
                guard, "image.png", new byte[] {5, 5, 5, 5},
                (temporary, bytes) -> Files.write(temporary.toPath(), bytes),
                (temporary, target) -> { throw new IOException("simulated replace failure"); }
        ), "replacement failure");

        check(Arrays.equals(Files.readAllBytes(original), stableContent),
                "a failed replacement must preserve the previous file");
        check(countTemporaryFiles(root) == 0, "failed replacements must remove their temporary files");

        System.out.println("ImagePackAtomicFileWriterTest passed: atomic replacement and failure preservation.");
    }

    private static long countTemporaryFiles(Path parent) throws IOException {
        try (Stream<Path> children = Files.list(parent)) {
            return children.filter(path -> path.getFileName().toString().startsWith(".wayward-imagepack-")).count();
        }
    }

    private static void expectWriteFailure(CheckedAction action, String label) throws Exception {
        try {
            action.run();
            throw new AssertionError("Expected write failure: " + label);
        } catch (IOException expected) {
            // Expected: original file must survive.
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private interface CheckedAction { void run() throws Exception; }
}
