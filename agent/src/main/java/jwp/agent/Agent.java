package jwp.agent;

import java.lang.instrument.Instrumentation;

class Agent {
  public static void premain(String agentArgs, Instrumentation inst) {
    System.out.println("You started the agent!");
  }
}