package com.millenairekingdoms.atlas.geography;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.function.IntBinaryOperator;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Ocean surveying is disabled. This inert compatibility facade owns no workers, sessions or world observations. */
public final class GeographyService {
    public static final boolean ENABLED=false;
    // Retain API constants for historical fixtures; no runtime entry point consumes these budgets.
    public static final int RADIUS=48,COLUMNS_PER_PLAYER=64,COLUMNS_PER_INTERVAL=256,INTERVAL=20,MAX_SESSIONS=16,MAX_PENDING_WRITES=4;
    public static final int COAST_PROBE_DISTANCE=32,COASTAL_CANDIDATE=4;
    public static final long SOFT_INTERVAL_NANOS=10_000_000L;
    public static final class Loading extends RuntimeException {private Loading(){super("Ocean geography is disabled");}}
    private GeographyService(){}
    /** Path calculation only, retained so existing files can be located without opening or changing them. */
    public static Path directory(ServerLevel level,UUID player){return DimensionType.getStorageFolder(level.dimension(),level.getServer().getWorldPath(LevelResource.ROOT)).resolve("data/millenairekingdoms/geography").resolve(player.toString());}
    public static void onTick(ServerTickEvent.Post event){}
    public static int observePlayers(MinecraftServer server,List<ServerPlayer> players){return 0;}
    public static int capture(ServerPlayer player){return 0;}
    public static int captureAsync(ServerPlayer player){return 0;}
    public static int classifyLoadedColumn(ServerLevel level,int x,int z){return GeographyTile.UNKNOWN;}
    public static int classifyCoastal(IntBinaryOperator source,int x,int z){return GeographyTile.UNKNOWN;}
    public static GeographyStore store(ServerPlayer player){throw new UnsupportedOperationException("Ocean geography is disabled; existing cache files are preserved");}
    public static boolean ready(ServerPlayer player){return false;}
    public static int pendingWrites(){return 0;}
    public static int activeSessions(MinecraftServer server){return 0;}
    public static String lastWriteThread(ServerPlayer player){return "";}
    public static void clear(MinecraftServer server){}
}
