package com.millenairekingdoms.history;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

/** Separate history files. Never writes native, political, financial or cargo files. */
public final class HistoryFiles {
    private HistoryFiles() {}
    public static CompoundTag read(Path file) {
        try { return NbtIo.readCompressed(file, NbtAccounter.create(1_048_576)); }
        catch (IOException e) { throw new IllegalStateException("Cannot read history; original bytes preserved: " + file, e); }
    }
    public static void keys(CompoundTag tag, Set<String> expected) {
        if (!tag.getAllKeys().equals(expected)) throw new IllegalArgumentException("Unknown or missing history fields: " + tag.getAllKeys());
    }
    public static void write(Path file, CompoundTag tag) {
        Path temp = file.resolveSibling(file.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            NbtIo.writeCompressed(tag, temp);
            try (var ch = FileChannel.open(temp, StandardOpenOption.WRITE)) { ch.force(true); }
            if (!read(temp).equals(tag)) throw new IOException("History read-back mismatch");
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            try (var ch = FileChannel.open(file.getParent(), StandardOpenOption.READ)) { ch.force(true); }
        } catch (IOException e) { throw new IllegalStateException("Cannot acknowledge history write: " + file, e); }
        finally { try { Files.deleteIfExists(temp); } catch (IOException ignored) { /* Preserve any unremovable temporary. */ } }
    }
}
