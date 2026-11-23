package com.millenairekingdoms.util;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

/**
 * Cross-platform file durability helpers. Linux/macOS support directory {@code fsync} via {@link FileChannel}, whereas
 * Windows rejects opening a directory channel with {@link AccessDeniedException} and may briefly lock newly created
 * temporary files during background scanning.
 */
public final class DurableFiles {
    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private DurableFiles() {}

    public static void syncDirectory(Path directory) throws IOException {
        syncDirectory(directory, WINDOWS);
    }

    public static void syncDirectory(Path directory, boolean windows) throws IOException {
        if (directory == null)
            return;
        if (!Files.isDirectory(directory))
            throw new IOException("Expected directory for sync: " + directory);
        if (windows)
            return;
        try (var channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (AccessDeniedException | UnsupportedOperationException ignored) {
            // Non-POSIX or mounted filesystems that reject directory read channels do not support directory fsync.
        }
    }

    public static void move(Path source, Path target, boolean replaceExisting) throws IOException {
        if (!replaceExisting && Files.exists(target, LinkOption.NOFOLLOW_LINKS))
            throw new FileAlreadyExistsException(target.toString());
        CopyOption[] atomicOptions = replaceExisting
                ? new CopyOption[] {StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING}
                : new CopyOption[] {StandardCopyOption.ATOMIC_MOVE};
        CopyOption[] fallbackOptions =
                replaceExisting ? new CopyOption[] {StandardCopyOption.REPLACE_EXISTING} : new CopyOption[0];
        int attempts = WINDOWS ? 4 : 1;
        for (int attempt = 1;; attempt++) {
            try {
                try {
                    Files.move(source, target, atomicOptions);
                } catch (AtomicMoveNotSupportedException fallback) {
                    Files.move(source, target, fallbackOptions);
                }
                return;
            } catch (AccessDeniedException retryable) {
                if (attempt >= attempts)
                    throw retryable;
                try {
                    Thread.sleep(15L * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    retryable.addSuppressed(interrupted);
                    throw retryable;
                }
            }
        }
    }
}
