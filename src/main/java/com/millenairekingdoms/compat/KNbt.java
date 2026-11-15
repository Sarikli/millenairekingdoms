package com.millenairekingdoms.compat;

import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.Tag;

/** Portage 26.3 : CompoundTag n'a plus getUUID / putUUID / hasUUID. Même format qu'avant (tableau de 4 entiers). */
public final class KNbt {
    private KNbt() {}

    public static UUID getUUID(CompoundTag tag, String key) {
        return tag.read(key, UUIDUtil.CODEC).orElseThrow(() -> new IllegalArgumentException("UUID absent : " + key));
    }

    public static boolean hasUUID(CompoundTag tag, String key) {
        return tag.read(key, UUIDUtil.CODEC).isPresent();
    }

    public static void putUUID(CompoundTag tag, String key, UUID value) {
        tag.store(key, UUIDUtil.CODEC, value);
    }

    /** ancien ListTag.getElementType() : type du premier élément, 0 si la liste est vide */
    public static byte elementType(ListTag list) {
        return list.isEmpty() ? 0 : list.get(0).getId();
    }

    /** ancien CompoundTag.contains(clé, type) ; 99 = n'importe quel nombre */
    public static boolean contains(CompoundTag tag, String key, int type) {
        Tag t = tag.get(key);
        if (t == null) return false;
        return type == 99 ? t instanceof NumericTag : t.getId() == type;
    }

    public static int[] intArray(CompoundTag tag, String key) {
        return tag.getIntArray(key).orElseGet(() -> new int[0]);
    }

    public static long[] longArray(CompoundTag tag, String key) {
        return tag.getLongArray(key).orElseGet(() -> new long[0]);
    }

    /** ancien com.millenairekingdoms.compat.KNbt.saveStack(registries, ItemStack) */
    public static Tag saveStack(HolderLookup.Provider registries, ItemStack stack) {
        return ItemStack.OPTIONAL_CODEC.encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), stack).getOrThrow();
    }

    /** ancien com.millenairekingdoms.compat.KNbt.parseStack(registries, tag) */
    public static Optional<ItemStack> parseStack(HolderLookup.Provider registries, Tag tag) {
        return ItemStack.OPTIONAL_CODEC.parse(registries.createSerializationContext(NbtOps.INSTANCE), tag).result();
    }

    /** ancien com.millenairekingdoms.compat.KNbt.componentJson(component, registries) */
    public static String componentJson(Component component, HolderLookup.Provider registries) {
        return ComponentSerialization.CODEC.encodeStart(registries.createSerializationContext(JsonOps.INSTANCE), component).getOrThrow().toString();
    }

    /** ancien com.millenairekingdoms.compat.KNbt.componentFromJson(json, registries) */
    public static Component componentFromJson(String json, HolderLookup.Provider registries) {
        return ComponentSerialization.CODEC.parse(registries.createSerializationContext(JsonOps.INSTANCE), com.google.gson.JsonParser.parseString(json)).result().orElse(null);
    }

    /** ancien Entity.save(CompoundTag) : null si l'entité refuse d'être enregistrée */
    public static CompoundTag saveEntity(Entity entity) {
        TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, entity.registryAccess());
        return entity.save(out) ? out.buildResult() : null;
    }

    /** ancien SavedData.Factory : le fichier reste data/<nom>.dat */
    public static <T extends SavedData> SavedDataType<T> savedType(String name, Supplier<T> fresh,
            BiFunction<CompoundTag, HolderLookup.Provider, T> load, BiFunction<T, HolderLookup.Provider, CompoundTag> save) {
        return new SavedDataType<T>(Identifier.withDefaultNamespace(name), level -> fresh.get(),
                level -> CompoundTag.CODEC.xmap(tag -> load.apply(tag, level.registryAccess()), data -> save.apply(data, level.registryAccess())));
    }
}
