import com.sun.source.tree.*;
import com.sun.source.util.*;
import javax.tools.*;
import com.google.gson.*;
import java.nio.file.*;
import java.nio.charset.*;
import java.security.*;
import java.util.*;
public class CollectPublicText {
 static String key(String english)throws Exception{return "millenairekingdoms.text."+HexFormat.of().formatHex(Arrays.copyOf(MessageDigest.getInstance("SHA-256").digest(english.getBytes(StandardCharsets.UTF_8)),8));}
 public static void main(String[] args)throws Exception{
  var files=Files.walk(Path.of(args[0])).filter(f->f.toString().endsWith(".java")).toList();var compiler=ToolProvider.getSystemJavaCompiler();var manager=compiler.getStandardFileManager(null,null,null);var task=(JavacTask)compiler.getTask(null,manager,null,List.of("-proc:none"),null,manager.getJavaFileObjectsFromPaths(files));var texts=new TreeSet<String>();var errors=new TreeSet<String>();
  var patterns=new TreeSet<String>();for(var unit:task.parse())new TreeScanner<Void,Void>(){
   void add(Tree t,Set<String> set){if(t instanceof LiteralTree l && l.getValue() instanceof String value && value.matches("(?s).*[A-Za-z].*") && !value.startsWith("millenairekingdoms."))set.add(value);}
   String flatten(ExpressionTree t){if(t instanceof BinaryTree b && b.getKind()==Tree.Kind.PLUS)return flatten(b.getLeftOperand())+flatten(b.getRightOperand());if(t instanceof LiteralTree l && l.getValue() instanceof String x)return x.replace("%","%%");return "%s";}
   void diagnostic(ExpressionTree t){if(t instanceof ConditionalExpressionTree c){diagnostic(c.getTrueExpression());diagnostic(c.getFalseExpression());}else if(t instanceof ParenthesizedTree p)diagnostic(p.getExpression());else if(t instanceof LiteralTree)add(t,errors);else if(t instanceof BinaryTree b && b.getKind()==Tree.Kind.PLUS){String template=flatten(t);if(template.replace("%s","").matches("(?s).*[A-Za-z].*"))patterns.add(template);}}
   @Override public Void visitMethodInvocation(MethodInvocationTree node,Void v){var name=node.getMethodSelect().toString();if(name.endsWith("PublicText.of") && !node.getArguments().isEmpty())add(node.getArguments().getFirst(),texts);if(name.equals("check") || name.endsWith(".check") || name.equals("require") || name.endsWith(".require"))for(var arg:node.getArguments())diagnostic(arg);if(name.equals("Attempt.waiting") || name.equals("Attempt.dropped"))diagnostic(node.getArguments().getFirst());return super.visitMethodInvocation(node,v);}
   @Override public Void visitAssignment(AssignmentTree node,Void v){if(node.getVariable().toString().equals("reason") && unit.getSourceFile().getName().endsWith("AutonomyService.java"))diagnostic(node.getExpression());return super.visitAssignment(node,v);}
   @Override public Void visitReturn(ReturnTree node,Void v){if(node.getExpression() instanceof LiteralTree literal && literal.getValue() instanceof String x && x.contains(" "))add(literal,errors);else if(node.getExpression() instanceof BinaryTree || node.getExpression() instanceof ConditionalExpressionTree)diagnostic(node.getExpression());return super.visitReturn(node,v);}
   @Override public Void visitNewClass(NewClassTree node,Void v){var name=node.getIdentifier().toString();if(Set.of("IllegalArgumentException","IllegalStateException").contains(name) && !node.getArguments().isEmpty())diagnostic(node.getArguments().getFirst());return super.visitNewClass(node,v);}
  }.scan(unit,null);
  var values=new TreeMap<String,String>();var errorKeys=new TreeMap<String,String>();for(var text:texts)values.put(key(text),text);for(var error:errors){errorKeys.put(error,key(error));values.putIfAbsent(key(error),error.replace("%","%%"));}for(var pattern:patterns)values.putIfAbsent(key(pattern),pattern);var result=new JsonObject();result.add("values",new Gson().toJsonTree(values));result.add("errors",new Gson().toJsonTree(errorKeys));result.add("patterns",new Gson().toJsonTree(patterns));result.addProperty("message_templates",texts.size());result.addProperty("literal_errors",errors.size());Files.writeString(Path.of(args[1]),new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(result)+"\n");System.out.println("Public templates="+texts.size()+" literal errors="+errors.size()+" dynamic patterns="+patterns.size()+" total keys="+values.size());
 }
}
