package com.millenairekingdoms.localization;

import net.minecraft.network.chat.*;
import net.minecraft.network.chat.contents.TranslatableContents;

/** Locale formatting for typed numbers only. Literal names/IDs, commands, styles and saved facts remain untouched. */
public final class LocalizedComponents {
    private LocalizedComponents(){}
    public static MutableComponent numbers(Component source,String language){
        if(source==null)return Component.empty();MutableComponent result;
        if(source.getContents() instanceof TranslatableContents translation){
            var args=translation.getArgs().clone();for(int n=0;n<args.length;n++){
                if(args[n] instanceof Number number)args[n]=LocalizedResources.number(language,number);
                else if(args[n] instanceof Component component)args[n]=numbers(component,language);
            }
            result=Component.translatableWithFallback(translation.getKey(),translation.getFallback(),args);
        }else result=MutableComponent.create(source.getContents());
        var style=source.getStyle();var hover=style.getHoverEvent();if(hover!=null){var text=hover.getValue(HoverEvent.Action.SHOW_TEXT);if(text!=null)style=style.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,numbers(text,language)));}
        result.setStyle(style);for(var sibling:source.getSiblings())result.append(numbers(sibling,language));return result;
    }
}
