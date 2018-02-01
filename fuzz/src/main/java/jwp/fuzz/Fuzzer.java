package jwp.fuzz;

import java.lang.invoke.WrongMethodTypeException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/** The main fuzzer that delegates to the different pieces configured via {@link Config} */
public class Fuzzer {

  /** The immutable fuzzer config */
  public final Config config;

  /** Create a new fuzzer with the given config */
  public Fuzzer(Config config) {
    this.config = config;
  }

  /**
   * Run the fuzzer for the given amount of time
   * @see #fuzz(AtomicBoolean)
   */
  public void fuzzFor(long time, TimeUnit unit) throws Throwable {
    AtomicBoolean stopper = new AtomicBoolean();
    new Timer().schedule(new TimerTask() {
      @Override
      public void run() { stopper.set(true); }
    }, unit.toMillis(time));
    fuzz(stopper);
  }

  /**
   * Run the fuzzer with no timeout
   * @see #fuzz(AtomicBoolean)
   */
  public void fuzz() throws Throwable { fuzz(new AtomicBoolean()); }

  /**
   * Run the fuzzer until the given {@link AtomicBoolean} is set to true. This is a blocking operation and will only
   * throw on an exceptional circumstance and {@link Config#stopOnFutureFailure} is true. One exceptional circumstance
   * that causes this method to throw a {@link FuzzException.FirstRunFailed} is if the first invocation throws a
   * {@link WrongMethodTypeException} or {@link ClassCastException}. These usually signify that the parameters were
   * wrong, but just in case, callers should ensure that their method does not throw either of these on first run.
   */
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
        // Obtain the params, but copy them
        Object[] params = paramIter.next();
        params = Arrays.copyOf(params, params.length);
        // Exec and grab future
        CompletableFuture<ExecutionResult> fut = config.invoker.invoke(invokerConfig, params);
        if (config.sleepAfterSubmit > 0) Thread.sleep(config.sleepAfterSubmit);
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
          if (ex != null && config.stopOnFutureFailure) stopExRef.set(ex);
        });
      }
    } finally {
      // Shutdown and wait a really long time
      config.invoker.shutdownAndWaitUntilComplete(1000, TimeUnit.DAYS);
      config.params.close();
    }
  }

  /** Configuration for the {@link Fuzzer}. Can use {@link #builder()} to build the config easier */
  public static class Config {
    /** Create a {@link Builder} for easy building */
    public static Builder builder() { return new Builder(); }

    /** See {@link Builder#method(Method)} */
    public final Method method;
    /** See {@link Builder#params(ParamProvider)} */
    public final ParamProvider params;
    /** See {@link Builder#onSubmit(BiFunction)} */
    public final BiFunction<Config, CompletableFuture<ExecutionResult>, CompletableFuture<ExecutionResult>> onSubmit;
    /** See {@link Builder#invoker(Invoker)} */
    public final Invoker invoker;
    /** See {@link Builder#tracer(Tracer)} */
    public final Tracer tracer;
    /** See {@link Builder#stopOnFutureFailure(boolean)} */
    public final boolean stopOnFutureFailure;
    /** See {@link Builder#sleepAfterSubmit(long)} */
    public final long sleepAfterSubmit;

    public Config(Method method, ParamProvider params, BiFunction<Config, CompletableFuture<ExecutionResult>,
        CompletableFuture<ExecutionResult>> onSubmit, Invoker invoker, Tracer tracer,
        boolean stopOnFutureFailure, long sleepAfterSubmit) {
      this.method = Objects.requireNonNull(method);
      this.params = Objects.requireNonNull(params);
      this.onSubmit = onSubmit;
      this.invoker = Objects.requireNonNull(invoker);
      this.tracer = Objects.requireNonNull(tracer);
      this.stopOnFutureFailure = stopOnFutureFailure;
      this.sleepAfterSubmit = sleepAfterSubmit;
    }

    /**
     * Builder to make creating {@link Config}s easier. At the least, {@link #method(Method)} and
     * {@link #params(ParamProvider)} are required.
     */
    public static class Builder {
      /** See {@link #method(Method)} */
      public Method method;
      /**
       * The method to be invoked by the fuzzer. This must be a static method. This is required and there is no default.
       */
      public Builder method(Method method) {
        this.method = method;
        return this;
      }

      /** See {@link #params(ParamProvider)} */
      public ParamProvider params;
      /**
       * The {@link ParamProvider} that will generate invocation parameters. This is required and there is no default.
       */
      public Builder params(ParamProvider params) {
        this.params = params;
        return this;
      }

      /** See {@link #onSubmit(BiFunction)} */
      public BiFunction<Config, CompletableFuture<ExecutionResult>, CompletableFuture<ExecutionResult>> onSubmit;
      /**
       * A function that will be called just after each invocation is requested. It is called with a future and must
       * return a future. This is called before anything else is done like recording the result or checking the future's
       * exception when {@link Config#stopOnFutureFailure} is true. So this function can manipulate the future or the
       * result inside it. Note, the future does not fail just because the inner invocation does. Instead, it will
       * succeed and {@link ExecutionResult#exception} will contain the exception. There is optional and there is no
       * default. Setting this overrides any value it may have had.
       */
      public Builder onSubmit(BiFunction<Config, CompletableFuture<ExecutionResult>,
          CompletableFuture<ExecutionResult>> onSubmit) {
        this.onSubmit = onSubmit;
        return this;
      }
      /** Instead of overwriting onSubmit like {@link #onSubmit(BiFunction)}, this chains it */
      public Builder andOnSubmit(BiFunction<Config, CompletableFuture<ExecutionResult>,
          CompletableFuture<ExecutionResult>> onSubmit) {
        if (this.onSubmit == null) this.onSubmit = onSubmit;
        else {
          BiFunction<Config, CompletableFuture<ExecutionResult>, CompletableFuture<ExecutionResult>> origOnSubmit =
              this.onSubmit, newOnSubmit = onSubmit;
          this.onSubmit = (config, execRes) -> newOnSubmit.apply(config, origOnSubmit.apply(config, execRes));
        }
        return this;
      }
      /** Just uses {@link #onSubmit(BiFunction)} */
      public Builder onEachResult(Consumer<ExecutionResult> consumer) {
        return onSubmit((config, execResFut) -> execResFut.thenApply(execRes -> {
          consumer.accept(execRes);
          return execRes;
        }));
      }
      /** Just uses {@link #andOnSubmit(BiFunction)} */
      public Builder andOnEachResult(Consumer<ExecutionResult> consumer) {
        return andOnSubmit((config, execResFut) -> execResFut.thenApply(execRes -> {
          consumer.accept(execRes);
          return execRes;
        }));
      }

      /** See {@link #invoker(Invoker)} */
      public Invoker invoker;
      /**
       * The {@link Invoker} to use for executing methods. The default is {@link Invoker.WithExecutorService} using
       * {@link Util.CurrentThreadExecutorService}.
       */
      public Builder invoker(Invoker invoker) {
        this.invoker = invoker;
        return this;
      }
      /** See {@link #invoker(Invoker)} */
      public Invoker invokerDefault() {
        return new Invoker.WithExecutorService(new Util.CurrentThreadExecutorService());
      }

      /** See {@link #tracer(Tracer)} */
      public Tracer tracer;
      /**
       * The {@link Tracer} that keeps track of hit branches. The default is {@link Tracer.Instrumenting}.
       */
      public Builder tracer(Tracer tracer) {
        this.tracer = tracer;
        return this;
      }
      /** See {@link #tracer(Tracer)} */
      public Tracer tracerDefault() { return new Tracer.Instrumenting(); }

      /** See {@link #stopOnFutureFailure(boolean)} */
      public boolean stopOnFutureFailure;
      /**
       * If true, any failure that happens in the future is considered an error in the fuzzer and throws out of
       * {@link Fuzzer#fuzz(AtomicBoolean)}. Note, invocation failure is not considered a future failure. So this only
       * applies if something outside of the invocation fails such as parameter generation or something caused by
       * {@link Config#onSubmit}. The default is false.
       */
      public Builder stopOnFutureFailure(boolean stopOnFutureFailure) {
        this.stopOnFutureFailure = stopOnFutureFailure;
        return this;
      }

      /** See {@link #sleepAfterSubmit(long)} */
      public long sleepAfterSubmit;
      /**
       * The number of milliseconds to sleep after each invocation is started. Sometimes, introducing these kinds of
       * delays can help with debugging the fuzzer. Default is 0.
       */
      public Builder sleepAfterSubmit(long sleepAfterSubmit) {
        this.sleepAfterSubmit = sleepAfterSubmit;
        return this;
      }

      /** Build the config */
      public Config build() {
        return new Config(
            method,
            params,
            onSubmit,
            invoker == null ? invokerDefault() : invoker,
            tracer == null ? tracerDefault() : tracer,
            stopOnFutureFailure,
            sleepAfterSubmit
        );
      }
    }
  }

  /** Base class for all fuzzer exceptions */
  public static class FuzzException extends RuntimeException {
    public FuzzException(String msg) { super(msg); }
    public FuzzException(String msg, Throwable cause) { super(msg, cause); }
    public FuzzException(Throwable cause) { super(cause); }

    /** Thrown if the first run fails on {@link Fuzzer#fuzz(AtomicBoolean)} */
    public static class FirstRunFailed extends FuzzException {
      public FirstRunFailed(Throwable cause) { super(cause); }
    }
  }
}
