package com.millenairekingdoms.network;

import com.millenairekingdoms.MillenaireKingdoms;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Server to client: one read-only snapshot of what the kingdom screen shows (built by KingdomScreens for this player).
 * It grants nothing; every button sends a KingdomActionPayload that the server checks again. */
public record KingdomViewPayload(CompoundTag view) implements CustomPacketPayload {
    public static final Type<KingdomViewPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MillenaireKingdoms.MOD_ID, "view"));
    public static final StreamCodec<RegistryFriendlyByteBuf, KingdomViewPayload> CODEC =
            StreamCodec.composite(ByteBufCodecs.COMPOUND_TAG, KingdomViewPayload::view, KingdomViewPayload::new);
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
