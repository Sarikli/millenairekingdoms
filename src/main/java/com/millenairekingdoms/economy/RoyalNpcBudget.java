package com.millenairekingdoms.economy;

import com.millenairekingdoms.autonomy.RoyalNpcConfig;
import com.millenairekingdoms.construction.RoyalConstructionStore;
import com.millenairekingdoms.personality.KingdomPersonality;
import com.millenairekingdoms.shipping.RoyalShipmentService;
import com.millenairekingdoms.shipping.RoyalShipmentStore;
import java.nio.file.Files;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;

/** First-fee guard for persisted policy-owned domain IDs, including delayed previously-unpaid requests. Existing
 * PREPARED/native-proof reconciliation, paid acknowledgements/refunds and player requests do not create new spending. */
final class RoyalNpcBudget {
    private RoyalNpcBudget(){}
    static void requireNewSpend(ServerLevel level,RoyalTreasurySnapshot treasury,UUID id,RoyalPaymentService.Purpose purpose,UUID reference,long amount){
        var directory=RoyalNpcDecisionStore.directory(level);if(!Files.exists(directory.resolve("decisions.dat")))return;
        var decision=new RoyalNpcDecisionStore(directory).load(id);if(decision==null)return;
        var limits=RoyalNpcConfig.budgets();var profile=KingdomPersonality.forKingdom(level,treasury.kingdom());var budget=decision.budget();var choice=decision.choice();
        if(!limits.enabled() || !decision.dimension().equals(level.dimension().location().toString()) || !decision.kingdom().equals(treasury.kingdom())
                || !budget.capital().equals(treasury.capital()) || !profile.specialized() || !java.util.Objects.equals(budget.capitalType(),profile.villageType())
                || !id.equals(reference) || choice.fee()!=amount)throw new IllegalArgumentException("Original NPC policy capital/type/agency/fee authorization is no longer valid");
        if(purpose==RoyalPaymentService.Purpose.CONSTRUCTION && choice.kind()==RoyalNpcDecisionStore.Kind.CONSTRUCTION){
            var path=level.getServer().getWorldPath(LevelResource.ROOT).resolve("data/millenairekingdoms/royal-construction");
            var entry=Files.exists(path.resolve("orders.dat")) ? new RoyalConstructionStore(path).load(id) : null;var intent=entry==null ? null : entry.intent();
            if(intent==null || intent.actor()!=null || !intent.dimension().equals(decision.dimension()) || !intent.kingdom().equals(decision.kingdom()) || !intent.capital().equals(budget.capital())
                    || !intent.village().equals(choice.source()) || !intent.planSet().equals(choice.selection()) || intent.fee()!=amount || entry.stage()!=RoyalConstructionStore.Stage.REQUESTED)
                throw new IllegalArgumentException("NPC first fee differs from its original bound construction attempt");
        }else if(purpose==RoyalPaymentService.Purpose.SHIPMENT_FEE && choice.kind()==RoyalNpcDecisionStore.Kind.SHIPMENT){
            var path=RoyalShipmentService.directory(level);var entry=Files.exists(path.resolve("shipments.dat")) ? new RoyalShipmentStore(path).load(id) : null;var intent=entry==null ? null : entry.intent();
            if(intent==null || intent.actor()!=null || !intent.dimension().equals(decision.dimension()) || !intent.kingdom().equals(decision.kingdom()) || !intent.capital().equals(budget.capital())
                    || !intent.source().village().equals(choice.source()) || !intent.destination().village().equals(choice.target()) || !intent.product().equals(choice.selection()) || intent.quantity()!=choice.quantity()
                    || intent.fee()!=amount || intent.travelTicks()!=choice.travelTicks() || entry.stage()!=RoyalShipmentStore.Stage.PREPARING || entry.cancellationRequested())
                throw new IllegalArgumentException("NPC first fee differs from its original bound shipment attempt");
        }else throw new IllegalArgumentException("NPC first fee purpose differs from its original policy attempt");
        treasury.requireUnchanged();long funds=treasury.spendableValue(),reserve=Math.max(limits.reserve(),budget.reserve());int share=Math.min(limits.sharePermille(),budget.sharePermille());
        if(funds<amount || funds-amount<reserve || amount>maximumFee(funds,share))throw new IllegalStateException("NPC first fee waits for actual treasury reserve/share limits: funds="+funds+", fee="+amount+", reserve="+reserve+", sharePermille="+share);
    }
    /** floor(funds*share/1000) without overflowing a long. */
    static long maximumFee(long funds,int share){
        if(funds<0 || share<1 || share>1000)throw new IllegalArgumentException("Invalid NPC fee share inputs");
        return funds/1000*share+(funds%1000)*share/1000;
    }
}
