package com.millenairekingdoms.client;

import com.millenairekingdoms.localization.LocalizedResources;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Standard active Minecraft language/resource-pack translation; no separate language selector or saved prose. */
final class ClientText {
    private ClientText(){}
    static net.minecraft.network.chat.MutableComponent component(String key,Object...args){var values=args.clone();for(int n=0;n<values.length;n++)if(values[n] instanceof Number number)values[n]=LocalizedResources.number(Minecraft.getInstance().options.languageCode,number);return Component.translatableWithFallback(key,LocalizedResources.catalog("en_us").getOrDefault(key,key),values);}
    static Component culture(String id,String fallback){var location=net.minecraft.resources.ResourceLocation.tryParse(id);return location==null?Component.literal(fallback):Component.translatableWithFallback("culture."+location.getNamespace()+"."+location.getPath(),fallback);}
    static Component building(String id,String fallback){var location=net.minecraft.resources.ResourceLocation.tryParse(id);var key=location==null?null:org.millenaire.language.BuildingNameHelper.getTranslationKey(location);return key==null?Component.literal(fallback):Component.translatableWithFallback(key,fallback);}
    static String resolve(String key,Object...args){return component(key,args).getString();}
}
