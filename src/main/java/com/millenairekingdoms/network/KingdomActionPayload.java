package com.millenairekingdoms.network;

import com.millenairekingdoms.MillenaireKingdoms;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client to server: a request from the kingdom screen. It names only what the player picked (a kingdom, a village, an
 * amount, a name); who acts and with which rights is always decided on the server from the connection's ServerPlayer. */
public record KingdomActionPayload(String action, Optional<UUID> first, Optional<UUID> second, long amount, String text) implements CustomPacketPayload {
    public static final Type<KingdomActionPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MillenaireKingdoms.MOD_ID, "action"));
    public static final StreamCodec<RegistryFriendlyByteBuf, KingdomActionPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(32), KingdomActionPayload::action,
            ByteBufCodecs.optional(UUIDUtil.STREAM_CODEC), KingdomActionPayload::first,
            ByteBufCodecs.optional(UUIDUtil.STREAM_CODEC), KingdomActionPayload::second,
            ByteBufCodecs.VAR_LONG, KingdomActionPayload::amount,
            ByteBufCodecs.stringUtf8(128), KingdomActionPayload::text,
            KingdomActionPayload::new);
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static KingdomActionPayload of(String action) { return new KingdomActionPayload(action, Optional.empty(), Optional.empty(), 0, ""); }
    public static KingdomActionPayload of(String action, UUID first) { return new KingdomActionPayload(action, Optional.ofNullable(first), Optional.empty(), 0, ""); }
    public static KingdomActionPayload of(String action, UUID first, UUID second) {
        return new KingdomActionPayload(action, Optional.ofNullable(first), Optional.ofNullable(second), 0, "");
    }
}
