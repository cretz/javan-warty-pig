package jwp.fuzz;

import java.lang.invoke.WrongMethodTypeException;
import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

public class Fuzzer {

  public final Config config;

  public Fuzzer(Config config) {
    this.config = config;
  }

  public void fuzz() throws Throwable { fuzz(new AtomicBoolean()); }

  public void fuzz(AtomicBoolean stopper) throws Throwable {
    try {
      // Go over every param set, invoking
      Iterator<Object[]> paramIter = config.params.iterator();
      AtomicReference<Throwable> stopExRef = new AtomicReference<>();
      boolean first = true;
      Invoker.Config invokerConfig = new Invoker.Config(config.tracer, config.method);
      while (paramIter.hasNext() && !stopper.get()) {
        // If there's an exception, throw it
        Throwable stopEx = stopExRef.get();
        if (stopEx != null) throw stopEx;
        // Exec and grab future
        Object[] params = paramIter.next();
        CompletableFuture<ExecutionResult> fut = config.invoker.invoke(invokerConfig, params);
        // As a special case for the first run, we wait for completion and fail if it's the wrong method type
        if (first) {
          first = false;
          ExecutionResult firstResult = fut.get();
          if (firstResult.exception instanceof WrongMethodTypeException ||
              firstResult.exception instanceof ClassCastException)
            throw new FuzzException.FirstRunFailed(firstResult.exception);
        }
        if (config.onSubmit != null) {
          fut = config.onSubmit.apply(config, fut);
          if (fut == null) continue;
        }
        fut.whenComplete((er, ex) -> {
          if (er != null) config.params.onResult(er);
          if (ex != null) stopExRef.set(ex);
        });
      }
    } finally {
      // Shutdown and wait a really long time
      config.invoker.shutdownAndWaitUntilComplete(1000, TimeUnit.DAYS);
      config.params.close();
    }
  }

  public static class Config {
    public static Builder builder() { return new Builder(); }

    public final Method method;
    public final ParamProvider params;
    public final BiFunction<Config, CompletableFuture<ExecutionResult>, CompletableFuture<ExecutionResult>> onSubmit;
    public final Invoker invoker;
    public final Tracer tracer;
    public final boolean stopOnFutureFailure;

    public Config(Method method, ParamProvider params, BiFunction<Config, CompletableFuture<ExecutionResult>,
        CompletableFuture<ExecutionResult>> onSubmit, Invoker invoker, Tracer tracer, boolean stopOnFutureFailure) {
      this.method = Objects.requireNonNull(method);
      this.params = Objects.requireNonNull(params);
      this.onSubmit = onSubmit;
      this.invoker = Objects.requireNonNull(invoker);
      this.tracer = Objects.requireNonNull(tracer);
      this.stopOnFutureFailure = stopOnFutureFailure;
    }

    public static class Builder {
      public Method method;
      public Builder method(Method method) {
        this.method = method;
        return this;
      }

      public ParamProvider params;
      public Builder params(ParamProvider params) {
        this.params = params;
        return this;
      }

      public BiFunction<Config, CompletableFuture<ExecutionResult>, CompletableFuture<ExecutionResult>> onSubmit;
      public Builder onSubmit(BiFunction<Config, CompletableFuture<ExecutionResult>,
          CompletableFuture<ExecutionResult>> onSubmit) {
        this.onSubmit = onSubmit;
        return this;
      }

      public Invoker invoker;
      public Builder invoker(Invoker invoker) {
        this.invoker = invoker;
        return this;
      }
      public Invoker invokerDefault() {
        return new Invoker.WithExecutorService(new Util.CurrentThreadExecutorService());
      }

      public Tracer tracer;
      public Builder tracer(Tracer tracer) {
        this.tracer = tracer;
        return this;
      }
      public Tracer tracerDefault() { return new Tracer.Instrumenting(); }

      public boolean stopOnFutureFailure;
      public Builder stopOnFutureFailure(boolean stopOnFutureFailure) {
        this.stopOnFutureFailure = stopOnFutureFailure;
        return this;
      }

      public Config build() {
        return new Config(
            method,
            params,
            onSubmit,
            invoker == null ? invokerDefault() : invoker,
            tracer == null ? tracerDefault() : tracer,
            stopOnFutureFailure
        );
      }
    }
  }

  public static class FuzzException extends RuntimeException {
    public FuzzException(String msg) { super(msg); }
    public FuzzException(String msg, Throwable cause) { super(msg, cause); }
    public FuzzException(Throwable cause) { super(cause); }

    public static class FirstRunFailed extends FuzzException {
      public FirstRunFailed(Throwable cause) { super(cause); }
    }
  }
}
