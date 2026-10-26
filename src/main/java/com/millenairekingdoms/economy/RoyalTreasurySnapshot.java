package com.millenairekingdoms.economy;

import com.millenairekingdoms.kingdom.KingdomSavedData;
import com.millenairekingdoms.politics.PoliticsService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import org.millenaire.building.BuildingInventory;

/** Authorized physical treasury quote. Public access is read-only; the payment coordinator owns mutations. */
public final class RoyalTreasurySnapshot {
    private record Chest(BlockPos position, ChestBlockEntity entity, BlockState state) {}
    private final ServerLevel level;
    private final ServerPlayer player;
    private final UUID kingdom, capital;
    private final UUID recoveryCapital;
    private final boolean maintenance;
    private final BuildingInventory storage;
    private final List<BlockPos> declaredChests;
    private final List<Chest> chests;
    private final List<ItemStack> slots;

    public static RoyalTreasurySnapshot forNpc(ServerLevel level, UUID kingdom) {
        return new RoyalTreasurySnapshot(level,null,kingdom);
    }
    /** No spending agency: only the package-private exactly conserving mutation accepts this snapshot. */
    static RoyalTreasurySnapshot forMaintenance(ServerLevel level,UUID kingdom) {return new RoyalTreasurySnapshot(level,null,kingdom,null,true);}
    public static RoyalTreasurySnapshot forPlayer(ServerPlayer player) {
        thread(player.serverLevel());
        return new RoyalTreasurySnapshot(player.serverLevel(),player,PoliticsService.ruledKingdom(player).id());
    }

    private RoyalTreasurySnapshot(ServerLevel level, ServerPlayer player, UUID kingdom) {
        this(level,player,kingdom,null);
    }
    // Existing-receipt settlement only. Never used by a new payment/debit endpoint.
    static RoyalTreasurySnapshot forRecovery(ServerLevel level,UUID kingdom,UUID capital) {
        return new RoyalTreasurySnapshot(level,null,kingdom,java.util.Objects.requireNonNull(capital));
    }
    private RoyalTreasurySnapshot(ServerLevel level, ServerPlayer player, UUID kingdom,UUID recoveryCapital) {
        this(level,player,kingdom,recoveryCapital,false);
    }
    private RoyalTreasurySnapshot(ServerLevel level, ServerPlayer player, UUID kingdom,UUID recoveryCapital,boolean maintenance) {
        thread(level);
        this.maintenance=maintenance;
        this.level=level; this.player=player; this.kingdom=kingdom; this.recoveryCapital=recoveryCapital;
        var current=authority(); this.capital=current.capital();
        this.storage=EconomyService.storage(level,current);
        if(storage==null || storage.getChestPositions().isEmpty()) throw new IllegalStateException("Capital town hall storage is unavailable");
        this.declaredChests=storage.getChestPositions().stream().map(BlockPos::immutable).toList();
        var resolved=new LinkedHashMap<BlockPos,Chest>();
        for(BlockPos position:declaredChests) {
            Chest chest=resolve(position); resolved.putIfAbsent(position,chest);
            if(chest.state.getValue(ChestBlock.TYPE)!=ChestType.SINGLE) {
                BlockPos neighbor=position.relative(ChestBlock.getConnectedDirection(chest.state));
                Chest other=resolve(neighbor);
                if(other.state.getBlock()!=chest.state.getBlock() || other.state.getValue(ChestBlock.FACING)!=chest.state.getValue(ChestBlock.FACING)
                        || other.state.getValue(ChestBlock.TYPE)==ChestType.SINGLE
                        || !neighbor.relative(ChestBlock.getConnectedDirection(other.state)).equals(position)) {
                    throw new IllegalStateException("Town hall double chest connection changed");
                }
                resolved.putIfAbsent(neighbor,other);
            }
        }
        this.chests=List.copyOf(resolved.values());
        this.slots=readSlots();
    }

    public UUID kingdom() { return kingdom; }
    public UUID capital() { return capital; }
    public List<BlockPos> positions() { return chests.stream().map(Chest::position).toList(); }
    public List<ItemStack> slots() { return slots.stream().map(ItemStack::copy).toList(); }
    public long spendableValue() { return RoyalCoinPlan.spendableValue(slots); }
    public RoyalCoinPlan.Plan planDebit(long amount) { requireUnchanged(); return RoyalCoinPlan.debit(slots,amount); }

    // Package-private: never expose an unjournaled mutation to GUI/network callers.
    void apply(List<ItemStack> after) {
        if(maintenance)throw new IllegalStateException("Maintenance snapshot cannot apply a payment");
        requireUnchanged();
        if(after.size()!=slots.size()) throw new IllegalArgumentException("Invalid treasury slot image");
        int index=0;
        for(Chest chest:chests) {
            for(int slot=0;slot<chest.entity.getContainerSize();slot++) chest.entity.setItem(slot,after.get(index++).copy());
            chest.entity.setChanged();
        }
        storage.invalidateCache();
    }
    /** A full quote is checked, but only changed slots in one physical BE may receive a mutation. */
    void applyConservingSingle(List<ItemStack> after) {
        if(!maintenance)throw new IllegalStateException("Conserving maintenance snapshot required");requireUnchanged();if(after.size()!=slots.size())throw new IllegalArgumentException("Invalid conserving slot image");int changed=0;
        for(int start=0;start<after.size();start+=27){var before=slots.subList(start,start+27);var replacement=after.subList(start,start+27);if(RoyalCoinPlan.spendableValue(before)!=RoyalCoinPlan.spendableValue(replacement))throw new IllegalArgumentException("Consolidation cannot move value between physical chests");boolean different=false;
            for(int n=0;n<27;n++){var a=before.get(n);var b=replacement.get(n);boolean same=ItemStack.matches(a,b);if(!same && (!a.isEmpty() && RoyalCoinPlan.spendableValue(List.of(a))==0 || !b.isEmpty() && RoyalCoinPlan.spendableValue(List.of(b))==0))throw new IllegalArgumentException("Protected/noncurrency inventory cannot change during consolidation");different|=!same;}if(different)changed++;
        }
        if(changed>1)throw new IllegalArgumentException("Only one independent physical chest may consolidate per operation");int offset=0;
        for(var chest:chests){boolean touched=false;for(int n=0;n<27;n++){var replacement=after.get(offset++);if(!ItemStack.matches(chest.entity.getItem(n),replacement)){chest.entity.setItem(n,replacement.copy());touched=true;}}if(touched)chest.entity.setChanged();}if(changed>0)storage.invalidateCache();
    }

    public void requireUnchanged() {
        thread(level);
        var current=authority();
        if(!capital.equals(current.capital()) || EconomyService.storage(level,current)!=storage
                || !declaredChests.equals(storage.getChestPositions())) throw new IllegalStateException("Capital storage changed; obtain a new quote");
        for(Chest chest:chests) {
            if(!level.isLoaded(chest.position) || level.getBlockEntity(chest.position)!=chest.entity
                    || !level.getBlockState(chest.position).equals(chest.state)) {
                throw new IllegalStateException("Treasury chest unloaded, replaced or reconnected; obtain a new quote");
            }
        }
        var actual=readSlots();
        if(actual.size()!=slots.size()) throw new IllegalStateException("Treasury inventory size changed");
        for(int i=0;i<actual.size();i++) if(!ItemStack.matches(actual.get(i),slots.get(i))) {
            throw new IllegalStateException("Treasury inventory changed; obtain a new quote");
        }
    }

    private KingdomSavedData.Kingdom authority() {
        if(player!=null && player.serverLevel()!=level) throw new IllegalArgumentException("Ruler moved to another dimension; obtain a new quote");
        var data=KingdomSavedData.get(level); var current=data.kingdom(kingdom);
        if(maintenance){if(current==null || !current.active() || current.fallen() || !kingdom.equals(data.owner(current.capital())))throw new IllegalStateException("Current active capital identity is required for conserving maintenance");return current;}
        if(recoveryCapital!=null) {
            if(current==null || !current.active() || current.fallen() || !recoveryCapital.equals(current.capital()) || !kingdom.equals(data.owner(recoveryCapital))) {
                throw new IllegalStateException("Original capital no longer belongs to the active kingdom; preserve retired receipt for explicit recovery");
            }
            return current;
        }
        if(current==null || !data.mayDecide(kingdom,player==null ? null : player.getUUID())) {
            throw new IllegalArgumentException("This ruler cannot spend for the active kingdom");
        }
        if(player!=null && !PoliticsService.ruledKingdom(player).id().equals(kingdom)) {
            throw new IllegalArgumentException("This player no longer rules the quoted kingdom");
        }
        return current;
    }

    private Chest resolve(BlockPos position) {
        // Check before getBlockState/getBlockEntity: never turn a quote into a distant chunk-load request.
        if(!level.isLoaded(position)) throw new IllegalStateException("Treasury chunk is unloaded; postpone the operation");
        var state=level.getBlockState(position); var entity=level.getBlockEntity(position);
        if(!(state.getBlock() instanceof ChestBlock) || !(entity instanceof ChestBlockEntity chest)) {
            throw new IllegalStateException("Town hall chest is missing or unsupported");
        }
        return new Chest(position.immutable(),chest,state);
    }

    private List<ItemStack> readSlots() {
        var result=new ArrayList<ItemStack>();
        for(Chest chest:chests) for(int slot=0;slot<chest.entity.getContainerSize();slot++) result.add(chest.entity.getItem(slot).copy());
        return result;
    }
    private static void thread(ServerLevel level) {
        if(!level.getServer().isSameThread()) throw new IllegalStateException("Treasury access must run on the server thread");
    }
}
