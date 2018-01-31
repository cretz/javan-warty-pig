package jwp.agent;

import jwp.fuzz.AgentController;
import jwp.fuzz.ClassBranchAdapter;
import org.objectweb.asm.Type;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.security.ProtectionDomain;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** The main agent and class transformer. Access to this is provided via {@link AgentController}. */
public class Agent implements ClassFileTransformer, AgentController.Agent {

  protected static final String[] CLASS_PREFIXES_TO_IGNORE_DEFAULT =
      { "com.sun.", "java.", "jdk.", "jwp.agent.", "jwp.fuzz.", "kotlin.",
          "org.netbeans.lib.profiler.", "scala.", "sun." };
  protected static final boolean RETRANSFORM_BOOTSTRAPPED_DEFAULT = true;

  /** Entry point delegated to from {@link AgentBootstrap#premain(String, Instrumentation)} */
  public static void premain(String agentArgs, Instrumentation inst) {
    Args args = Args.fromString(agentArgs);
    Agent agent = new Agent(inst,
        args.classPrefixesToIgnore == null ? CLASS_PREFIXES_TO_IGNORE_DEFAULT : args.classPrefixesToIgnore);
    agent.init(args.retransformBoostrapped == null ? RETRANSFORM_BOOTSTRAPPED_DEFAULT : args.retransformBoostrapped);
    AgentController.setAgent(agent);
  }

  protected final Instrumentation inst;
  private volatile String[] classPrefixesToIgnore;

  protected Agent(Instrumentation inst, String[] classPrefixesToIgnore) {
    this.inst = inst;
    this.classPrefixesToIgnore = classPrefixesToIgnore;
  }

  @Override
  public synchronized void setClassPrefixesToIgnore(String[] classPrefixesToIgnore) {
    this.classPrefixesToIgnore = classPrefixesToIgnore;
  }

  @Override
  public Class[] getAllLoadedClasses() {
    return inst.getAllLoadedClasses();
  }

  protected void init(boolean retransformBootstrapped) {
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

  @Override
  public void retransformClasses(Class<?>... classes) throws UnmodifiableClassException {
    inst.retransformClasses(classes);
  }

  protected boolean isClassIgnored(Class<?> cls) { return isClassIgnored(cls.getName()); }
  protected boolean isClassIgnored(String className) {
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

  /** Arguments passed in to the agent, parsed via {@link #fromString(String)} */
  public static class Args {
    /** Parse the given string into args or throw an exception on failure */
    public static Args fromString(String str) {
      Boolean retransformBoostrapped = null;
      String[] classPrefixesToIgnore = null;
      if (str != null && !str.isEmpty()) {
        for (String arg : str.split(";")) {
          if ("noAutoRetransform".equals(arg)) {
            retransformBoostrapped = false;
            continue;
          }
          String[] nameAndPieces = arg.split("=", 2);
          if (nameAndPieces.length != 2 || !"classPrefixesToIgnore".equals(nameAndPieces[0]))
            throw new IllegalArgumentException("Unknown arg: " + arg);
          classPrefixesToIgnore = nameAndPieces[1].split(",");
        }
      }
      return new Args(retransformBoostrapped, classPrefixesToIgnore);
    }

    public final Boolean retransformBoostrapped;
    public final String[] classPrefixesToIgnore;

    public Args(Boolean retransformBoostrapped, String[] classPrefixesToIgnore) {
      this.retransformBoostrapped = retransformBoostrapped;
      this.classPrefixesToIgnore = classPrefixesToIgnore;
    }
  }
}