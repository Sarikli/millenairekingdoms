package com.millenairekingdoms.shipping;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.millenaire.Millenaire;
import org.millenaire.building.BuildingInventory;

/** Narrow read-only 9.0.3 inventory boundary; never calls the native loadChunks method. */
final class NativeStockBridge {
    record Positions(List<BlockPos> chests,List<BlockPos> furnaces,List<BlockPos> firepits) {}
    private record Fields(Field furnaces,Field firepits) {}
    private static final class Pinned {static final Fields FIELDS=resolve();}
    private NativeStockBridge() {}
    static Positions positions(BuildingInventory inventory) {
        if(inventory.getClass()!=BuildingInventory.class)throw new IllegalStateException("Unsupported village storage implementation; preserve stock");
        try {
            return new Positions(inventory.getChestPositions().stream().map(BlockPos::immutable).toList(),
                    list(Pinned.FIELDS.furnaces.get(inventory)),list(Pinned.FIELDS.firepits.get(inventory)));
        } catch(IllegalAccessException e){throw new IllegalStateException("Cannot inspect pinned native stock positions",e);}
    }
    private static List<BlockPos> list(Object value) {
        if(!(value instanceof List<?> positions) || positions.size()>256)throw new IllegalStateException("Unsupported native auxiliary stock positions");
        var result=new ArrayList<BlockPos>();for(Object raw:positions){if(!(raw instanceof BlockPos p))throw new IllegalStateException("Invalid native stock position");result.add(p.immutable());}return List.copyOf(result);
    }
    private static Fields resolve() {
        if(!"9.0.3".equals(Millenaire.getModVersion()))throw new IllegalStateException("Royal stock adapter requires the audited original Millenaire9.0.3");
        try {
            var furnaces=BuildingInventory.class.getDeclaredField("furnacePositions");var firepits=BuildingInventory.class.getDeclaredField("firepitPositions");
            furnaces.setAccessible(true);firepits.setAccessible(true);return new Fields(furnaces,firepits);
        } catch(ReflectiveOperationException e){throw new IllegalStateException("Pinned native stock fields are unavailable; no cargo can be offered",e);}
    }
}
