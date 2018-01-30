package jwp.fuzz;

import java.lang.instrument.UnmodifiableClassException;

/** Controller to manually control the agent at runtime */
public class AgentController {

  /** Singleton instance set in {@link #setAgent(Agent)} */
  protected static AgentController instance;

  /** Set the global agent. This will change the singleton instance. */
  public static synchronized void setAgent(Agent agent) {
    instance = new AgentController(agent);
  }

  /** Get the global controller */
  public static synchronized AgentController getInstance() {
    return instance;
  }

  /** Agent to delegate to */
  protected final Agent agent;

  /** Construct the controller with an agent to delegate to */
  public AgentController(Agent agent) {
    this.agent = agent;
  }

  /** Delegates to {@link Agent#setClassPrefixesToIgnore(String...)} */
  public void setClassPrefixesToIgnore(String... classPrefixesToIgnore) {
    agent.setClassPrefixesToIgnore(classPrefixesToIgnore);
  }

  /** Delegates to {@link Agent#getAllLoadedClasses()} */
  public Class[] getAllLoadedClasses() {
    return agent.getAllLoadedClasses();
  }

  /** Delegates to {@link Agent#retransformClasses(Class[])} */
  public void retransformClasses(Class<?>... classes) throws UnmodifiableClassException {
    agent.retransformClasses(classes);
  }

  /** Interface to be implemented by the source agent */
  public interface Agent {

    /**
     * Provide a set of class prefixes to ignore. This will override the default or the command-line-provided set and
     * apply to loaded classes going forward, but it will not go back and retransform already loaded classes.
     */
    void setClassPrefixesToIgnore(String... classPrefixesToIgnore);

    /**
     * Get a list of all loaded classes by the agent. This can be used to know what classes to retransform if necessary.
     * @see java.lang.instrument.Instrumentation#getAllLoadedClasses()
     */
    Class[] getAllLoadedClasses();

    /**
     * Retransform the already-loaded classes. Retransformation of already-transformed classes is undefined behavior.
     * @see java.lang.instrument.Instrumentation#retransformClasses(Class[])
     */
    void retransformClasses(Class<?>... classes) throws UnmodifiableClassException;
  }
}
