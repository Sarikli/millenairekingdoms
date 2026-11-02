package com.millenairekingdoms.construction;

import java.io.DataInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.HashSet;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.*;
import net.minecraft.nbt.visitors.CollectToTag;
import net.minecraft.util.FastBufferedInputStream;

/** Read-only projections of the pinned native save. The64MiB allocation budget applies to selected data,
 * not every unrelated village. Both passes use the same open file; no native bytes are rewritten. */
final class NativeVillageReadback {
    private static final long MAX_SELECTED_BYTES=64L*1024*1024;
    private static final long MAX_EXPANDED_BYTES=512L*1024*1024;
    private static final int MAX_VILLAGES=100_000;

    private NativeVillageReadback() {}

    static void validate(Path file) { read(file,null); }
    static CompoundTag village(Path file,UUID id) { return read(file,Objects.requireNonNull(id)); }

    private static CompoundTag read(Path file,UUID id) {
        try(var channel=FileChannel.open(file,StandardOpenOption.READ)) {
            var index=parse(channel,-1);validateHeader(index);
            int selected=-1;var villages=index.getCompound("data").getList("villages",Tag.TAG_COMPOUND);var identities=new HashSet<UUID>();
            for(int n=0;n<villages.size();n++) {
                var village=villages.getCompound(n);
                if(!village.hasUUID("id") || !identities.add(village.getUUID("id")))
                    throw new IllegalStateException("Missing or duplicate saved construction village identity; original bytes preserved");
                if(id!=null && village.hasUUID("id") && id.equals(village.getUUID("id"))) {
                    if(selected>=0)throw new IllegalStateException("Duplicate saved construction village identity");
                    selected=n;
                }
            }
            if(id==null)return index;
            if(selected<0)throw new IllegalStateException("Village missing from saved Millenaire data; do not commit patronage");
            var root=parse(channel,selected);validateHeader(root);
            var result=root.getCompound("data").getList("villages",Tag.TAG_COMPOUND);
            if(result.size()!=1 || !result.getCompound(0).hasUUID("id") || !id.equals(result.getCompound(0).getUUID("id")))
                throw new IllegalStateException("Selected saved village identity changed; do not commit patronage");
            return result.getCompound(0);
        } catch(IOException|NbtException e) {
            throw new IllegalStateException("Cannot read bounded original Millenaire save projection; preserve it and do not accept construction: "+e.getMessage(),e);
        }
    }

    private static CompoundTag parse(FileChannel channel,int selected) throws IOException {
        channel.position(0);
        // Closing a decompressor must leave the pinned file open for the second pass.
        var source=new FilterInputStream(Channels.newInputStream(channel)) {@Override public void close() {}};
        try(var input=new DataInputStream(new FastBufferedInputStream(new ExpandedLimit(new GZIPInputStream(source))))) {
            var projection=new Projection(selected);
            NbtIo.parse(input,projection,NbtAccounter.create(MAX_SELECTED_BYTES));
            if(input.read()!=-1)throw new IOException("Trailing bytes in original Millenaire NBT");
            if(!(projection.getResult() instanceof CompoundTag root))throw new IOException("Original Millenaire root is not a compound");
            return root;
        }
    }

    private static void validateHeader(CompoundTag root) {
        var data=root.getCompound("data");
        if(!root.contains("data",Tag.TAG_COMPOUND) || !data.contains("villages",Tag.TAG_LIST)
                || data.getList("villages",Tag.TAG_COMPOUND).size()!=((ListTag)data.get("villages")).size()
                || data.contains("version") && !data.contains("version",Tag.TAG_INT) || data.getInt("version")<0 || data.getInt("version")>6
                || root.contains("DataVersion") && (!root.contains("DataVersion",Tag.TAG_INT)
                || root.getInt("DataVersion")>SharedConstants.getCurrentVersion().getDataVersion().getVersion()))
            throw new IllegalStateException("Unsupported/future Millenaire saved data; preserve original and postpone royal acceptance");
    }

    private static final class Projection extends CollectToTag {
        private final int selected;
        Projection(int selected) { this.selected=selected; }

        @Override public EntryResult visitEntry(TagType<?> type,String name) {
            if(depth()==1 && !name.equals("data") && !name.equals("DataVersion"))return EntryResult.SKIP;
            if(depth()==2 && !name.equals("villages") && !name.equals("version"))return EntryResult.SKIP;
            if(selected<0 && depth()==4 && !name.equals("id"))return EntryResult.SKIP;
            if(selected>=0 && depth()==4 && !name.equals("id") && !name.equals("pending_project") && !name.equals("buildings"))return EntryResult.SKIP;
            return super.visitEntry(type,name);
        }

        @Override public ValueResult visitList(TagType<?> type,int length) {
            if(depth()==3 && (length>MAX_VILLAGES || length<0 || length>0 && type!=CompoundTag.TYPE))
                throw new IllegalStateException("Invalid or oversized native village index; original bytes preserved");
            return super.visitList(type,length);
        }

        @Override public EntryResult visitElement(TagType<?> type,int index) {
            if(depth()==3 && selected>=0 && index!=selected)return EntryResult.SKIP;
            return super.visitElement(type,index);
        }
    }

    /** Skipped payload still has a finite decompression/work budget, independent of retained NBT allocations. */
    private static final class ExpandedLimit extends FilterInputStream {
        private long consumed;
        ExpandedLimit(InputStream input) { super(input); }
        private void count(long amount) throws IOException {
            if(amount>0 && (consumed+=amount)>MAX_EXPANDED_BYTES)throw new IOException("Native save exceeds bounded512MiB expanded read budget");
        }
        @Override public int read() throws IOException { int value=in.read();if(value>=0)count(1);return value; }
        @Override public int read(byte[] bytes,int offset,int length) throws IOException {
            int n=in.read(bytes,offset,(int)Math.min(length,MAX_EXPANDED_BYTES-consumed+1));count(n);return n;
        }
        @Override public long skip(long amount) throws IOException {
            long n=in.skip(Math.min(amount,MAX_EXPANDED_BYTES-consumed+1));count(n);return n;
        }
    }
}
