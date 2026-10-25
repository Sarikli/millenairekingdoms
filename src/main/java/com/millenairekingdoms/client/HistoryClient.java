package com.millenairekingdoms.client;

import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;

/** Does not open unsolicited screens when a late read-only response arrives after Back/Esc. */
public final class HistoryClient {
    private HistoryClient(){}
    public static void open(Screen parent,UUID kingdom){var screen=new ChronicleScreen(parent,kingdom);Minecraft.getInstance().setScreen(screen);screen.request(null);}
    public static void receive(CompoundTag view){var screen=Minecraft.getInstance().screen;if(screen instanceof ChronicleScreen chronicle)chronicle.receive(view);else if(screen instanceof ChronicleFilterScreen filter)filter.receive(view);}
}
