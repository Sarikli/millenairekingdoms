package com.millenairekingdoms.localization;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.regex.Pattern;
import net.minecraft.network.chat.*;

/** Structured client-localized chat/command text. Names/IDs remain arguments; English remains server/log fallback. */
public final class PublicText {
    private record Diagnostic(String template,Pattern expression,int arguments){}
    private record Catalog(Map<String,String> errors,List<Diagnostic> patterns){}
    private static final Catalog CATALOG=errors();
    private PublicText(){}
    public static String key(String english){try{return "millenairekingdoms.text."+HexFormat.of().formatHex(Arrays.copyOf(MessageDigest.getInstance("SHA-256").digest(english.getBytes(StandardCharsets.UTF_8)),8));}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
    public static MutableComponent of(String english,Object...arguments){
        var values=arguments.clone();for(int n=0;n<values.length;n++){var value=values[n];if(!(value instanceof Component || value instanceof Number || value instanceof Boolean || value instanceof String))values[n]=String.valueOf(value);}
        return Component.translatableWithFallback(key(english),english,values);
    }
    public static MutableComponent error(String english){
        if(english==null)return Component.empty();var key=CATALOG.errors().get(english);if(key!=null)return Component.translatableWithFallback(key,english.replace("%","%%"));
        if(english.length()<=8192)for(var diagnostic:CATALOG.patterns()){var match=diagnostic.expression().matcher(english);if(match.matches()){var values=new Object[diagnostic.arguments()];for(int n=0;n<values.length;n++)values[n]=match.group(n+1);return of(diagnostic.template(),values);}}
        return Component.literal(english);
    }
    private static Catalog errors(){try(var in=PublicText.class.getResourceAsStream("/assets/millenairekingdoms/public-text-sources.json")){
        if(in==null)return new Catalog(Map.of(),List.of());var root=JsonParser.parseReader(new InputStreamReader(in,StandardCharsets.UTF_8)).getAsJsonObject();var out=new HashMap<String,String>();root.getAsJsonObject("errors").entrySet().forEach(e->out.put(e.getKey(),e.getValue().getAsString()));var patterns=new ArrayList<Diagnostic>();
        if(root.has("patterns"))for(var entry:root.getAsJsonArray("patterns")){
            String template=entry.getAsString();var regex=new StringBuilder("\\A");var matcher=Pattern.compile("%s|%%").matcher(template);int end=0,count=0;
            while(matcher.find()){regex.append(Pattern.quote(template.substring(end,matcher.start())));if(matcher.group().equals("%%"))regex.append("%");else{regex.append("(.*?)");count++;}end=matcher.end();}
            if(count<1 || count>4 || template.startsWith("millenairekingdoms.") || template.contains("%s%s"))continue;
            regex.append(Pattern.quote(template.substring(end))).append("\\z");patterns.add(new Diagnostic(template,Pattern.compile(regex.toString(),Pattern.DOTALL),count));
        }
        patterns.sort(Comparator.comparingInt((Diagnostic d)->d.template().length()-d.arguments()*2).reversed());return new Catalog(Map.copyOf(out),List.copyOf(patterns));
    }catch(IOException e){throw new IllegalStateException("Public message inventory unavailable",e);}}
}
