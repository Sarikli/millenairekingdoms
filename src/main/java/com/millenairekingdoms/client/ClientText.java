package com.millenairekingdoms.client;

import com.millenairekingdoms.localization.LocalizedResources;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Standard active Minecraft language/resource-pack translation; no separate language selector or saved prose. */
final class ClientText {
    private ClientText(){}
    static Component component(String key,Object...args){var values=args.clone();for(int n=0;n<values.length;n++)if(values[n] instanceof Number number)values[n]=LocalizedResources.number(Minecraft.getInstance().options.languageCode,number);return Component.translatable(key,values);}
    static String resolve(String key,Object...args){return component(key,args).getString();}
}
