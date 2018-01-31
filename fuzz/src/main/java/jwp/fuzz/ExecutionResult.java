package jwp.fuzz;

import java.lang.reflect.Method;

/** The result of an execution */
public class ExecutionResult {
  /** The reflected method that was called */
  public final Method method;
  /** The parameter array that was used */
  public final Object[] params;
  /** All of the branches hit during the invocation. This is expected to be sorted. */
  public final BranchHit[] branchHits;
  /** The number of nanos it took to execute the method */
  public final long nanoTime;
  /**
   * The result of the invocation. This can be null if the result was null or there was an exception, so
   * {@link #exception}'s nullness should be what is used to determine success or fail.
   */
  public final Object result;
  /**
   * The exception thrown during this invocation if any. If no exception occurred, this is null. Therefore, whether this
   * is null or not should be used to determine success or failure.
   */
  public final Throwable exception;

  public ExecutionResult(Method method, Object[] params, BranchHit[] branchHits, long nanoTime, Object result) {
    this(method, params, branchHits, nanoTime, result, null);
  }

  public ExecutionResult(Method method, Object[] params, BranchHit[] branchHits, long nanoTime, Throwable exception) {
    this(method, params, branchHits, nanoTime, null, exception);
  }

  private ExecutionResult(Method method, Object[] params, BranchHit[] branchHits,
      long nanoTime, Object result, Throwable exception) {
    this.method = method;
    this.params = params;
    this.branchHits = branchHits;
    this.nanoTime = nanoTime;
    this.result = result;
    this.exception = exception;
  }
}
