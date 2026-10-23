package com.millenairekingdoms.economy;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

/** Atomic, versioned payment-intent journal. This does NOT atomically commit chunk inventories.
 * A future payment coordinator must verify physical save/recovery barriers before issuing a paid receipt.
 * Unsupported locking/atomic-move/directory-sync operations fail closed; no unsafe write fallback. */
public final class RoyalPaymentJournal {
    private static final int FORMAT = 1;
    private static final long MAX_NBT_BYTES = 16L * 1024 * 1024;
    private final Path directory;

    public enum State { PREPARED, PAID, COMMITTED, REFUND_PREPARED, REFUNDED, CANCELLED, RECOVERY_REQUIRED }

    public static final class Entry {
        private final UUID id;
        private final State state;
        private final CompoundTag intent, recovery;
        private Entry(UUID id, State state, CompoundTag intent, CompoundTag recovery) {
            this.id=id; this.state=state; this.intent=intent.copy(); this.recovery=recovery.copy();
        }
        public UUID id() { return id; }
        public State state() { return state; }
        public CompoundTag intent() { return intent.copy(); }
        public CompoundTag recovery() { return recovery.copy(); }
    }

    public RoyalPaymentJournal(Path directory) {
        this.directory=directory.toAbsolutePath().normalize();
        try {
            var created=new ArrayList<Path>();
            for(Path parent=this.directory; !Files.exists(parent); parent=parent.getParent()) created.add(parent);
            Files.createDirectories(this.directory);
            // Persist newly created directory entries too, not only the record rename within the leaf.
            for(Path parent:created) syncDirectory(parent);
            if(!created.isEmpty()) syncDirectory(created.getLast().getParent());
        }
        catch(IOException e) { throw failure(e); }
    }

    /** Bind a request ID permanently to its complete immutable intent. Exact replays never reset its state. */
    public synchronized Entry prepare(UUID id, CompoundTag intent, CompoundTag recovery) {
        return locked(() -> {
            Entry existing=read(id);
            if(existing!=null) {
                if(!existing.intent.equals(intent)) throw new IllegalArgumentException("Payment request ID is already bound to another intent");
                return existing;
            }
            Entry entry=new Entry(id,State.PREPARED,intent,recovery);
            write(entry); return entry;
        });
    }

    public synchronized Entry load(UUID id) { return locked(() -> read(id)); }

    /** Caller supplies the expected prior state. Only exact transition retries are idempotent. */
    public synchronized Entry transition(UUID id, State expected, State next, CompoundTag recovery) {
        return locked(() -> {
            Entry existing=read(id);
            if(existing==null) throw new IllegalArgumentException("Unknown payment request");
            if(existing.state==next && existing.recovery.equals(recovery)) return existing;
            if(existing.state!=expected || !allowed(expected,next)) throw new IllegalStateException("Invalid payment transition: " + existing.state + " -> " + next);
            Entry changed=new Entry(id,next,existing.intent,recovery);
            write(changed); return changed;
        });
    }

    private static boolean allowed(State from, State to) {
        return switch(from) {
            case PREPARED -> to==State.PAID || to==State.CANCELLED || to==State.RECOVERY_REQUIRED;
            case PAID -> to==State.COMMITTED || to==State.REFUND_PREPARED || to==State.RECOVERY_REQUIRED;
            case REFUND_PREPARED -> to==State.REFUNDED || to==State.RECOVERY_REQUIRED;
            // Ambiguous financial state needs a separately audited recovery procedure, never an automatic reset.
            case COMMITTED, REFUNDED, CANCELLED, RECOVERY_REQUIRED -> false;
        };
    }

    private Entry read(UUID id) throws IOException {
        Path file=file(id);
        if(!Files.exists(file)) return null;
        CompoundTag tag=NbtIo.readCompressed(file,NbtAccounter.create(MAX_NBT_BYTES));
        if(tag.getInt("format")!=FORMAT) throw new IllegalStateException("Unsupported royal payment format; original journal preserved");
        if(!tag.hasUUID("id") || !id.equals(tag.getUUID("id")) || !tag.contains("intent",10) || !tag.contains("recovery",10)) {
            throw new IllegalStateException("Invalid royal payment journal; original journal preserved");
        }
        State state;
        try { state=State.valueOf(tag.getString("state")); }
        catch(IllegalArgumentException e) { throw new IllegalStateException("Unknown royal payment state; original journal preserved",e); }
        return new Entry(id,state,tag.getCompound("intent"),tag.getCompound("recovery"));
    }

    private void write(Entry entry) throws IOException {
        CompoundTag tag=new CompoundTag();
        tag.putInt("format",FORMAT); tag.putUUID("id",entry.id); tag.putString("state",entry.state.name());
        tag.put("intent",entry.intent.copy()); tag.put("recovery",entry.recovery.copy());
        Path temporary=Files.createTempFile(directory,"."+entry.id+"-",".tmp");
        try {
            NbtIo.writeCompressed(tag,temporary);
            try(FileChannel file=FileChannel.open(temporary,StandardOpenOption.WRITE)) { file.force(true); }
            // Validate the same bounded format used on recovery before replacing a previous record.
            NbtIo.readCompressed(temporary,NbtAccounter.create(MAX_NBT_BYTES));
            Files.move(temporary,file(entry.id),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            syncDirectory(directory);
        } finally { Files.deleteIfExists(temporary); }
    }

    private Path file(UUID id) { return directory.resolve(id+".dat"); }

    private static void syncDirectory(Path directory) throws IOException {
        try(FileChannel parent=FileChannel.open(directory,StandardOpenOption.READ)) { parent.force(true); }
    }

    @FunctionalInterface private interface Operation<T> { T run() throws IOException; }
    private <T> T locked(Operation<T> action) {
        try(FileChannel channel=FileChannel.open(directory.resolve(".lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE)) {
            try(var lock=channel.tryLock()) {
                if(lock==null) throw new IllegalStateException("Royal payment journal is busy; retry before changing inventory");
                return action.run();
            }
        } catch(OverlappingFileLockException e) {
            throw new IllegalStateException("Royal payment journal is busy; retry before changing inventory",e);
        } catch(IOException e) { throw failure(e); }
    }

    private static IllegalStateException failure(IOException cause) {
        return new IllegalStateException("Cannot safely access royal payment journal; do not change inventory or issue a paid receipt",cause);
    }
}
