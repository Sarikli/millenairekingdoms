package com.millenairekingdoms.atlas.geography;

import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.io.IOException;
import java.util.*;
import net.minecraft.nbt.*;

/** Own geography files only. Strict bounds, atomic replacement/read-back and preserved future bytes. */
final class GeographyFiles {
    static final long MAX_ACCOUNTED_BYTES=16L*1024*1024;
    private GeographyFiles(){}
    static CompoundTag read(Path file){return read(file,MAX_ACCOUNTED_BYTES);}
    static CompoundTag read(Path file,long accounted){try{if(Files.size(file)>Math.min(8L*1024*1024,accounted))throw new IllegalStateException("Geography file exceeds compressed bound; preserve it");return NbtIo.readCompressed(file,NbtAccounter.create(accounted));}catch(IOException e){throw new IllegalStateException("Geography bytes unavailable; no reset",e);}}
    static void keys(CompoundTag value,Set<String> keys){if(!value.getAllKeys().equals(keys))throw new IllegalArgumentException("Unsupported geography fields; bytes preserved");}
    static void require(CompoundTag value,String key,int type){if(!value.contains(key,type))throw new IllegalArgumentException("Missing/wrong geography field: "+key);}
    static void write(Path file,CompoundTag value){var temporary=file.resolveSibling(file.getFileName()+"."+UUID.randomUUID()+".tmp");try{
        Files.createDirectories(file.getParent());NbtIo.writeCompressed(value,temporary);try(var c=FileChannel.open(temporary,StandardOpenOption.WRITE)){c.force(true);}if(!read(temporary).equals(value))throw new IllegalStateException("Geography read-back mismatch");Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);try(var c=FileChannel.open(file.getParent(),StandardOpenOption.READ)){c.force(true);}
    }catch(IOException e){throw new IllegalStateException("Cannot acknowledge geography write",e);}finally{try{Files.deleteIfExists(temporary);}catch(IOException ignored){}}}
}
