package com.millenairekingdoms.conquest;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/** One ledger per dimension, separate from Millénaire's village data. Server thread only. */
public final class ConquestSavedData extends SavedData {
    public static final String NAME = "millenairekingdoms_conquests";
    public static final int REQUIRED_WINS = 2;
    private static final int FORMAT = 1;
    private final Map<UUID, Integer> wins = new HashMap<>();
    private final Map<UUID, Raid> active = new HashMap<>();
    private final Map<UUID, Result> lastResults = new HashMap<>();

    public record Raid(UUID id, UUID attacker, UUID target, long startGameTime,
                       UUID initiator, boolean victoryRecorded) {
        Raid won() { return new Raid(id, attacker, target, startGameTime, initiator, true); }
    }
    public enum Outcome { WON, LOST, ABORTED }
    public record Result(UUID raidId, UUID attacker, Outcome outcome) {}

    public static Factory<ConquestSavedData> factory() {
        return new Factory<>(ConquestSavedData::new, ConquestSavedData::load, null);
    }

    public static ConquestSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(factory(), NAME);
    }

    public int wins(UUID target) { return wins.getOrDefault(target, 0); }
    public Raid raid(UUID target) { return active.get(target); }
    public Result lastResult(UUID target) { return lastResults.get(target); }
    public Collection<Raid> activeRaids() { return List.copyOf(active.values()); }

    public boolean involves(UUID village) {
        return active.values().stream().anyMatch(r -> r.attacker().equals(village) || r.target().equals(village));
    }

    public Raid begin(UUID attacker, UUID target, long startGameTime, UUID initiator) {
        if (attacker.equals(target) || involves(attacker) || involves(target)
                || startGameTime <= 0 || wins(target) >= REQUIRED_WINS) {
            throw new IllegalStateException("Invalid or overlapping conquest raid");
        }
        Raid raid = new Raid(UUID.randomUUID(), attacker, target, startGameTime, initiator, false);
        active.put(target, raid);
        setDirty();
        return raid;
    }

    /** A repeated success callback still suppresses loot, but never awards another point. */
    public boolean recordVictory(Raid raid) {
        Raid current = active.get(raid.target());
        if (current == null || !current.id().equals(raid.id()) || current.victoryRecorded()) return false;
        wins.put(raid.target(), Math.min(REQUIRED_WINS, wins(raid.target()) + 1));
        active.put(raid.target(), current.won());
        setDirty();
        return true;
    }

    public void finish(Raid raid, Outcome otherwise) {
        Raid current = active.get(raid.target());
        if (current == null || !current.id().equals(raid.id())) return;
        lastResults.put(raid.target(), new Result(raid.id(), raid.attacker(),
                current.victoryRecorded() ? Outcome.WON : otherwise));
        active.remove(raid.target());
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("format", FORMAT);
        ListTag progress = new ListTag();
        wins.forEach((target, count) -> {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("target", target);
            entry.putInt("wins", count);
            progress.add(entry);
        });
        tag.put("progress", progress);
        ListTag raids = new ListTag();
        for (Raid raid : active.values()) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("id", raid.id());
            entry.putUUID("attacker", raid.attacker());
            entry.putUUID("target", raid.target());
            entry.putLong("startGameTime", raid.startGameTime());
            if (raid.initiator() != null) entry.putUUID("initiator", raid.initiator());
            entry.putBoolean("victoryRecorded", raid.victoryRecorded());
            raids.add(entry);
        }
        tag.put("active", raids);
        ListTag results = new ListTag();
        lastResults.forEach((target, result) -> {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("target", target);
            entry.putUUID("id", result.raidId());
            entry.putUUID("attacker", result.attacker());
            entry.putString("outcome", result.outcome().name());
            results.add(entry);
        });
        tag.put("lastResults", results);
        return tag;
    }

    public static ConquestSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        if (tag.getInt("format") != FORMAT) throw new IllegalStateException("Unsupported conquest data format");
        ConquestSavedData data = new ConquestSavedData();
        for (Tag value : tag.getList("progress", Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) value;
            int count = entry.getInt("wins");
            if (count < 0 || count > REQUIRED_WINS) throw new IllegalStateException("Invalid conquest progress");
            data.wins.put(entry.getUUID("target"), count);
        }
        for (Tag value : tag.getList("active", Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) value;
            Raid raid = new Raid(entry.getUUID("id"), entry.getUUID("attacker"), entry.getUUID("target"),
                    entry.getLong("startGameTime"), entry.hasUUID("initiator") ? entry.getUUID("initiator") : null,
                    entry.getBoolean("victoryRecorded"));
            if (raid.startGameTime() <= 0 || raid.attacker().equals(raid.target())
                    || data.involves(raid.attacker()) || data.involves(raid.target())
                    || (raid.victoryRecorded() && data.wins(raid.target()) == 0)) {
                throw new IllegalStateException("Invalid active conquest raid");
            }
            data.active.put(raid.target(), raid);
        }
        for (Tag value : tag.getList("lastResults", Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) value;
            data.lastResults.put(entry.getUUID("target"), new Result(entry.getUUID("id"),
                    entry.getUUID("attacker"), Outcome.valueOf(entry.getString("outcome"))));
        }
        return data;
    }
}
