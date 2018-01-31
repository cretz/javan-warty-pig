package jwp.fuzz;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Objects;

/** Representation of a single branching operation */
public class BranchHit implements Comparable<BranchHit> {

  /** The hash unique to this branch */
  public final int branchHash;

  /** The number of times the branch was executed */
  public final int hitCount;

  /** The hash of {@link #branchHash} and {@link #hitBucket()} */
  public final int withHitCountHash;

  public BranchHit(int branchHash, int hitCount) {
    this.branchHash = branchHash;
    this.hitCount = hitCount;
    withHitCountHash = Objects.hash(branchHash, hitBucket());
  }

  /** Returns the number of hits bucketed into a value of 1, 2, 3, 4, 8, 16, 32, or 128 */
  public int hitBucket() {
    if (hitCount < 4) return hitCount;
    if (hitCount < 8) return 4;
    if (hitCount < 16) return 8;
    if (hitCount < 32) return 16;
    if (hitCount < 128) return 32;
    return 128;
  }

  /** Compares using {@link #branchHash} */
  @Override
  public int compareTo(BranchHit o) {
    // Hit counts don't factor in because they don't affect uniqueness
    return o == null ? -1 : Integer.compare(branchHash, o.branchHash);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    BranchHit branchHit = (BranchHit) o;
    return branchHash == branchHit.branchHash &&
        hitCount == branchHit.hitCount &&
        withHitCountHash == branchHit.withHitCountHash;
  }

  @Override
  public int hashCode() {
    return Objects.hash(branchHash, hitCount, withHitCountHash);
  }

  /** Interface for hashing {@link BranchHit}s */
  @FunctionalInterface
  public interface Hasher {
    /** Uses {@link BranchHit#withHitCountHash} */
    Hasher WITH_HIT_COUNTS = hit -> hit.withHitCountHash;
    /** Uses {@link BranchHit#branchHash} */
    Hasher WITHOUT_HIT_COUNTS = hit -> hit.branchHash;

    /** Create unique hash for the given branch */
    int hash(BranchHit hit);

    /**
     * Create single hash for all given branches together. The parameter is expected to be sorted by the caller before
     * invoking.
     *
     * The default implementation just uses {@link #hash(BranchHit)} and then {@link Arrays#hashCode(int[])}
     */
    default int hash(BranchHit... hits) {
      int[] hashes = new int[hits.length];
      for (int i = 0; i < hits.length; i++) hashes[i] = hash(hits[i]);
      return Arrays.hashCode(hashes);
    }
  }
}
