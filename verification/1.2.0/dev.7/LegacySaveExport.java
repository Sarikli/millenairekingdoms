import java.net.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.nbt.*;
public class LegacySaveExport {
 static UUID id(int n){return new UUID(0x1110000000004000L,0x8000000000000000L+n);}
 static Object call(Object target,String name,Object... args)throws Exception{
  for(var m:target.getClass().getMethods())if(m.getName().equals(name)&&m.getParameterCount()==args.length)return m.invoke(target,args);
  throw new NoSuchMethodException(name);
 }
 public static void main(String[] args)throws Exception{
  try(var loader=new URLClassLoader(new URL[]{Path.of(args[0]).toUri().toURL()},LegacySaveExport.class.getClassLoader()){
   protected Class<?> loadClass(String n,boolean resolve)throws ClassNotFoundException{
    if(n.startsWith("com.millenairekingdoms.kingdom.KingdomSavedData")){synchronized(getClassLoadingLock(n)){var c=findLoadedClass(n);if(c==null)c=findClass(n);if(resolve)resolveClass(c);return c;}}
    return super.loadClass(n,resolve);
   }
  }){
   var c=loader.loadClass("com.millenairekingdoms.kingdom.KingdomSavedData");var d=c.getConstructor().newInstance();
   var a=call(d,"create","Legacy111 A",id(1));var b=call(d,"create","Legacy111 B",id(2));var k=call(d,"create","Legacy111 C",id(3));
   var ai=(UUID)call(a,"id");var bi=(UUID)call(b,"id");var ki=(UUID)call(k,"id");
   call(d,"attach",bi,id(4));call(d,"startDay",42L,Map.of(ai,100L,bi,77L));call(d,"claimRule",ki,id(99),"Ruler111",1L);
   var old=call(d,"declareWar",ai,bi,2L);call(d,"peace",call(old,"id"),"RULER_PEACE",10);
   var war=call(d,"declareWar",ai,bi,3L);var raid=call(d,"begin",id(1),id(4),4L,null);call(d,"recordVictory",raid);
   var outcome=loader.loadClass("com.millenairekingdoms.kingdom.KingdomSavedData$Outcome");call(d,"finish",raid,outcome.getField("LOST").get(null));
   call(d,"offerPeace",call(war,"id"));call(d,"requestJoin",id(5),ki,null,"",5L);call(d,"addOrder",ki,id(6),id(3),id(99),6L,Long.MAX_VALUE/4,Long.MAX_VALUE/2);call(d,"scheduleDecision",70L);
   var root=new CompoundTag();root.put("data",(CompoundTag)call(d,"save",new CompoundTag(),null));NbtIo.writeCompressed(root,Path.of(args[1]));System.out.println("Exported original1.1.1 serializer format="+root.getCompound("data").getInt("format"));
  }
 }
}
