package jwp.fuzz;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

public interface Invoker {
  // Must complete the future with WrongMethodTypeException if the method
  // could not be executed with the given parameters.
  CompletableFuture<ExecutionResult> invoke(Config config, Object[] params);

  boolean shutdownAndWaitUntilComplete(long timeout, TimeUnit unit);

  class Config {
    public final Tracer tracer;
    public final Method method;
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

  class WithExecutorService implements Invoker {
    public final ExecutorService exec;

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
