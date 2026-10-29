package com.millenairekingdoms.autonomy;

import com.millenairekingdoms.history.HistoryFiles;
import com.millenairekingdoms.history.HistoryHooks;
import java.nio.file.*;
import java.util.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

/** Two bounded economic lanes per kingdom; neither is money, cargo or a completed native order. */
public final class RoyalStrategyStore {
    public enum Lane { DEVELOPMENT, LOGISTICS }
    public record Portfolio(Plan development, Plan logistics) {
        public Portfolio {
            if (development != null && development.logistics() || logistics != null && !logistics.logistics())
                throw new IllegalArgumentException("Economic plan is stored in the wrong lane");
            if (development != null && logistics != null && (!development.kingdom().equals(logistics.kingdom())
                    || !development.dimension().equals(logistics.dimension()) || development.id() != null && development.id().equals(logistics.id())))
                throw new IllegalArgumentException("Economic lanes have conflicting identities");
        }
        public Plan get(Lane lane) { return lane == Lane.DEVELOPMENT ? development : logistics; }
        public Portfolio with(Lane lane, Plan plan) {
            return lane == Lane.DEVELOPMENT ? new Portfolio(plan, logistics) : new Portfolio(development, plan);
        }
        public Plan primary() {
            if (logistics != null && logistics.status() != Status.INVALIDATED && logistics.status() != Status.IDLE
                    && (development == null || development.status() != Status.LINKED && development.status() != Status.ISSUING)) return logistics;
            return development == null ? logistics : development;
        }
    }
    public enum Status { IDLE, CONDITIONAL, ISSUING, LINKED, INVALIDATED }
    public record Plan(UUID kingdom,UUID capital,ResourceLocation capitalType,String dimension,UUID id,UUID target,
                       ResourceLocation selection,boolean logistics,long evaluatedDay,long nextDay,long metadataObservedTick,
                       String fingerprint,Status status,String reason){
        public Plan{
            Objects.requireNonNull(kingdom);Objects.requireNonNull(capital);Objects.requireNonNull(capitalType);Objects.requireNonNull(status);
            if(ResourceLocation.tryParse(dimension)==null || evaluatedDay<0 || nextDay<=evaluatedDay || metadataObservedTick<0 || reason==null || reason.length()>512)throw new IllegalArgumentException("Invalid strategic evaluation");
            UUID.fromString(fingerprint);
            if((id==null)!=(target==null) || target!=null && !logistics && selection==null || target==null && (selection!=null || logistics) || (status==Status.CONDITIONAL || status==Status.ISSUING || status==Status.LINKED) && id==null)throw new IllegalArgumentException("Invalid conditional investment identity");
        }
        public Plan status(Status next,String why){return new Plan(kingdom,capital,capitalType,dimension,id,target,selection,logistics,evaluatedDay,nextDay,metadataObservedTick,fingerprint,next,bound(why));}
    }
    private final Path directory;
    private final String dimension;
    public RoyalStrategyStore(ServerLevel level){this(HistoryHooks.directory(level).resolve("royal-strategy"),level.dimension().location().toString());}
    public RoyalStrategyStore(Path directory,String dimension){if(ResourceLocation.tryParse(dimension)==null)throw new IllegalArgumentException("Invalid strategy dimension");this.directory=directory;this.dimension=dimension;}
    public Path file(UUID kingdom){return directory.resolve(kingdom+".dat");}
    public Plan read(UUID kingdom) { return portfolio(kingdom).primary(); }
    public Plan read(UUID kingdom, Lane lane) { return portfolio(kingdom).get(lane); }

    public Portfolio portfolio(UUID kingdom) {
        var path = file(kingdom);
        if (!Files.exists(path)) return new Portfolio(null, null);
        var tag = HistoryFiles.read(path);
        if (!tag.contains("format", Tag.TAG_INT)) throw new IllegalArgumentException("Missing economic strategy version");
        if (tag.getInt("format") == 1) {
            var legacy = decode(tag, kingdom);
            return legacy.logistics() ? new Portfolio(null, legacy) : new Portfolio(legacy, null);
        }
        if (tag.getInt("format") != 2) throw new IllegalArgumentException("Unsupported strategic data; original preserved");
        var fields = new HashSet<>(Set.of("format", "kingdom", "dimension"));
        if (tag.contains("development")) fields.add("development");
        if (tag.contains("logistics")) fields.add("logistics");
        HistoryFiles.keys(tag, fields);
        if (!uuid(tag, "kingdom").equals(kingdom) || !tag.contains("dimension", Tag.TAG_STRING)
                || !dimension.equals(tag.getString("dimension"))) throw new IllegalArgumentException("Economic strategy identity changed");
        for (var key : List.of("development", "logistics"))
            if (tag.contains(key) && !tag.contains(key, Tag.TAG_COMPOUND)) throw new IllegalArgumentException("Invalid economic lane");
        return new Portfolio(tag.contains("development") ? decode(tag.getCompound("development"), kingdom) : null,
                tag.contains("logistics") ? decode(tag.getCompound("logistics"), kingdom) : null);
    }

    /** Compatibility entry point: moves a legacy primary plan without dropping another lane. */
    public void write(Plan expected, Plan next) {
        var portfolio = portfolio(next.kingdom());
        if (!Objects.equals(portfolio.primary(), expected)) throw new IllegalStateException("Strategic intention changed; re-evaluate");
        var nextLane=next.logistics() ? Lane.LOGISTICS : Lane.DEVELOPMENT;
        if(expected!=null && expected.logistics()!=next.logistics() && portfolio.get(nextLane)!=null)
            throw new IllegalStateException("Legacy move would overwrite an independent economic lane");
        if (expected != null) portfolio = portfolio.with(expected.logistics() ? Lane.LOGISTICS : Lane.DEVELOPMENT, null);
        writePortfolio(next.kingdom(), portfolio.with(next.logistics() ? Lane.LOGISTICS : Lane.DEVELOPMENT, next));
    }

    public void write(Lane lane, Plan expected, Plan next) {
        var portfolio = portfolio(next.kingdom());
        if (!Objects.equals(portfolio.get(lane), expected)) throw new IllegalStateException("Economic lane changed; re-evaluate");
        writePortfolio(next.kingdom(), portfolio.with(lane, next));
    }

    private void writePortfolio(UUID kingdom, Portfolio portfolio) {
        var tag = new CompoundTag();
        tag.putInt("format", 2); tag.putUUID("kingdom", kingdom); tag.putString("dimension", dimension);
        for (var lane : Lane.values()) {
            var plan = portfolio.get(lane);
            if (plan == null) continue;
            if (!plan.kingdom().equals(kingdom) || !plan.dimension().equals(dimension))
                throw new IllegalArgumentException("Economic lane identity mismatch");
            tag.put(lane == Lane.DEVELOPMENT ? "development" : "logistics", encode(plan));
        }
        HistoryFiles.write(file(kingdom), tag);
    }

    private Plan decode(CompoundTag t, UUID kingdom) {
        var required = Set.of("format","kingdom","capital","capitalType","dimension","logistics","evaluatedDay","nextDay","metadataObservedTick","fingerprint","status","reason");
        var allowed = new HashSet<>(required); allowed.addAll(Set.of("id","target","selection"));
        if (!allowed.containsAll(t.getAllKeys()) || !t.getAllKeys().containsAll(required) || !t.contains("format",Tag.TAG_INT) || t.getInt("format") != 1)
            throw new IllegalArgumentException("Unsupported strategic data; original preserved");
        for (var field : List.of("capitalType","dimension","fingerprint","status","reason"))
            if (!t.contains(field,Tag.TAG_STRING)) throw new IllegalArgumentException("Invalid strategic text field");
        for (var field : List.of("evaluatedDay","nextDay","metadataObservedTick"))
            if (!t.contains(field,Tag.TAG_LONG)) throw new IllegalArgumentException("Invalid strategic clock field");
        if (!t.contains("logistics",Tag.TAG_BYTE) || t.contains("selection") && !t.contains("selection",Tag.TAG_STRING))
            throw new IllegalArgumentException("Invalid strategic selection");
        var plan = new Plan(uuid(t,"kingdom"),uuid(t,"capital"),ResourceLocation.parse(t.getString("capitalType")),t.getString("dimension"),optional(t,"id"),optional(t,"target"),t.contains("selection") ? ResourceLocation.parse(t.getString("selection")) : null,t.getBoolean("logistics"),t.getLong("evaluatedDay"),t.getLong("nextDay"),t.getLong("metadataObservedTick"),t.getString("fingerprint"),Status.valueOf(t.getString("status")),t.getString("reason"));
        if (!plan.kingdom().equals(kingdom) || !plan.dimension().equals(dimension)) throw new IllegalArgumentException("Strategic identity/dimension mismatch");
        return plan;
    }

    private CompoundTag encode(Plan next) {
        var t = new CompoundTag();t.putInt("format",1);t.putUUID("kingdom",next.kingdom());t.putUUID("capital",next.capital());t.putString("capitalType",next.capitalType().toString());t.putString("dimension",dimension);
        if(next.id()!=null)t.putUUID("id",next.id());if(next.target()!=null)t.putUUID("target",next.target());if(next.selection()!=null)t.putString("selection",next.selection().toString());
        t.putBoolean("logistics",next.logistics());t.putLong("evaluatedDay",next.evaluatedDay());t.putLong("nextDay",next.nextDay());t.putLong("metadataObservedTick",next.metadataObservedTick());t.putString("fingerprint",next.fingerprint());t.putString("status",next.status().name());t.putString("reason",next.reason());return t;
    }
    static String bound(String text){return text.substring(0,Math.min(512,text.length()));}
    private static UUID uuid(CompoundTag tag,String key){if(!tag.hasUUID(key))throw new IllegalArgumentException("Invalid strategic UUID "+key);return tag.getUUID(key);}
    private static UUID optional(CompoundTag tag,String key){return tag.contains(key)?uuid(tag,key):null;}
}
