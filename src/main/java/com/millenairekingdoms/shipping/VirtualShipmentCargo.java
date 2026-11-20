package com.millenairekingdoms.shipping;

import com.millenairekingdoms.shipping.RoyalShipmentStore.Operation;
import com.millenairekingdoms.shipping.RoyalShipmentStore.Outcome;
import com.millenairekingdoms.shipping.RoyalShipmentStore.Role;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * Journal form of a royal cargo operation on an unloaded village's virtual Town Hall buffer. It is recorded in the
 * shipment journal like a physical one (prepared, then acknowledged), but its image carries no chest slots: the
 * exactly-once proof is the virtual economy's own applied-operation ledger.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public final class VirtualShipmentCargo {
    private static final String KEY = "virtualVillage";

    private VirtualShipmentCargo() {}

    public static UUID container(UUID village) {
        return UUID.nameUUIDFromBytes(("mk-virtual-cargo|" + village).getBytes(StandardCharsets.UTF_8));
    }
    public static Operation prepared(UUID id, Role role, UUID village, BlockPos center, int quantity) {
        var empty = new ListTag();
        for (int slot = 0; slot < 27; slot++)
            empty.add(new CompoundTag());
        var state = new CompoundTag();
        state.putUUID(KEY, village);
        var image = new CompoundTag();
        image.putInt("format", 1);
        image.put("state", state);
        image.put("before", empty.copy());
        image.put("after", empty.copy());
        return new Operation(id, role, container(village), center, quantity, image, Outcome.PREPARED);
    }
    public static boolean isVirtual(Operation operation) {
        var state = operation.image().getCompound("state");
        return state.hasUUID(KEY) && operation.container().equals(container(state.getUUID(KEY)));
    }
    public static UUID village(Operation operation) {
        return operation.image().getCompound("state").getUUID(KEY);
    }
    static boolean hasState(CompoundTag image) {
        return image.contains("state", Tag.TAG_COMPOUND) && image.getCompound("state").hasUUID(KEY);
    }
}
