package jwp.fuzz;

import java.util.Arrays;
import java.util.Map;

/** Base interface for all tracers. The primary implementation is {@link Instrumenting}. */
public interface Tracer {

  /** Start a trace on the given thread. This should throw if already being traced. */
  void startTrace(Thread thread);

  /**
   * Stop the trace on the given thread. If there is not a trace on the given thread, this should return null. The
   * resulting array from this trace should be sorted using {@link BranchHit}'s set ordering.
   */
  BranchHit[] stopTrace(Thread thread);

  /** Main tracer using instrumenting */
  class Instrumenting implements Tracer {
    @Override
    public void startTrace(Thread thread) {
      BranchTracker.beginTrackingForThread(thread);
    }

    @Override
    public BranchHit[] stopTrace(Thread thread) {
      BranchTracker.BranchHits hits = BranchTracker.endTrackingForThread(thread);
      if (hits == null) return null;
      BranchHit[] ret = new BranchHit[hits.branchHashHits.size()];
      int index = 0;
      for (Map.Entry<Integer, BranchTracker.IntRef> hit : hits.branchHashHits.entrySet()) {
        ret[index++] = new BranchHit(hit.getKey(), hit.getValue().value);
      }
      Arrays.sort(ret);
      return ret;
    }
  }
}
