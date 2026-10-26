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
        var name=event.facts().getOrDefault("buildingName","");String structure=name.isBlank()?text.resolve(P+"compact.structure.unknown"):text.resolve(P+"compact.structure.known",name);
        String actor=event.actorName().isBlank()?text.resolve(P+"compact.actor.unknown"):event.actorName();
        String culture=culture(voice.culture());String ethos=ETHOS.contains(voice.ethos())?voice.ethos():"GENERAL";
        String key=bodyKey(event.kind(),variant,opposing,event.facts().getOrDefault("upgrade","false").equals("true"));
        // An occasional word choice belongs inside the event. There is no per-ethos motto or compulsory preamble.
        if(variant==0 && (event.kind()==HistoryEvent.Kind.FOUNDING || event.kind()==HistoryEvent.Kind.PEACE
                || event.kind()==HistoryEvent.Kind.WAR && !opposing))key+="."+ethos.toLowerCase(Locale.ROOT);
        String body=text.resolve(key,a,b,village,target,structure,actor);
        return new Account(title(event.kind(),text),body,voice.name(),text.resolve(P+"tradition."+culture),voice.ethos(),variant);
    }
    public static String bodyKey(HistoryEvent.Kind kind,int variant,boolean opposing,boolean upgrade){
        String key=P+"compact.body."+kind.name().toLowerCase(Locale.ROOT);
        if(kind==HistoryEvent.Kind.CONSTRUCTION_ORDER)key+=upgrade?".upgrade":".new";
        if(Set.of(HistoryEvent.Kind.WAR,HistoryEvent.Kind.VILLAGE_CONQUEST,HistoryEvent.Kind.CAPITAL_CONQUEST,HistoryEvent.Kind.STRATEGIC_BATTLE).contains(kind))key+=opposing?".opponent":".own";
        return key+"."+Math.floorMod(variant,3);
    }
    private static String culture(String culture){String id=culture.contains(":")?culture.substring(culture.indexOf(':')+1):culture;return CULTURES.contains(id)?id:"world";}
    public static String title(HistoryEvent.Kind kind,Text text){return text.resolve(P+"compact.title."+kind.name().toLowerCase(Locale.ROOT));}
    public static String title(HistoryEvent.Kind kind,String language){return LocalizedResources.text(language,P+"title."+kind.name().toLowerCase(Locale.ROOT));}
    public static String titleEn(HistoryEvent.Kind kind){return title(kind,"en_us");}
    public static String titleTr(HistoryEvent.Kind kind){return title(kind,"tr_tr");}
    public static String tradition(String culture,boolean tr){return LocalizedResources.text(tr?"tr_tr":"en_us",P+"tradition."+culture(culture));}
}
