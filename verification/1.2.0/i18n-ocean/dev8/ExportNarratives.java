import com.millenairekingdoms.history.*;
import com.google.gson.*;
import java.util.*;
import java.nio.file.*;
public class ExportNarratives {
 static final String PREFIX="millenairekingdoms.chronicle.";
 static final UUID A=new UUID(10,1),B=new UUID(10,2),ACTOR=new UUID(10,9);
 static String bodyKey(HistoryEvent.Kind k,int v,boolean opposing,boolean upgrade){String key=PREFIX+"body."+k.name().toLowerCase(Locale.ROOT);if(k==HistoryEvent.Kind.CONSTRUCTION_ORDER)return key+(upgrade?".upgrade":".new");if(Set.of(HistoryEvent.Kind.WAR,HistoryEvent.Kind.VILLAGE_CONQUEST,HistoryEvent.Kind.CAPITAL_CONQUEST).contains(k))return key+(opposing?".opponent":".own");if(Set.of(HistoryEvent.Kind.FOUNDING,HistoryEvent.Kind.INCORPORATION,HistoryEvent.Kind.PEACE).contains(k))return key+"."+(v==0?0:1);return key;}
 static HistoryEvent event(HistoryEvent.Kind k,int n,String culture,String ethos,int context){return HistoryEvent.create(k,81,1944000,"minecraft:overworld","i18n-golden/"+n+"/"+context,List.of(new HistoryEvent.Realm(A,"İznik",culture,ethos),new HistoryEvent.Realm(B,"Bellefontaine","millenaire:norman","AGRICULTURAL")),context==3?List.of():List.of(new HistoryEvent.Place(new UUID(10,3),"O'Neill %s",true,100,200),new HistoryEvent.Place(new UUID(10,4),"Aşağıköy",true,300,400)),context==0?null:ACTOR,context==0?"":"Ruler %s","","RECORDED",context==0?Map.of():Map.of("buildingName","Forge %s","upgrade",Boolean.toString(context==2)));}
 public static void main(String[] args)throws Exception{
  var out=Path.of(args[0]);var gson=new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
  var english=ChronicleNarrator.class.getDeclaredMethod("english",HistoryEvent.class,String.class,String.class,String.class,String.class,boolean.class,int.class);var turkish=ChronicleNarrator.class.getDeclaredMethod("turkish",HistoryEvent.class,String.class,String.class,String.class,String.class,boolean.class,int.class);var opening=ChronicleNarrator.class.getDeclaredMethod("opening",String.class,boolean.class,int.class);var emphasis=ChronicleNarrator.class.getDeclaredMethod("emphasis",String.class,boolean.class,int.class);for(var method:List.of(english,turkish,opening,emphasis))method.setAccessible(true);
  for(boolean tr:List.of(false,true)){
   var lang=new LinkedHashMap<String,String>();for(var k:HistoryEvent.Kind.values())lang.put(PREFIX+"title."+k.name().toLowerCase(Locale.ROOT),tr?ChronicleNarrator.titleTr(k):ChronicleNarrator.titleEn(k));
   for(String culture:List.of("norman","byzantines","seljuk","indian","japanese","mayan","inuits","world")){lang.put(PREFIX+"tradition."+culture,ChronicleNarrator.tradition(culture,tr));for(int v=0;v<3;v++)lang.put(PREFIX+"opening."+culture+"."+v,(String)opening.invoke(null,culture,tr,v));}
   for(String ethos:List.of("MILITARY","RELIGIOUS","AGRICULTURAL","ARTISAN","GENERAL"))for(int v=0;v<2;v++)lang.put(PREFIX+"emphasis."+ethos.toLowerCase(Locale.ROOT)+"."+v,(String)emphasis.invoke(null,ethos,tr,v));
   for(var k:HistoryEvent.Kind.values())for(int v=0;v<3;v++)for(boolean opposing:List.of(false,true))for(boolean upgrade:List.of(false,true)){
    var e=HistoryEvent.create(k,1,2,"minecraft:overworld","export",List.of(new HistoryEvent.Realm(A,"%1$s","millenaire:norman","GENERAL")),List.of(),ACTOR,"%6$s","","RECORDED",Map.of("buildingName","%5$s","upgrade",Boolean.toString(upgrade)));
    var value=(String)(tr?turkish:english).invoke(null,e,"%1$s","%2$s","%3$s","%4$s",opposing,v);value=value.replace(tr?"“%5$s” adlı yapı":"the building known as “%5$s”","%5$s");String key=bodyKey(k,v,opposing,upgrade);String before=lang.putIfAbsent(key,value);if(before!=null&&!before.equals(value))throw new IllegalStateException(key);
   }
   lang.put(PREFIX+"structure.known",tr?"“%1$s” adlı yapı":"the building known as “%1$s”");lang.put(PREFIX+"structure.unknown",tr?"köy yapısı":"a village building");lang.put(PREFIX+"actor.unknown",tr?"Kayda geçmiş hükümdar":"the recorded ruler");lang.put(PREFIX+"settlement.unknown",tr?"yerleşim":"the settlement");Files.writeString(out.resolve("narrative-"+(tr?"tr_tr":"en_us")+".json"),gson.toJson(lang)+"\n");
  }
  var rows=new JsonArray();var cultures=List.of("norman","byzantines","seljuk","indian","japanese","mayan","inuits","world");var ethos=List.of("MILITARY","RELIGIOUS","AGRICULTURAL","ARTISAN","GENERAL");for(var k:HistoryEvent.Kind.values())for(int n=0;n<24;n++)for(int context=0;context<4;context++)for(var perspective:List.of(A,B))for(var language:List.of("en_us","tr_tr")){var e=event(k,n,"millenaire:"+cultures.get(n%8),ethos.get(n%5),context);var row=new JsonObject();row.add("event",gson.toJsonTree(e));row.addProperty("perspective",perspective.toString());row.addProperty("language",language);row.add("account",gson.toJsonTree(ChronicleNarrator.render(e,perspective,language)));rows.add(row);}
  Files.writeString(out.resolve("narrative-alpha1-golden.json"),gson.toJson(rows)+"\n");System.out.println("Exported golden rows="+rows.size());
 }
}
