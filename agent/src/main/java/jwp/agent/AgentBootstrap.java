package jwp.agent;

import java.lang.instrument.Instrumentation;
import java.net.JarURLConnection;
import java.util.jar.JarFile;

/** Contains the primary entry point for the agent at {@link #premain(String, Instrumentation)} */
public class AgentBootstrap {
  /**
   * Entry point for the agent. This concept was taken from
   * <a href="https://github.com/google/allocation-instrumenter/blob/2de61ea4225ab0d75580b7341f39deb688f572c3/src/main/java/com/google/monitoring/runtime/instrumentation/Bootstrap.java.in">
   *   Google's Allocation Instrumenter
   * </a>. It also adds this JAR in the bootstrap classpath
   */
  public static void premain(String agentArgs, Instrumentation inst) {
    try {
      // Add JAR to bootstrap loader
      java.net.URL url = ClassLoader.getSystemResource("jwp/agent");
      JarFile jarFile = ((JarURLConnection) url.openConnection()).getJarFile();
      inst.appendToBootstrapClassLoaderSearch(jarFile);

      // Invoke Agent::premain w/ runtime lookup
      Class.forName("jwp.agent.Agent").
          getDeclaredMethod("premain", String.class, Instrumentation.class).
          invoke(null, agentArgs, inst);
    } catch (Exception e) {
      System.err.println("Unable to start agent: " + e);
      throw new RuntimeException(e);
    }
  }
}
