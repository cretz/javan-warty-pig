package jwp.agent;

import jwp.fuzz.ClassBranchAdapter;
import org.objectweb.asm.Type;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.security.ProtectionDomain;
import java.util.*;

/** The main agent and class transformer. Access to this is provided via {@link jwp.fuzz.Agent.Controller}. */
public class Agent implements ClassFileTransformer, jwp.fuzz.Agent {

  protected static final String[] CLASS_PREFIXES_TO_EXCLUDE_DEFAULT =
      { "com.sun.", "java.", "jdk.", "jwp.agent.", "jwp.fuzz.", "kotlin.",
          "org.netbeans.lib.profiler.", "scala.", "sun." };
  protected static final boolean RETRANSFORM_BOOTSTRAPPED_DEFAULT = true;

  /** Entry point delegated to from {@link AgentBootstrap#premain(String, Instrumentation)} */
  public static void premain(String agentArgs, Instrumentation inst) {
    Args args = Args.fromString(agentArgs);
    Agent agent = new Agent(inst, args.classPrefixesToInclude,
        args.classPrefixesToExclude == null ? CLASS_PREFIXES_TO_EXCLUDE_DEFAULT : args.classPrefixesToExclude);
    agent.init(args.retransformBoostrapped == null ? RETRANSFORM_BOOTSTRAPPED_DEFAULT : args.retransformBoostrapped);
    Controller.setAgent(agent);
  }

  protected final Instrumentation inst;
  private volatile String[] classPrefixesToInclude;
  private volatile String[] classPrefixesToExclude;

  protected Agent(Instrumentation inst, String[] classPrefixesToInclude, String[] classPrefixesToExclude) {
    this.inst = inst;
    this.classPrefixesToInclude = classPrefixesToInclude;
    this.classPrefixesToExclude = classPrefixesToExclude;
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

  protected boolean isClassIgnored(Class<?> cls) { return isClassIgnored(cls.getName()); }
  protected boolean isClassIgnored(String className) {
    if (classPrefixesToInclude != null) {
      for (String classPrefixToInclude : classPrefixesToInclude) {
        if (className.startsWith(classPrefixToInclude)) return false;
      }
    }
    if (classPrefixesToExclude != null) {
      for (String classPrefixToExclude : classPrefixesToExclude) {
        if (className.startsWith(classPrefixToExclude)) return true;
      }
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

  @Override
  public String[] getClassPrefixesToInclude() {
    return Arrays.copyOf(classPrefixesToInclude, classPrefixesToInclude.length);
  }

  @Override
  public void setClassPrefixesToInclude(String... classPrefixesToInclude) {
    this.classPrefixesToInclude = Arrays.copyOf(classPrefixesToInclude, classPrefixesToInclude.length);
  }

  @Override
  public String[] getClassPrefixesToExclude() {
    return Arrays.copyOf(classPrefixesToExclude, classPrefixesToExclude.length);
  }

  @Override
  public void setClassPrefixesToExclude(String... classPrefixesToExclude) {
    this.classPrefixesToExclude = Arrays.copyOf(classPrefixesToExclude, classPrefixesToExclude.length);
  }

  @Override
  public Class[] getAllLoadedClasses() {
    return inst.getAllLoadedClasses();
  }

  @Override
  public void retransformClasses(Class<?>... classes) throws UnmodifiableClassException {
    inst.retransformClasses(classes);
  }

  /** Arguments passed in to the agent, parsed via {@link #fromString(String)} */
  public static class Args {

    /** Parse the given string into args or throw an exception on failure */
    public static Args fromString(String str) {
      Boolean retransformBoostrapped = null;
      String[] classPrefixesToInclude = null;
      String[] classPrefixesToExclude = null;
      if (str != null && !str.isEmpty()) {
        for (String arg : str.split(";")) {
          if ("noAutoRetransform".equals(arg)) {
            retransformBoostrapped = false;
            continue;
          }
          String[] nameAndPieces = arg.split("=", 2);
          if (nameAndPieces.length != 2) throw new IllegalArgumentException("Unknown arg: " + arg);
          switch (nameAndPieces[0]) {
            case "classPrefixesToInclude":
              classPrefixesToInclude = stringArrayArg(nameAndPieces[1]);
              break;
            case "classPrefixesToExclude":
              classPrefixesToExclude = stringArrayArg(nameAndPieces[1]);
              break;
            default:
              throw new IllegalArgumentException("Unknown arg: " + arg);
          }
        }
      }
      return new Args(retransformBoostrapped, classPrefixesToInclude, classPrefixesToExclude);
    }

    protected static String[] stringArrayArg(String str) {
      String[] ret = str.split(",");
      if (ret.length == 1 && ret[0].isEmpty()) return new String[0];
      return ret;
    }

    public final Boolean retransformBoostrapped;
    public final String[] classPrefixesToInclude;
    public final String[] classPrefixesToExclude;

    public Args(Boolean retransformBoostrapped, String[] classPrefixesToInclude, String[] classPrefixesToExclude) {
      this.retransformBoostrapped = retransformBoostrapped;
      this.classPrefixesToInclude = classPrefixesToInclude;
      this.classPrefixesToExclude = classPrefixesToExclude;
    }
  }
}