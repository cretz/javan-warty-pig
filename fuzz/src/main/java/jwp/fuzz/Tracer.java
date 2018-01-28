package jwp.fuzz;

import java.util.Arrays;
import java.util.Map;

public interface Tracer {

  void startTrace(Thread thread);

  // This should never throw! The result should be sorted.
  BranchHit[] stopTrace(Thread thread);

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
