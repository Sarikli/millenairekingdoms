package com.millenairekingdoms.history;

import com.millenairekingdoms.localization.LocalizedResources;
import java.util.*;

/** Deterministic presentation of immutable facts through standard localization keys. No network or save writes. */
public final class ChronicleNarrator {
    public record Account(String heading,String prose,String perspective,String culture,String ethos,int variant) {}
    @FunctionalInterface public interface Text {String resolve(String key,Object...args);}
    private static final String P=LocalizedResources.PREFIX;
    private static final Set<String> CULTURES=Set.of("norman","byzantines","seljuk","indian","japanese","mayan","inuits");
    private static final Set<String> ETHOS=Set.of("MILITARY","RELIGIOUS","AGRICULTURAL","ARTISAN","GENERAL");
    private ChronicleNarrator(){}
    public static Account render(HistoryEvent event,UUID perspective,String language){return render(event,perspective,(key,args)->LocalizedResources.text(language,key,args));}
    /** Clients pass the current Minecraft/resource-pack resolver, so changing language never changes stored facts. */
    public static Account render(HistoryEvent event,UUID perspective,Text text){
        var voice=event.realms().stream().filter(r->r.id().equals(perspective)).findFirst().orElse(event.realms().getFirst());
        boolean opposing=event.realms().size()>1 && voice.id().equals(event.realms().get(1).id());int variant=Math.floorMod(event.id().hashCode() ^ voice.id().hashCode(),3);
        String a=event.realms().getFirst().name(),b=event.realms().size()>1?event.realms().get(1).name():"";
        String village=event.places().isEmpty()?text.resolve(P+"settlement.unknown"):event.places().getFirst().name();String target=event.places().size()>1?event.places().get(1).name():village;
        var name=event.facts().getOrDefault("buildingName","");String structure=name.isBlank()?text.resolve(P+"structure.unknown"):text.resolve(P+"structure.known",name);
        String actor=event.actorName().isBlank()?text.resolve(P+"actor.unknown"):event.actorName();
        String body=text.resolve(bodyKey(event.kind(),variant,opposing,event.facts().getOrDefault("upgrade","false").equals("true")),a,b,village,target,structure,actor);
        String culture=culture(voice.culture());String ethos=ETHOS.contains(voice.ethos())?voice.ethos():"GENERAL";
        String opening=text.resolve(P+"opening."+culture+"."+variant);String emphasis=text.resolve(P+"emphasis."+ethos.toLowerCase(Locale.ROOT)+"."+(variant==0?0:1));
        return new Account(title(event.kind(),text),opening+body+" "+emphasis,voice.name(),text.resolve(P+"tradition."+culture),voice.ethos(),variant);
    }
    public static String bodyKey(HistoryEvent.Kind kind,int variant,boolean opposing,boolean upgrade){
        String key=P+"body."+kind.name().toLowerCase(Locale.ROOT);
        if(kind==HistoryEvent.Kind.CONSTRUCTION_ORDER)return key+(upgrade?".upgrade":".new");
        if(Set.of(HistoryEvent.Kind.WAR,HistoryEvent.Kind.VILLAGE_CONQUEST,HistoryEvent.Kind.CAPITAL_CONQUEST).contains(kind))return key+(opposing?".opponent":".own");
        if(Set.of(HistoryEvent.Kind.FOUNDING,HistoryEvent.Kind.INCORPORATION,HistoryEvent.Kind.PEACE).contains(kind))return key+"."+(variant==0?0:1);return key;
    }
    private static String culture(String culture){String id=culture.contains(":")?culture.substring(culture.indexOf(':')+1):culture;return CULTURES.contains(id)?id:"world";}
    public static String title(HistoryEvent.Kind kind,Text text){return text.resolve(P+"title."+kind.name().toLowerCase(Locale.ROOT));}
    public static String title(HistoryEvent.Kind kind,String language){return LocalizedResources.text(language,P+"title."+kind.name().toLowerCase(Locale.ROOT));}
    public static String titleEn(HistoryEvent.Kind kind){return title(kind,"en_us");}
    public static String titleTr(HistoryEvent.Kind kind){return title(kind,"tr_tr");}
    public static String tradition(String culture,boolean tr){return LocalizedResources.text(tr?"tr_tr":"en_us",P+"tradition."+culture(culture));}
}
