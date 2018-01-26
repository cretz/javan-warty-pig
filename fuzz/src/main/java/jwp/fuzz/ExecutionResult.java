package jwp.fuzz;

import java.lang.reflect.Method;

public class ExecutionResult {
  public final Method method;
  public final Object[] params;
  public final BranchHit[] branchHits;
  public final long nanoTime;
  public final Object result;
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
