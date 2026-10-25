package com.millenairekingdoms.network;

import com.millenairekingdoms.MillenaireKingdoms;
import com.millenairekingdoms.history.HistoryEvent;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Read-only requests; dimension, identity, visibility and economic rights come from the connection. */
public record ChronicleRequestPayload(UUID session,long serial,String mode,Optional<UUID> kingdom,String kind,
        long fromDay,long toDay,Optional<UUID> cursor,String search,Optional<UUID> event,Optional<UUID> village) implements CustomPacketPayload {
    public static final Type<ChronicleRequestPayload> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(MillenaireKingdoms.MOD_ID,"chronicle_request"));
    public ChronicleRequestPayload {
        if(session==null || serial<0 || !Set.of("CHRONICLE","REALMS","ATLAS","INFO","GEOGRAPHY").contains(mode) || kingdom==null || cursor==null || event==null || village==null
                || kind==null || kind.length()>32 || fromDay<0 || toDay<fromDay || search==null || search.length()>64 || search.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Invalid chronicle request");
        if(!kind.isEmpty())HistoryEvent.Kind.valueOf(kind);
        if(mode.equals("GEOGRAPHY") && (kingdom.isPresent() || !kind.isEmpty() || cursor.isPresent() || !search.isEmpty() || event.isPresent() || village.isPresent()))throw new IllegalArgumentException("Geography accepts revision windows only; identity/dimension/observations come from the connection");
    }
    public ChronicleRequestPayload(UUID session,long serial,String mode,Optional<UUID> kingdom,String kind,long fromDay,long toDay,Optional<UUID> cursor,String search,Optional<UUID> event){this(session,serial,mode,kingdom,kind,fromDay,toDay,cursor,search,event,Optional.empty());}
    /** GEOGRAPHY reuses the bounded nonnegative day-window wire fields as observation revision cursors. */
    public static ChronicleRequestPayload geography(UUID session,long serial,long after,long through){return new ChronicleRequestPayload(session,serial,"GEOGRAPHY",Optional.empty(),"",after,through,Optional.empty(),"",Optional.empty(),Optional.empty());}
    public static final StreamCodec<RegistryFriendlyByteBuf,ChronicleRequestPayload> CODEC=StreamCodec.of((b,p)->{
        b.writeUUID(p.session);b.writeVarLong(p.serial);b.writeUtf(p.mode,16);write(b,p.kingdom);b.writeUtf(p.kind,32);b.writeVarLong(p.fromDay);b.writeVarLong(p.toDay);write(b,p.cursor);b.writeUtf(p.search,64);write(b,p.event);write(b,p.village);
    },b->new ChronicleRequestPayload(b.readUUID(),b.readVarLong(),b.readUtf(16),read(b),b.readUtf(32),b.readVarLong(),b.readVarLong(),read(b),b.readUtf(64),read(b),read(b)));
    private static void write(RegistryFriendlyByteBuf b,Optional<UUID> id){b.writeBoolean(id.isPresent());id.ifPresent(b::writeUUID);}
    private static Optional<UUID> read(RegistryFriendlyByteBuf b){return b.readBoolean()?Optional.of(b.readUUID()):Optional.empty();}
    public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
