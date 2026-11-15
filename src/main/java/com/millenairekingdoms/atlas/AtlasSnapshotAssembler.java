package com.millenairekingdoms.atlas;

import java.util.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;

/**
 * Stages bounded pages of ONE revision. A renderer receives a complete immutable frame, never mixed borders/markers.
 */
public final class AtlasSnapshotAssembler {
    public static final int MAX_SITES = 8192;
    public static final class Changed extends IllegalArgumentException {
        public Changed() {
            super("Atlas revision changed while paging");
        }
    }
    public record Frame(String revision, String dimension, List<CompoundTag> settlements, List<CompoundTag> kingdoms,
            List<CompoundTag> wars, CompoundTag pin, long observedTime) {
        public Frame {
            settlements = copy(settlements);
            kingdoms = copy(kingdoms);
            wars = copy(wars);
            pin = pin.copy();
        }
        private static List<CompoundTag> copy(List<CompoundTag> tags) {
            return tags.stream().map(CompoundTag::copy).toList();
        }
        @Override
        public List<CompoundTag> settlements() {
            return copy(settlements);
        }
        @Override
        public List<CompoundTag> kingdoms() {
            return copy(kingdoms);
        }
        @Override
        public List<CompoundTag> wars() {
            return copy(wars);
        }
        @Override
        public CompoundTag pin() {
            return pin.copy();
        }
    }
    private String revision, dimension;
    private int total;
    private UUID next, last;
    private final Map<UUID, CompoundTag> sites = new LinkedHashMap<>(), kingdoms = new LinkedHashMap<>(),
                                         wars = new LinkedHashMap<>();
    private CompoundTag pin = new CompoundTag();
    public Frame accept(CompoundTag page, UUID cursor) {
        if (!page.contains("revision") || !page.contains("dimension")
                || Identifier.tryParse(page.getStringOr("dimension", "")) == null
                || !page.contains("total") || page.getIntOr("total", 0) < 0 || page.getIntOr("total", 0) > MAX_SITES)
            throw new IllegalArgumentException("Invalid atlas snapshot header");
        UUID.fromString(page.getStringOr("revision", ""));
        if (cursor == null) {
            revision = page.getStringOr("revision", "");
            dimension = page.getStringOr("dimension", "");
            total = page.getIntOr("total", 0);
            sites.clear();
            kingdoms.clear();
            wars.clear();
            pin = page.getCompoundOrEmpty("historicalPin").copy();
            next = null;
            last = null;
        } else if (!cursor.equals(next) || !page.getStringOr("revision", "").equals(revision)
                || !page.getStringOr("dimension", "").equals(dimension) || page.getIntOr("total", 0) != total)
            throw new Changed();
        var list = list(page, "settlements");
        if (list.size() > AtlasViews.PAGE_SIZE || list.isEmpty() && total > 0)
            throw new IllegalArgumentException("Invalid atlas page size");
        for (var raw : list) {
            var site = (CompoundTag) raw;
            if (!com.millenairekingdoms.compat.KNbt.hasUUID(site, "id") || !site.contains("x") || !site.contains("z"))
                throw new IllegalArgumentException("Invalid atlas village");
            var id = com.millenairekingdoms.compat.KNbt.getUUID(site, "id");
            if (last != null && last.compareTo(id) >= 0)
                throw new IllegalArgumentException("Repeated/out-of-order atlas village");
            last = id;
            if (Math.abs((long) site.getIntOr("x", 0)) > 30_000_000 || Math.abs((long) site.getIntOr("z", 0)) > 30_000_000)
                throw new IllegalArgumentException("Invalid atlas position");
            if (!site.contains("politicalColor") || site.getIntOr("politicalColor", 0) >>> 24 != 255
                    || com.millenairekingdoms.compat.KNbt.hasUUID(site, "kingdom") && !site.contains("kingdomName"))
                throw new IllegalArgumentException("Missing political identity");
            for (var field : List.of("name", "type", "typeName", "culture", "ethos", "icon"))
                if (!site.contains(field) || site.getStringOr(field, "").length() > 256)
                    throw new IllegalArgumentException("Invalid atlas village description");
            if (Identifier.tryParse(site.getStringOr("icon", "")) == null || sites.putIfAbsent(id, site.copy()) != null)
                throw new IllegalArgumentException("Invalid atlas icon/identity");
        }
        merge(kingdoms, list(page, "kingdoms"));
        merge(wars, list(page, "wars"));
        if (sites.size() > total || kingdoms.size() > MAX_SITES || wars.size() > MAX_SITES)
            throw new IllegalArgumentException("Atlas frame exceeds declared limits");
        if (com.millenairekingdoms.compat.KNbt.hasUUID(page, "next")) {
            next = com.millenairekingdoms.compat.KNbt.getUUID(page, "next");
            if (!next.equals(last) || sites.size() >= total)
                throw new IllegalArgumentException("Invalid atlas continuation cursor");
            return null;
        }
        next = null;
        for (var site : sites.values()) {
            if (com.millenairekingdoms.compat.KNbt.hasUUID(site, "kingdom")) {
                var owner = kingdoms.get(com.millenairekingdoms.compat.KNbt.getUUID(site, "kingdom"));
                if (owner == null || owner.getIntOr("color", 0) != site.getIntOr("politicalColor", 0)
                        || !owner.getStringOr("name", "").equals(site.getStringOr("kingdomName", "")))
                    throw new IllegalArgumentException("Inconsistent kingdom identity");
            } else if (site.getIntOr("politicalColor", 0) != AtlasColors.NEUTRAL)
                throw new IllegalArgumentException("Invalid independent color");
        }
        if (sites.size() != total)
            throw new IllegalArgumentException("Truncated atlas snapshot");
        return new Frame(revision, dimension, List.copyOf(sites.values()), List.copyOf(kingdoms.values()),
                List.copyOf(wars.values()), pin, page.getLongOr("observedTime", 0L));
    }
    private static ListTag list(CompoundTag page, String key) {
        if (!page.contains(key))
            throw new IllegalArgumentException("Missing atlas list");
        var list = (ListTag) page.get(key);
        if (!list.isEmpty() && com.millenairekingdoms.compat.KNbt.elementType(list) != Tag.TAG_COMPOUND)
            throw new IllegalArgumentException("Invalid atlas list type");
        return list;
    }
    private static void merge(Map<UUID, CompoundTag> into, ListTag list) {
        if (list.size() > AtlasViews.PAGE_SIZE)
            throw new IllegalArgumentException("Atlas metadata page too large");
        for (var raw : list) {
            var tag = (CompoundTag) raw;
            if (!com.millenairekingdoms.compat.KNbt.hasUUID(tag, "id"))
                throw new IllegalArgumentException("Invalid atlas metadata UUID");
            var old = into.putIfAbsent(com.millenairekingdoms.compat.KNbt.getUUID(tag, "id"), tag.copy());
            if (old != null && !old.equals(tag))
                throw new Changed();
        }
    }
}
