package jwp.fuzz;

import java.lang.instrument.UnmodifiableClassException;

/** Interface to be implemented by the source agent */
public interface Agent {

  /** Get the controller singleton via {@link Controller#getInstance()} */
  static Controller controller() { return Controller.getInstance(); }

  /** The class prefixes to include. Takes precedent over {@link #getClassPrefixesToExclude()}. */
  String[] getClassPrefixesToInclude();

  /**
   * Provide a set of class prefixes to include. This will override the default or the command-line-provided set and
   * apply to loaded classes going forward, but it will not go back and retransform already loaded classes. Takes
   * precedent over {@link #getClassPrefixesToExclude()}.
   */
  void setClassPrefixesToInclude(String... classPrefixesToInclude);

  /** Get all the current class prefixes to exclude. Lower precedent than {@link #getClassPrefixesToInclude()}. */
  String[] getClassPrefixesToExclude();

  /**
   * Provide a set of class prefixes to exclude. This will override the default or the command-line-provided set and
   * apply to loaded classes going forward, but it will not go back and retransform already loaded classes. Lower
   * precedent than {@link #setClassPrefixesToInclude(String...)}.
   */
  void setClassPrefixesToExclude(String... classPrefixesToExclude);

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

  /** Controller to manually control the agent at runtime */
  class Controller implements Agent {

    /** Singleton instance set in {@link #setAgent(Agent)} */
    protected static Controller instance;

    /** Set the global agent. This will change the singleton instance. */
    public static synchronized void setAgent(Agent agent) {
      instance = new Controller(agent);
    }

    /** Get the global controller */
    public static synchronized Controller getInstance() {
      return instance;
    }

    /** Agent to delegate to */
    protected final Agent agent;

    /** Construct the controller with an agent to delegate to */
    public Controller(Agent agent) {
      this.agent = agent;
    }

    @Override
    public String[] getClassPrefixesToInclude() { return agent.getClassPrefixesToInclude(); }

    @Override
    public void setClassPrefixesToInclude(String... classPrefixesToInclude) {
      agent.setClassPrefixesToInclude(classPrefixesToInclude);
    }

    @Override
    public String[] getClassPrefixesToExclude() { return agent.getClassPrefixesToExclude(); }

    @Override
    public void setClassPrefixesToExclude(String... classPrefixesToExclude) {
      agent.setClassPrefixesToExclude(classPrefixesToExclude);
    }

    @Override
    public Class[] getAllLoadedClasses() { return agent.getAllLoadedClasses(); }

    @Override
    public void retransformClasses(Class<?>... classes) throws UnmodifiableClassException {
      agent.retransformClasses(classes);
    }
  }
}
