package com.millenairekingdoms.localization;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** Bundled fallback/test catalogs. Actual client rendering supplies Minecraft's current resource-pack resolver. */
public final class LocalizedResources {
    public static final String PREFIX="millenairekingdoms.chronicle.";
    private static final Pattern SLOT=Pattern.compile("%(?:(\\d+)\\$)?([sdf])|%%");
    private static final Map<String,Map<String,String>> CATALOGS=new ConcurrentHashMap<>();
    private static final List<String> LOCALES=manifest();
    private LocalizedResources(){}
    private static List<String> manifest(){
        try(var in=LocalizedResources.class.getResourceAsStream("/assets/millenairekingdoms/locale-manifest.json")){
            if(in==null)throw new IllegalStateException("Missing verified locale manifest");
            var root=JsonParser.parseReader(new InputStreamReader(in,StandardCharsets.UTF_8)).getAsJsonObject();
            return root.getAsJsonArray("locales").asList().stream().map(JsonElement::getAsString).sorted().toList();
        }catch(IOException e){throw new IllegalStateException("Locale manifest unavailable",e);}
    }
    public static List<String> locales(){return LOCALES;}
    public static String normalize(String language){var code=language==null?"en_us":language.toLowerCase(Locale.ROOT).replace('-','_');return LOCALES.contains(code)?code:"en_us";}
    public static Map<String,String> catalog(String language){return CATALOGS.computeIfAbsent(normalize(language),code->{
        try(var in=LocalizedResources.class.getResourceAsStream("/assets/millenairekingdoms/lang/"+code+".json")){
            if(in==null)return Map.of();
            var root=JsonParser.parseReader(new InputStreamReader(in,StandardCharsets.UTF_8)).getAsJsonObject();var values=new LinkedHashMap<String,String>();
            for(var entry:root.entrySet()){if(!entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isString())throw new IllegalArgumentException("Invalid localized value: "+entry.getKey());values.put(entry.getKey(),entry.getValue().getAsString());}return Map.copyOf(values);
        }catch(IOException e){throw new IllegalStateException("Cannot read bundled language "+code,e);}
    });}
    public static Set<String> missingKeys(String language){var missing=new TreeSet<>(catalog("en_us").keySet());missing.removeAll(catalog(language).keySet());return Collections.unmodifiableSet(missing);}
    public static String text(String language,String key,Object...args){return format(language,catalog(language).getOrDefault(key,catalog("en_us").getOrDefault(key,key)),args);}
    /** Literal arguments are appended once: names containing percent signs can never become format instructions. */
    public static String format(String language,String template,Object...args){
        var out=new StringBuilder();var match=SLOT.matcher(template);int end=0,implicit=0;
        while(match.find()){out.append(template,end,match.start());if(match.group().equals("%%"))out.append('%');else{int slot=match.group(1)==null?implicit++:Integer.parseInt(match.group(1))-1;if(slot<0 || slot>=args.length)throw new IllegalArgumentException("Missing localized argument "+slot);Object value=args[slot];out.append(value instanceof Number number?number(language,number):String.valueOf(value));}end=match.end();}return out.append(template,end,template.length()).toString();
    }
    public static String number(String language,Number number){var format=NumberFormat.getNumberInstance(Locale.forLanguageTag(normalize(language).replace('_','-')));format.setMaximumFractionDigits(6);return format.format(number);}
}
