package com.wayward.launcher;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;

/** Writes to a sibling temporary file and atomically replaces the target only after fsync. */
final class ImagePackAtomicFileWriter {
    interface TempFileWriter { void write(File temporaryFile, byte[] bytes) throws IOException; }
    interface FileReplacer { void replace(File temporaryFile, File targetFile) throws IOException; }
    private ImagePackAtomicFileWriter() {}

    static void write(ImagePackPathGuard guard, String relativePath, byte[] bytes) throws IOException {
        writeForTest(guard, relativePath, bytes,
                ImagePackAtomicFileWriter::writeAndSync, ImagePackAtomicFileWriter::replaceAtomically);
    }

    static void writeForTest(ImagePackPathGuard guard, String relativePath, byte[] bytes,
                             TempFileWriter tempFileWriter, FileReplacer fileReplacer) throws IOException {
        if (guard == null || bytes == null || tempFileWriter == null || fileReplacer == null) {
            throw new IllegalArgumentException("A guard, payload, writer, and replacer are required.");
        }
        File target = guard.resolveTarget(relativePath);
        if (target.exists() && !Files.isRegularFile(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("The target exists but is not a regular file.");
        }
        File parent = target.getParentFile();
        if (parent == null || !parent.isDirectory()) throw new IOException("The target folder is unavailable.");

        File temporary = File.createTempFile(".wayward-imagepack-", ".tmp", parent);
        Throwable failure = null;
        try {
            tempFileWriter.write(temporary, bytes);
            if (!temporary.isFile() || temporary.length() != bytes.length) {
                throw new IOException("The temporary image-pack file was not fully written.");
            }
            File revalidatedTarget = guard.resolveTarget(relativePath);
            if (!target.equals(revalidatedTarget)) throw new IOException("The target path changed during the write.");
            if (target.exists() && !Files.isRegularFile(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("The target changed to a non-file entry during the write.");
            }
            fileReplacer.replace(temporary, target);
            if (temporary.exists()) throw new IOException("The temporary file was not consumed by replacement.");
        } catch (IOException | RuntimeException | Error error) {
            failure = error;
            throw error;
        } finally {
            if (temporary.exists() && !temporary.delete()) {
                IOException cleanupError = new IOException("Could not remove the temporary image-pack file.");
                if (failure != null) failure.addSuppressed(cleanupError);
                else throw cleanupError;
            }
        }
    }

    private static void writeAndSync(File temporary, byte[] bytes) throws IOException {
        try (FileOutputStream output = new FileOutputStream(temporary, false)) {
            output.write(bytes);
            output.flush();
            output.getFD().sync();
        }
    }

    private static void replaceAtomically(File temporary, File target) throws IOException {
        try {
            Files.move(temporary.toPath(), target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException error) {
            throw new IOException("This storage provider does not support atomic image-pack replacement.", error);
        }
    }
}
