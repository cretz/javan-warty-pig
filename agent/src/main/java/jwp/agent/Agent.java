package jwp.agent;

import jwp.fuzz.ClassBranchAdapter;
import org.objectweb.asm.Type;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.security.ProtectionDomain;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class Agent implements ClassFileTransformer {

  private static Agent instance;

  public static Agent instance() { return instance; }

  public static void premain(String agentArgs, Instrumentation inst) {
    // TODO: make prefixes and retransform-bootstrap-ness configurable
    // Add new self as a transformer of classes and init
    instance = new Agent(inst);
    instance.init(true);
  }

  public final Instrumentation inst;
  // This is thread safe to modify
  public final Set<String> classPrefixesToIgnore;

  public Agent(Instrumentation inst) {
    this(inst, new String[] { "com.sun.", "java.", "jdk.", "jwp.agent.", "jwp.fuzz.", "kotlin.", "scala.", "sun." });
  }

  public Agent(Instrumentation inst, String[] classPrefixesToIgnore) {
    this.inst = inst;
    this.classPrefixesToIgnore = Collections.newSetFromMap(new ConcurrentHashMap<>());
    this.classPrefixesToIgnore.addAll(Arrays.asList(classPrefixesToIgnore));
  }

  public void init(boolean retransformBootstrapped) {
    // Add self as transfomer
    inst.addTransformer(this, inst.isRetransformClassesSupported());

    // Retransform all non-ignored classes
    if (retransformBootstrapped && inst.isRetransformClassesSupported()) {
      List<Class<?>> classesToRetransform = new ArrayList<>();
      for (Class<?> cls : inst.getAllLoadedClasses()) {
        if (inst.isModifiableClass(cls) && !isClassIgnored(cls)) classesToRetransform.add(cls);
      }
      if (!classesToRetransform.isEmpty()) {
        try {
          inst.retransformClasses(classesToRetransform.toArray(new Class<?>[classesToRetransform.size()]));
        } catch (UnmodifiableClassException e) {
          System.out.println("Failed retransforming classes: " + e);
        }
      }
    }
  }

  public boolean isClassIgnored(Class<?> cls) { return isClassIgnored(cls.getName()); }
  public boolean isClassIgnored(String className) {
    for (String classPrefixToIgnore : classPrefixesToIgnore) {
      if (className.startsWith(classPrefixToIgnore)) return true;
    }
    return false;
  }

  @Override
  public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
      ProtectionDomain protectionDomain, byte[] classfileBuffer) {
    if (className == null || isClassIgnored(Type.getObjectType(className).getClassName())) return null;
    try {
      return ClassBranchAdapter.transform(classfileBuffer);
    } catch (Throwable e) {
      System.err.println("Failed to transform " + className + ": " + e);
      return null;
    }
  }
}