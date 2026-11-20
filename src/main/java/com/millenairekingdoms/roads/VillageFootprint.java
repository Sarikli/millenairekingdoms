package com.millenairekingdoms.roads;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.millenaire.building.BuildingInstance;
import org.millenaire.village.Village;

/**
 * Flat 2D footprint of Millénaire villages: every building bounding box (inflated by a margin) as axis-aligned
 * rectangles. Built purely from saved village records, so it never touches chunks. Used to keep Royal Roads from
 * cutting through village interiors and to score gate/path endpoints.
 */
public final class VillageFootprint {
    /** Default margin (blocks) added around every building box. */
    public static final int DEFAULT_MARGIN = 3;

    private final List<int[]> boxes;

    private VillageFootprint(List<int[]> boxes) {
        this.boxes = boxes;
    }

    public static VillageFootprint empty() {
        return new VillageFootprint(List.of());
    }

    public static VillageFootprint of(Collection<Village> villages, int margin) {
        List<int[]> out = new ArrayList<>();
        if (villages != null) {
            for (Village v : villages) {
                if (v == null)
                    continue;
                for (BuildingInstance b : v.getBuildings()) {
                    int[] box = buildingBox(b);
                    if (box != null)
                        out.add(new int[] { box[0] - margin, box[1] - margin, box[2] + margin, box[3] + margin });
                }
            }
        }
        return new VillageFootprint(List.copyOf(out));
    }

    /** Returns {@code {minX, minZ, maxX, maxZ}} of a building in world coordinates, or {@code null}. */
    public static int[] buildingBox(BuildingInstance b) {
        if (b == null)
            return null;
        BlockPos origin = b.getOrigin();
        if (origin == null)
            return null;
        boolean hasBounds = b.getEffectiveWidth() > 0 && b.getEffectiveDepth() > 0;
        if (!hasBounds)
            return new int[] { origin.getX() - 1, origin.getZ() - 1, origin.getX() + 1, origin.getZ() + 1 };
        int minX = origin.getX() + b.getCachedMinX();
        int maxX = origin.getX() + b.getCachedMaxX();
        int minZ = origin.getZ() + b.getCachedMinZ();
        int maxZ = origin.getZ() + b.getCachedMaxZ();
        if (maxX < minX || maxZ < minZ)
            return new int[] { origin.getX() - 1, origin.getZ() - 1, origin.getX() + 1, origin.getZ() + 1 };
        return new int[] { minX, minZ, maxX, maxZ };
    }

    public boolean isEmpty() {
        return boxes.isEmpty();
    }

    public boolean contains(int x, int z) {
        for (int[] b : boxes) {
            if (x >= b[0] && x <= b[2] && z >= b[1] && z <= b[3])
                return true;
        }
        return false;
    }

    /** Counts how many sampled points (every 2 blocks) of the straight segment fall inside the footprint. */
    public int insideSamples(int x0, int z0, int x1, int z1, int maxLength) {
        double dx = x1 - x0;
        double dz = z1 - z0;
        double len = Math.hypot(dx, dz);
        if (len < 1e-6)
            return contains(x0, z0) ? 1 : 0;
        double limit = Math.min(len, Math.max(1, maxLength));
        int count = 0;
        for (double t = 0.0; t <= limit; t += 2.0) {
            int x = (int) Math.round(x0 + dx * t / len);
            int z = (int) Math.round(z0 + dz * t / len);
            if (contains(x, z))
                count++;
        }
        return count;
    }
}
