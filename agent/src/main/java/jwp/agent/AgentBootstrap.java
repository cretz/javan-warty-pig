package jwp.agent;

import java.lang.instrument.Instrumentation;
import java.net.JarURLConnection;
import java.util.jar.JarFile;

public class AgentBootstrap {
  public static void premain(String agentArgs, Instrumentation inst) {
    // Taken from:
    //  https://github.com/google/allocation-instrumenter/blob/2de61ea4225ab0d75580b7341f39deb688f572c3/src/main/java/com/google/monitoring/runtime/instrumentation/Bootstrap.java.in
    try {
      // Add JAR to bootstrap loader
      java.net.URL url = ClassLoader.getSystemResource("jwp/agent");
      JarFile jarfile = ((JarURLConnection) url.openConnection()).getJarFile();
      inst.appendToBootstrapClassLoaderSearch(jarfile);

      // Invoke Agent::premain w/ runtime lookup
      Class.forName("jwp.agent.Agent").
          getDeclaredMethod("premain", String.class, Instrumentation.class).
          invoke(null, agentArgs, inst);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
