package com.millenairekingdoms.network;

import com.millenairekingdoms.MillenaireKingdoms;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Separately bounded response; unrelated Kingdoms management screens retain their existing snapshot. */
public record ChronicleViewPayload(CompoundTag view) implements CustomPacketPayload {
    public static final int MAX_BYTES=524288;
    public static final Type<ChronicleViewPayload> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(MillenaireKingdoms.MOD_ID,"chronicle_view"));
    public ChronicleViewPayload {if(view==null || view.sizeInBytes()>MAX_BYTES || !view.hasUUID("session") || !view.contains("serial",4))throw new IllegalArgumentException("Invalid chronicle response");view=view.copy();}
    @Override public CompoundTag view(){return view.copy();}
    public static final StreamCodec<RegistryFriendlyByteBuf,ChronicleViewPayload> CODEC=StreamCodec.of((b,p)->b.writeNbt(p.view),b->{var tag=b.readNbt(NbtAccounter.create(MAX_BYTES));if(!(tag instanceof CompoundTag compound))throw new IllegalArgumentException("Chronicle response must be a compound");return new ChronicleViewPayload(compound);});
    public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
