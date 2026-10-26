package com.millenairekingdoms.client;

import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;

/** Does not open unsolicited screens when a late read-only response arrives after Back/Esc. */
public final class HistoryClient {
    private HistoryClient(){}
    public static void open(Screen parent,UUID kingdom){open(parent,kingdom,null);}
    public static void open(Screen parent,UUID kingdom,UUID village){var screen=new ChronicleScreen(parent,kingdom,village);Minecraft.getInstance().setScreen(screen);screen.request(null);}
    public static void openAtlas(Screen parent,UUID kingdom,UUID event,UUID village){var screen=new AtlasScreen(parent,kingdom,event,village);Minecraft.getInstance().setScreen(screen);screen.refresh();}
    public static void receive(CompoundTag view){
        Screen screen=Minecraft.getInstance().screen;
        for(int depth=0;screen!=null && depth<64;depth++){
            if(screen instanceof ChronicleScreen chronicle)chronicle.receive(view);else if(screen instanceof ChronicleFilterScreen filter)filter.receive(view);else if(screen instanceof AtlasScreen atlas)atlas.receive(view);else return;
            screen=previous(screen);
        }
    }
    /** Late management responses refresh the retained parent without replacing an open chronicle/atlas. */
    public static boolean updateManagement(CompoundTag view){
        Screen screen=Minecraft.getInstance().screen;if(!(screen instanceof ChronicleScreen || screen instanceof ChronicleFilterScreen || screen instanceof AtlasScreen))return false;
        for(int depth=0;screen!=null && depth<64;depth++){if(screen instanceof KingdomScreen kingdom){kingdom.update(view);return true;}screen=previous(screen);}return true;
    }
    private static Screen previous(Screen screen){if(screen instanceof ChronicleScreen chronicle)return chronicle.parent;if(screen instanceof ChronicleFilterScreen filter)return filter.previous();if(screen instanceof AtlasScreen atlas)return atlas.previous();return null;}
}
