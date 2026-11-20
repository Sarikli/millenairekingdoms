package com.millenairekingdoms.gui;

import com.millenairekingdoms.autonomy.ExpeditionPricingService;
import com.millenairekingdoms.economy.TreasuryService;
import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.millenairekingdoms.politics.PoliticsService;
import com.millenairekingdoms.roads.RoadConfig;
import com.millenairekingdoms.roads.RoadSavedData;
import com.millenairekingdoms.roads.RoadSavedData.RoadSegment;
import com.millenairekingdoms.roads.RoadService;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** Builds server-validated Royal Roads view state synced to {@link com.millenairekingdoms.client.KingdomScreen}. */
public final class RoyalRoadView {
    private RoyalRoadView() {}

    public static CompoundTag build(ServerPlayer player, UUID kingdomId) {
        ServerLevel level = player.serverLevel();
        KingdomSavedData data = KingdomSavedData.get(level);
        RoadConfig.Settings cfg = RoadConfig.current();
        RoadSavedData roadData = RoadSavedData.get(level);
        CompoundTag out = new CompoundTag();
        out.putBoolean("enabled", cfg.enabled());

        var kingdom = data.kingdom(kingdomId);
        UUID ruled = data.ruledBy(player.getUUID());
        boolean isRuler = kingdom != null && kingdomId.equals(ruled);
        boolean canDecide = isRuler && data.mayDecide(kingdomId, player.getUUID())
                && PoliticsService.reputation(level, kingdom, player.getUUID()) >= PoliticsService.DECISION_REPUTATION;
        out.putBoolean("isRuler", isRuler);
        out.putBoolean("canDecide", canDecide);

        if (kingdom == null || kingdom.fallen() || !kingdom.active())
            return out;

        boolean storageLoaded = isRuler && TreasuryService.loaded(level, kingdom);
        long spendable = storageLoaded ? ExpeditionPricingService.spendableTreasury(level, data, kingdomId) : -1L;
        out.putBoolean("storageLoaded", storageLoaded);
        out.putLong("spendable", spendable);

        var connected = roadData.connectedToCapital(data, kingdomId);
        out.putInt("connectedCount", connected.size());

        RoadSegment active = roadData.activeSegment(kingdomId);
        if (active != null) {
            out.put("active", segmentTag(level, active));
        }

        ListTag completed = new ListTag();
        for (RoadSegment seg : roadData.segmentsForKingdom(kingdomId)) {
            if (seg.isCompleted())
                completed.add(segmentTag(level, seg));
        }
        out.put("completed", completed);

        ListTag pairs = new ListTag();
        for (RoadService.PairPreview preview : RoadService.previewPairs(level, data, kingdomId)) {
            CompoundTag pt = new CompoundTag();
            pt.putUUID("villageA", preview.villageA());
            pt.putString("nameA", preview.nameA());
            pt.putUUID("villageB", preview.villageB());
            pt.putString("nameB", preview.nameB());
            pt.putInt("distance", preview.distanceBlocks());
            pt.putLong("stipend", preview.stipendDeniers());
            pt.putInt("materials", preview.materialUnits());

            String reason = "";
            if (!cfg.enabled())
                reason = "millenairekingdoms.road.error.disabled";
            else if (!isRuler)
                reason = "millenairekingdoms.gui.roads.reason.RULER_ONLY";
            else if (!canDecide)
                reason = "millenairekingdoms.road.error.authority";
            else if (active != null)
                reason = "millenairekingdoms.road.error.active_exists";
            else if (preview.blocked())
                reason = preview.blockedReasonKey();
            else if (preview.stipendDeniers() > 0 && !storageLoaded)
                reason = "millenairekingdoms.road.error.treasury_unloaded";
            else if (preview.stipendDeniers() > 0 && spendable < preview.stipendDeniers())
                reason = "millenairekingdoms.road.error.insufficient_funds";

            pt.putBoolean("canOrder", reason.isEmpty());
            pt.putString("reason", reason);
            pairs.add(pt);
        }
        out.put("pairs", pairs);
        return out;
    }

    private static CompoundTag segmentTag(ServerLevel level, RoadSegment seg) {
        CompoundTag t = new CompoundTag();
        t.putUUID("id", seg.id());
        t.putString("nameA", villageName(level, seg.villageA()));
        t.putString("nameB", villageName(level, seg.villageB()));
        t.putInt("completed", seg.completedBlocks());
        t.putInt("total", seg.totalBlocks());
        t.putString("status", seg.status().name());
        t.putLong("stipendPaid", seg.stipendPaid());
        int remainingBlocks = Math.max(0, seg.totalBlocks() - seg.completedBlocks());
        t.putInt("neededMaterials",
                remainingBlocks == 0 ? 0 : RoadService.estimatedMaterials(remainingBlocks, RoadConfig.current()));
        t.putInt("availableSurplus",
                com.millenairekingdoms.roads.RoadMaterialStock.availableSurplus(level, seg.villageA(), seg.villageB()));
        return t;
    }

    private static String villageName(ServerLevel level, UUID id) {
        var v = PoliticsService.village(level, id);
        return v == null ? id.toString().substring(0, 8) : PoliticsService.name(v);
    }
}
