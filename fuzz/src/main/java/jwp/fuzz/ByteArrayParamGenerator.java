package jwp.fuzz;

import java.io.Closeable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import static jwp.fuzz.Util.*;

public class ByteArrayParamGenerator implements ParamGenerator<byte[]> {

  public final Config config;

  public ByteArrayParamGenerator(Config config) {
    this.config = config;
  }

  @Override
  public Iterator<byte[]> iterator() {
    return null;
  }

  @Override
  public void close() {

  }

  public Stream<byte[]> stages(byte[] buf) {
    return Stream.of(
        stageFlipBits(buf, 1),
        stageFlipBits(buf, 2),
        stageFlipBits(buf, 4),
        stageFlipBytes(buf, 1),
        stageFlipBytes(buf, 2),
        stageFlipBytes(buf, 4)
        // TODO: more
    ).flatMap(Function.identity());
  }

  public Stream<byte[]> stageFlipBits(byte[] buf, int consecutiveToFlip) {
    return IntStream.range(0, (buf.length * 8) - (consecutiveToFlip - 1)).boxed().map(bitIndex ->
        withCopiedBytes(buf, bytes -> {
          for (int i = 0; i < consecutiveToFlip; i++) flipBit(bytes, bitIndex + i);
        })
    );
  }

  public Stream<byte[]> stageFlipBytes(byte[] buf, int consecutiveToFlip) {
    return IntStream.range(0, buf.length - (consecutiveToFlip - 1)).boxed().map(byteIndex ->
        withCopiedBytes(buf, bytes -> {
          for (int i = 0; i < consecutiveToFlip; i++) bytes[byteIndex + i] = (byte) ~bytes[byteIndex + i];
        })
    );
  }

  public Stream<byte[]> stageArith8(byte[] buf) {
    return IntStream.range(0, buf.length).boxed().flatMap(byteIndex ->
        IntStream.rangeClosed(-config.arithMax, config.arithMax).mapToObj(arithVal -> {
          byte newByte = (byte) (buf[byteIndex] + arithVal);
          if (couldHaveBitFlippedTo(buf[byteIndex], newByte)) return null;
          return withCopiedBytes(buf, bytes -> bytes[byteIndex] = newByte);
        }).filter(Objects::nonNull)
    );
  }

  public Stream<byte[]> stageArith16(byte[] buf) {
    BiPredicate<Short, Short> affectsBothBytes = (origShort, newShort) ->
        byte0(origShort) != byte0(newShort) && byte1(origShort) != byte1(newShort);
    return IntStream.range(0, buf.length - 1).boxed().flatMap(byteIndex -> {
      short origLe = getShortLe(buf, byteIndex);
      short origBe = getShortBe(buf, byteIndex);
      return IntStream.rangeClosed(-config.arithMax, config.arithMax).boxed().flatMap(arithVal -> {
        short newLe = (short) (origLe + arithVal);
        byte[] leBytes = null;
        if (affectsBothBytes.test(origLe, newLe) && !couldHaveBitFlippedTo(origLe, newLe))
          leBytes = withCopiedBytes(buf, arr -> putShortLe(arr, byteIndex, newLe));
        short newBe = (short) (origBe + arithVal);
        byte[] beBytes = null;
        if (affectsBothBytes.test(origBe, newBe) && !couldHaveBitFlippedTo(origLe, endianSwapped(newBe)))
          beBytes = withCopiedBytes(buf, arr -> putShortBe(arr, byteIndex, newBe));
        return streamOfNotNull(leBytes, beBytes);
      });
    });
  }

  public Stream<byte[]> stageArith32(byte[] buf) {
    BiPredicate<Integer, Integer> affectsMoreThanTwoBytes = (origInt, newInt) ->
        (byte0(origInt) == byte0(newInt) ? 0 : 1) +
        (byte1(origInt) == byte1(newInt) ? 0 : 1) +
        (byte2(origInt) == byte2(newInt) ? 0 : 1) +
        (byte3(origInt) == byte3(newInt) ? 0 : 1) > 2;
    return IntStream.range(0, buf.length - 3).boxed().flatMap(byteIndex -> {
      int origLe = getIntLe(buf, byteIndex);
      int origBe = getIntBe(buf, byteIndex);
      return IntStream.rangeClosed(-config.arithMax, config.arithMax).boxed().flatMap(arithVal -> {
        int newLe = origLe + arithVal;
        byte[] leBytes = null;
        if (affectsMoreThanTwoBytes.test(origLe, newLe) && !couldHaveBitFlippedTo(origLe, newLe))
          leBytes = withCopiedBytes(buf, arr -> putIntLe(arr, byteIndex, newLe));
        int newBe = origBe + arithVal;
        byte[] beBytes = null;
        if (affectsMoreThanTwoBytes.test(origBe, newBe) && !couldHaveBitFlippedTo(origLe, endianSwapped(newBe)))
          beBytes = withCopiedBytes(buf, arr -> putIntBe(arr, byteIndex, newBe));
        return streamOfNotNull(leBytes, beBytes);
      });
    });
  }

  public static class Config {
    public final List<byte[]> initialValues;
    public final List<byte[]> dictionary;
    public final BranchHit.Hasher hasher;
    public final Function<Config, HashCache> hashCacheCreator;
    public final Function<Config, InputQueue> inputQueueCreator;
    public final int arithMax;
    public final int havocCycles;
    public final int havocStackPower;
    public final int havocBlockSmall;
    public final int havocBlockMedium;
    public final int havocBlockLarge;
    public final int havocBlockXLarge;
    public final int maxInput;

    public Config(List<byte[]> initialValues, List<byte[]> dictionary, BranchHit.Hasher hasher,
        Function<Config, HashCache> hashCacheCreator, Function<Config, InputQueue> inputQueueCreator, int arithMax,
        int havocCycles, int havocStackPower, int havocBlockSmall, int havocBlockMedium, int havocBlockLarge,
        int havocBlockXLarge, int maxInput) {
      this.initialValues = Objects.requireNonNull(initialValues);
      this.dictionary = Objects.requireNonNull(dictionary);
      this.hasher = Objects.requireNonNull(hasher);
      this.hashCacheCreator = Objects.requireNonNull(hashCacheCreator);
      this.inputQueueCreator = Objects.requireNonNull(inputQueueCreator);
      this.arithMax = arithMax;
      this.havocCycles = havocCycles;
      this.havocStackPower = havocStackPower;
      this.havocBlockSmall = havocBlockSmall;
      this.havocBlockMedium = havocBlockMedium;
      this.havocBlockLarge = havocBlockLarge;
      this.havocBlockXLarge = havocBlockXLarge;
      this.maxInput = maxInput;
    }

    public Builder builder() { return new Builder(); }

    public static class Builder {
      // Consts that aren't usually changed
      public int arithMax = 35;
      public int havocCycles = 1024;
      public int havocStackPower = 7;
      public int havocBlockSmall = 32;
      public int havocBlockMedium = 128;
      public int havocBlockLarge = 1500;
      public int havocBlockXLarge = 32768;
      public int maxInput = 1024 * 1024;

      public List<byte[]> initialValues;
      public Builder initialValues(List<byte[]> initialValues) {
        this.initialValues = initialValues;
        return this;
      }
      public List<byte[]> initialValuesDefault() { return Collections.singletonList("test".getBytes()); }

      public List<byte[]> dictionary;
      public Builder dictionary(List<byte[]> dictionary) {
        this.dictionary = dictionary;
        return this;
      }
      public List<byte[]> dictionaryDefault() { return Collections.emptyList(); }

      public BranchHit.Hasher hasher;
      public Builder hasher(BranchHit.Hasher hasher) {
        this.hasher = hasher;
        return this;
      }
      public BranchHit.Hasher hasherDefault() { return BranchHit.Hasher.WITH_HIT_COUNTS; }

      public Function<Config, HashCache> hashCacheCreator;
      public Builder hashCacheCreator(Function<Config, HashCache> hashCacheCreator) {
        this.hashCacheCreator = hashCacheCreator;
        return this;
      }
      public Function<Config, HashCache> hashCacheCreatorDefault() {
        return c -> new HashCache.SetBacked();
      }

      public Function<Config, InputQueue> inputQueueCreator;
      public Builder inputQueueCreator(Function<Config, InputQueue> inputQueueCreator) {
        this.inputQueueCreator = inputQueueCreator;
        return this;
      }
      public Function<Config, InputQueue> inputQueueCreatorDefault() {
        return c -> new InputQueue.ListBacked(new ArrayList<>(), c.hasher);
      }

      public Config build() {
        return new Config(
            initialValues == null ? initialValuesDefault() : initialValues,
            dictionary == null ? dictionaryDefault() : dictionary,
            hasher == null ? hasherDefault() : hasher,
            hashCacheCreator == null ? hashCacheCreatorDefault() : hashCacheCreator,
            inputQueueCreator == null ? inputQueueCreatorDefault() : inputQueueCreator,
            arithMax,
            havocCycles,
            havocStackPower,
            havocBlockSmall,
            havocBlockMedium,
            havocBlockLarge,
            havocBlockXLarge,
            maxInput
        );
      }
    }
  }

  public interface HashCache extends Closeable {
    // May be called by multiple threads
    boolean checkUniqueAndStore(int hash);

    class SetBacked implements HashCache {
      public final Set<Integer> backingSet;

      public SetBacked() {
        this(Collections.newSetFromMap(new ConcurrentHashMap<>()), true);
      }

      public SetBacked(Set<Integer> backingSet, boolean alreadySynchronized) {
        this.backingSet = alreadySynchronized ? backingSet : Collections.synchronizedSet(backingSet);
      }

      @Override
      public boolean checkUniqueAndStore(int hash) { return backingSet.add(hash); }

      @Override
      public void close() { }
    }
  }

  public interface InputQueue extends Closeable {
    // Must be thread safe and never block. Should be unbounded, but throw in worst-case scenario.
    void enqueue(TestCase entry);

    // Must be thread safe and never block. Return null if empty.
    byte[] dequeue();

    class ListBacked implements InputQueue {
      public final List<TestCase> queue;
      public final BranchHit.Hasher hasher;

      private boolean enqueuedSinceLastDequeued = false;

      public ListBacked(List<TestCase> queue, BranchHit.Hasher hasher) {
        this.queue = queue;
        this.hasher = hasher;
      }

      @Override
      public void enqueue(TestCase entry) {
        synchronized (queue) {
          queue.add(entry);
          enqueuedSinceLastDequeued = true;
        }
      }

      @Override
      public byte[] dequeue() {
        synchronized (queue) {
          // Cull if there have been some enqueued since
          if (enqueuedSinceLastDequeued) {
            enqueuedSinceLastDequeued = false;
            cull();
          }
          return queue.isEmpty() ? null : queue.remove(0).bytes;
        }
      }

      @Override
      public void close() { }

      // Guaranteed to have lock on queue when called
      protected void cull() {
        // Need test cases sorted by score
        queue.sort(Comparator.comparingLong(t -> t.score));
        // Now go over each, moving to the front ones that have branches we haven't seen
        Set<Integer> seenBranchHashes = new HashSet<>();
        List<TestCase> updated = new ArrayList<>(queue.size());
        int indexOfLastMovedForward = 0;
        for (TestCase entry : queue) {
          boolean foundNewHash = false;
          for (int i = 0; i < entry.branchHits.length; i++) {
            // Any new hash means move the item
            if (seenBranchHashes.add(hasher.hash(entry.branchHits[i])) && !foundNewHash) foundNewHash = true;
          }
          if (foundNewHash) updated.add(indexOfLastMovedForward++, entry); else updated.add(entry);
        }
        queue.clear();
        queue.addAll(updated);
      }
    }
  }

  public static class TestCase {
    public final byte[] bytes;
    // Can be null if not result of execution
    public final BranchHit[] branchHits;
    // Will be -1 if not result of execution
    public final long nanoTime;
    // Will be -1 if not result of execution
    public final long score;

    public TestCase(byte[] bytes) {
      this(bytes, null, -1);
    }

    public TestCase(byte[] bytes, BranchHit[] branchHits, long nanoTime) {
      this.bytes = bytes;
      this.branchHits = branchHits;
      this.nanoTime = nanoTime;
      score = branchHits == null ? 0 : bytes.length * nanoTime;
    }
  }

  public abstract static class ByteArraySpliterator extends Spliterators.AbstractSpliterator<byte[]> {

    public static ByteArraySpliterator of(long est, Supplier<byte[]> supplier) {
      return new ByteArraySpliterator(est) {
        @Override
        public boolean tryAdvance(Consumer<? super byte[]> action) {
          byte[] bytes = supplier.get();
          if (bytes == null) return false;
          action.accept(bytes);
          return true;
        }
      };
    }

    public ByteArraySpliterator(long est) {
      this(est, Spliterator.IMMUTABLE | Spliterator.NONNULL);
    }

    public ByteArraySpliterator(long est, int additionalCharacteristics) {
      super(est, additionalCharacteristics);
    }
  }
}
