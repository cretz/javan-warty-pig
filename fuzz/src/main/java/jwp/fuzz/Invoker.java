package jwp.fuzz;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Interface for invoking a method with some params and returning the result.
 */
public interface Invoker {
  /**
   * Request invocation of {@link Config#method} with the given params. A failure inside the method should not fail the
   * resulting future, but instead set {@link ExecutionResult#exception}. However, a failure to convert the parameters
   * to the required types or other failure to even start the method should throw only a
   * {@link java.lang.invoke.WrongMethodTypeException} or {@link ClassCastException}.
   * <p>
   * Note, this is called repeatedly by the fuzzer. If the submission queue is unbounded, the application will quickly
   * run out of memory. Therefore implementors are encouraged to block here if the queue is full.
   */
  CompletableFuture<ExecutionResult> invoke(Config config, Object[] params);

  /**
   * Shutdown the invoker and wait the given timeout for already-queued invocation requests to complete. Return true if
   * it completed and shut down normally or false if the timeout was reached.
   */
  boolean shutdownAndWaitUntilComplete(long timeout, TimeUnit unit);

  /** Configuration for the {@link Invoker} */
  class Config {
    /** The {@link Tracer} to do the actual tracing */
    public final Tracer tracer;
    /** The reflected method */
    public final Method method;
    /** The method handle that is used for invocation */
    public final MethodHandle handle;

    public Config(Tracer tracer, Method method) {
      this.tracer = tracer;
      this.method = method;
      try {
        handle = MethodHandles.lookup().unreflect(method);
      } catch (IllegalAccessException e) {
        throw new RuntimeException(e);
      }
    }
  }

  /** An implementation of {@link Invoker} using an {@link ExecutorService}. See the constructor for more details. */
  class WithExecutorService implements Invoker {
    /** The executor service this is using */
    public final ExecutorService exec;

    /**
     * Create the invoker with the given executor service. As mentioned in the {@link Invoker} interface, the queue for
     * this executor must be bounded or it will quickly run out of memory as it is fed invocation requests interminably.
     * This means that executors created by {@link java.util.concurrent.Executors} or
     * {@link java.util.concurrent.ForkJoinPool}s are not allowed. {@link java.util.concurrent.ThreadPoolExecutor}
     * should be used instead and it should use the {@link java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy} so
     * no executions are lost or fail.
     */
    public WithExecutorService(ExecutorService exec) {
      this.exec = exec;
    }

    @Override
    public CompletableFuture<ExecutionResult> invoke(Config config, Object[] params) {
      return CompletableFuture.supplyAsync(() -> {
        long beginNs = System.nanoTime();
        Object result = null;
        Throwable ex = null;
        Thread thread = Thread.currentThread();
        config.tracer.startTrace(thread);
        try {
          result = config.handle.invokeWithArguments(params);
        } catch (Throwable e) {
          ex = e;
        }
        long endNs = System.nanoTime();
        BranchHit[] hits = config.tracer.stopTrace(thread);
        if (ex != null) return new ExecutionResult(config.method, params, hits, endNs - beginNs, ex);
        return new ExecutionResult(config.method, params, hits, endNs - beginNs, result);
      }, exec);
    }

    @Override
    public boolean shutdownAndWaitUntilComplete(long timeout, TimeUnit unit) {
      exec.shutdown();
      try {
        return exec.awaitTermination(timeout, unit);
      } catch (InterruptedException e) {
        throw new RuntimeException(e);
      }
    }
  }
}
