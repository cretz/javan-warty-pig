package jwp.fuzz;

import java.util.Arrays;
import java.util.Objects;

public class BranchHit {
  public final int branchHash;
  public final int hitCount;
  public final int withHitCountHash;

  public BranchHit(int branchHash, int hitCount) {
    this.branchHash = branchHash;
    this.hitCount = hitCount;
    withHitCountHash = Objects.hash(branchHash, hitBucket());
  }

  public int hitBucket() {
    if (hitCount < 4) return hitCount;
    if (hitCount < 8) return 4;
    if (hitCount < 16) return 8;
    if (hitCount < 32) return 16;
    if (hitCount < 128) return 32;
    return 128;
  }

  @FunctionalInterface
  public interface Hasher {
    Hasher WITH_HIT_COUNTS = hit -> hit.withHitCountHash;
    Hasher WITHOUT_HIT_COUNTS = hit -> hit.branchHash;

    int hash(BranchHit hit);

    default int hash(BranchHit... hits) {
      int[] hashes = new int[hits.length];
      for (int i = 0; i < hits.length; i++) hashes[i] = hash(hits[i]);
      return Arrays.hashCode(hashes);
    }
  }
}
