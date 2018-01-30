package jwp.fuzz;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * A {@link ParamGenerator} for byte arrays. This class follows <a href="http://lcamtuf.coredump.cx/afl/">AFL</a>
 * pretty closely.
 * <p>
 * Here is a high level overview of the algorithm:
 * <ul>
 *   <li>Read next byte array from the input queue or the initial value set on first run</li>
 *   <li>
 *     If it is not the first run but the queue is empty, take the previous one and just run the "random havoc" stage
 *   </li>
 *   <li>Run the byte array over several stages that produce new, manipulated byte array forms</li>
 *   <li>Return that to the executor</li>
 *   <li>On execution result if branch path has never been seen before, enqueue the manipulated array</li>
 *   <li>Re-sort the input queue to put the ones hitting the most unique branch pieces at the top</li>
 * </ul>
 * <p>
 * A {@link Config} object is used to construct the generator
 */
public class ByteArrayParamGenerator implements ParamGenerator<byte[]> {

  /** Helper to convert a byte array generator into a byte buffer generator */
  public static ParamGenerator<ByteBuffer> byteBufferParamGenerator(ParamGenerator<byte[]> gen) {
    return gen.mapNotNull(ByteBuffer::wrap, ByteBuffer::array);
  }

  /**
   * Helper to convert a byte array generator to a char buffer generator using the given charset. If the byte array is
   * not valid for the charset, it is ignored and not emitted.
   */
  public static ParamGenerator<CharBuffer> charBufferParamGenerator(Charset cs, ParamGenerator<byte[]> gen) {
    CharsetDecoder decoder = cs.newDecoder().onMalformedInput(CodingErrorAction.REPORT).
        onUnmappableCharacter(CodingErrorAction.REPORT);
    CharsetEncoder encoder = cs.newEncoder().onMalformedInput(CodingErrorAction.REPORT).
        onUnmappableCharacter(CodingErrorAction.REPORT);
    return byteBufferParamGenerator(gen).mapNotNull(
        byteBuf -> {
          try { return decoder.decode(byteBuf); } catch (CharacterCodingException e) { return null; } },
        charBuf -> {
          try { return encoder.encode(charBuf); } catch (CharacterCodingException e) { throw new RuntimeException(e); }
        }
    );
  }

  /**
   * Helper to convert a byte array generator to a string generator using
   * {@link #charBufferParamGenerator(Charset, ParamGenerator)}
   */
  public static ParamGenerator<String> stringParamGenerator(Charset cs, ParamGenerator<byte[]> gen) {
    return charBufferParamGenerator(cs, gen).mapNotNull(CharBuffer::toString, CharBuffer::wrap);
  }

  /** Config for the generator */
  public final Config config;

  protected final HashCache seenBranchesCache;
  protected final InputQueue inputQueue;
  protected final ByteArrayStage[] stages;

  /**
   * Mutex that should be synchronized on when accessing {@link #startMs}, {@link #queueCycle}, or {@link #lastEntry}
   */
  protected final Object varMutex = new Object();
  protected long startMs = -1L;
  protected long queueCycle = 0;
  protected byte[] lastEntry;

  /** Create a new byte array generator from the given config */
  public ByteArrayParamGenerator(Config config) {
    this.config = config;
    seenBranchesCache = config.hashCacheCreator.apply(config);
    inputQueue = config.inputQueueCreator.apply(config);
    stages = config.stagesCreator.apply(config);
  }

  @Override
  public Iterator<byte[]> iterator() { return stream().iterator(); }

  /** A lazy, infinite stream of byte arrays used for {@link #iterator()} */
  protected Stream<byte[]> stream() {
    if (!config.reuseLastStageAsInfinite) throw new RuntimeException("Only last-stage-infinite is supported for now");
    return Stream.generate(() -> {
      // Set the start time and safely grab the last entry
      byte[] lastEntry;
      synchronized (varMutex) {
        if (startMs < 0) startMs = System.currentTimeMillis();
        lastEntry = this.lastEntry;
      }
      // Try the input queue first. If nothing, try running the infinite stage with the last entry. If there is no
      // last entry, we are at the beginning and we run with the initial values.
      byte[] entry = inputQueue.dequeue();
      if (entry == null && lastEntry != null) return stages[stages.length - 1].apply(this, lastEntry);
      Stream<byte[]> startStream = entry == null ? config.initialValues.stream() : Stream.empty();
      Stream<byte[]> entryStream = entry == null ? config.initialValues.stream() : Stream.of(entry);
      return Stream.concat(startStream, entryStream.flatMap(buf -> {
        // Set the last entry and update the cycle count
        synchronized (varMutex) {
          this.lastEntry = buf;
          queueCycle++;
        }
        // Run each stage for this buf
        return Arrays.stream(stages).flatMap(stage -> stage.apply(this, buf));
      }));
    }).flatMap(Function.identity());
  }

  /** True */
  @Override
  public boolean isInfinite() { return true; }

  /** Puts param on input queue if result has never been seen before */
  @Override
  public void onResult(ExecutionResult result, int myParamIndex, byte[] myParam) {
    // If it's a unique path, then our param goes to the input queue if it's not null
    if (myParam == null) return;
    if (seenBranchesCache.checkUniqueAndStore(config.hasher.hash(result.branchHits))) {
      inputQueue.enqueue(new TestCase(myParam, result.branchHits, result.nanoTime));
    }
  }

  /** Closes the hash cache and the input queue */
  @Override
  public void close() throws Exception {
    try {
      seenBranchesCache.close();
    } finally {
      inputQueue.close();
    }
  }

  /** Used by some {@link RandomHavocTweak}s to obtain a block length to work with */
  public int randomBlockLength(int limit) {
    int rLim, minValue, maxValue;
    synchronized (varMutex) {
      boolean over10Min = startMs > 0 && System.currentTimeMillis() - startMs > 10 * 60 * 1000;
      rLim = over10Min ? (int) Math.min(queueCycle, 3) : 1;
    }
    switch (config.random.nextInt(rLim)) {
      case 0:
        minValue = 1;
        maxValue = config.havocBlockSmall;
        break;
      case 1:
        minValue = config.havocBlockSmall;
        maxValue = config.havocBlockMedium;
        break;
      default:
        if (config.random.nextInt(10) == 0) {
          minValue = config.havocBlockLarge;
          maxValue = config.havocBlockXLarge;
        } else {
          minValue = config.havocBlockMedium;
          maxValue = config.havocBlockXLarge;
        }
    }
    if (minValue >= limit) minValue = 1;
    return minValue + config.random.nextInt(Math.min(maxValue, limit) - minValue + 1);
  }

  /** The config for a byte array generator. For defaults and easy use, use {@link #builder()} */
  public static class Config {
    /** Helper for building the config */
    public static Builder builder() { return new Builder(); }

    /** See {@link Builder#initialValues(List)} */
    public final List<byte[]> initialValues;
    /** See {@link Builder#dictionary(List)} */
    public final List<byte[]> dictionary;
    /** See {@link Builder#hasher(BranchHit.Hasher)} */
    public final BranchHit.Hasher hasher;
    /** See {@link Builder#hashCacheCreator(Function)} */
    public final Function<Config, HashCache> hashCacheCreator;
    /** See {@link Builder#inputQueueCreator} */
    public final Function<Config, InputQueue> inputQueueCreator;
    /** See {@link Builder#stagesCreator(Function)} */
    public final Function<Config, ByteArrayStage[]> stagesCreator;
    /** See {@link Builder#havocTweaksCreator(Function)} */
    public final Function<Config, RandomHavocTweak[]> havocTweaksCreator;
    /** See {@link Builder#random(Random)} */
    public final Random random;
    /** See {@link Builder#reuseLastStageAsInfinite(Boolean)} */
    public final boolean reuseLastStageAsInfinite;
    /** See {@link Builder#arithMax} */
    public final int arithMax;
    /** See {@link Builder#havocCycles} */
    public final int havocCycles;
    /** See {@link Builder#havocStackPower} */
    public final int havocStackPower;
    /** See {@link Builder#havocBlockSmall} */
    public final int havocBlockSmall;
    /** See {@link Builder#havocBlockMedium} */
    public final int havocBlockMedium;
    /** See {@link Builder#havocBlockLarge} */
    public final int havocBlockLarge;
    /** See {@link Builder#havocBlockXLarge} */
    public final int havocBlockXLarge;
    /** See {@link Builder#maxInput} */
    public final int maxInput;

    public Config(List<byte[]> initialValues, List<byte[]> dictionary, BranchHit.Hasher hasher,
        Function<Config, HashCache> hashCacheCreator, Function<Config, InputQueue> inputQueueCreator,
        Function<Config, ByteArrayStage[]> stagesCreator, Function<Config, RandomHavocTweak[]> havocTweaksCreator,
        Random random, boolean reuseLastStageAsInfinite,
        int arithMax, int havocCycles, int havocStackPower, int havocBlockSmall, int havocBlockMedium,
        int havocBlockLarge, int havocBlockXLarge, int maxInput) {
      this.initialValues = Objects.requireNonNull(initialValues);
      // Copy the dictionary and sort it smallest first
      this.dictionary = new ArrayList<>(Objects.requireNonNull(dictionary));
      this.dictionary.sort(Comparator.comparingInt(b -> b.length));
      this.hasher = Objects.requireNonNull(hasher);
      this.hashCacheCreator = Objects.requireNonNull(hashCacheCreator);
      this.inputQueueCreator = Objects.requireNonNull(inputQueueCreator);
      this.stagesCreator = Objects.requireNonNull(stagesCreator);
      this.havocTweaksCreator = Objects.requireNonNull(havocTweaksCreator);
      this.random = Objects.requireNonNull(random);
      this.reuseLastStageAsInfinite = reuseLastStageAsInfinite;
      this.arithMax = arithMax;
      this.havocCycles = havocCycles;
      this.havocStackPower = havocStackPower;
      this.havocBlockSmall = havocBlockSmall;
      this.havocBlockMedium = havocBlockMedium;
      this.havocBlockLarge = havocBlockLarge;
      this.havocBlockXLarge = havocBlockXLarge;
      this.maxInput = maxInput;
    }

    /**
     * Builder for {@link Config}. User is recommended to at least set {@link #initialValues(List)} and
     * {@link #dictionary(List)}
     */
    public static class Builder {
      public static final int ARITH_MAX_DEFAULT = 35;
      public static final int HAVOC_CYCLES_DEFAULT = 1024;
      public static final int HAVOC_STACK_POWER_DEFAULT = 7;
      public static final int HAVOC_BLOCK_SMALL_DEFAULT = 32;
      public static final int HAVOC_BLOCK_MEDIUM_DEFAULT = 128;
      public static final int HAVOC_BLOCK_LARGE_DEFAULT = 1500;
      public static final int HAVOC_BLOCK_XLARGE_DEFAULT = 32768;
      public static final int MAX_INPUT_DEFAULT = 1024 * 1024;

      /** When doing arithmetic runs, loop from negative this value to positive. Default {@value ARITH_MAX_DEFAULT} */
      public int arithMax = ARITH_MAX_DEFAULT;
      /** Number of byte arrays generated by each random havoc stage. Default {@value HAVOC_CYCLES_DEFAULT} */
      public int havocCycles = HAVOC_CYCLES_DEFAULT;
      /**
       * Number of random manips for each byte array, calc'd as:
       * <code>pow(2, 1 + random.nextInt(havocStackPower))</code>. Default {@value HAVOC_STACK_POWER_DEFAULT}
       */
      public int havocStackPower = HAVOC_STACK_POWER_DEFAULT;
      /** Small block for random havoc. Default {@value HAVOC_BLOCK_SMALL_DEFAULT} */
      public int havocBlockSmall = HAVOC_BLOCK_SMALL_DEFAULT;
      /** Medium block for random havoc. Default {@value HAVOC_BLOCK_MEDIUM_DEFAULT} */
      public int havocBlockMedium = HAVOC_BLOCK_MEDIUM_DEFAULT;
      /** Large block for random havoc. Default {@value HAVOC_BLOCK_LARGE_DEFAULT} */
      public int havocBlockLarge = HAVOC_BLOCK_LARGE_DEFAULT;
      /** Extra large block for random havoc. Default {@value HAVOC_BLOCK_XLARGE_DEFAULT} */
      public int havocBlockXLarge = HAVOC_BLOCK_XLARGE_DEFAULT;
      /** Maximum amount of bytes that random havoc cannot go over. Default {@value MAX_INPUT_DEFAULT} */
      public int maxInput = MAX_INPUT_DEFAULT;

      /** See {@link #initialValues(List)} */
      public List<byte[]> initialValues;
      /**
       * The initial set of bytes to work from before using input queue. This should not be empty. Default is a single
       * value of "test".
       */
      public Builder initialValues(List<byte[]> initialValues) {
        this.initialValues = initialValues;
        return this;
      }
      /** See {@link #initialValues(List)} */
      public List<byte[]> initialValuesDefault() { return Collections.singletonList("test".getBytes()); }

      /** See {@link #dictionary(List)} */
      public List<byte[]> dictionary;
      /**
       * List of byte arrays that can help the generator generate new branches. They should be a collection of
       * "keywords" or other small terms. Default is an empty list.
       */
      public Builder dictionary(List<byte[]> dictionary) {
        this.dictionary = dictionary;
        return this;
      }
      /** See {@link #dictionary(List)} */
      public List<byte[]> dictionaryDefault() { return Collections.emptyList(); }

      /** See {@link #hasher(BranchHit.Hasher)} */
      public BranchHit.Hasher hasher;
      /**
       * Hasher that is used by the byte array generator to determine branch uniqueness. Default is
       * {@link BranchHit.Hasher#WITH_HIT_COUNTS}.
       */
      public Builder hasher(BranchHit.Hasher hasher) {
        this.hasher = hasher;
        return this;
      }
      /** See {@link #hasher(BranchHit.Hasher)} */
      public BranchHit.Hasher hasherDefault() { return BranchHit.Hasher.WITH_HIT_COUNTS; }

      /** See {@link #hashCacheCreator(Function)} */
      public Function<Config, HashCache> hashCacheCreator;
      /**
       * Function to create the {@link HashCache} to use for storing seen hash values. Default is
       * {@link HashCache.SetBacked}.
       */
      public Builder hashCacheCreator(Function<Config, HashCache> hashCacheCreator) {
        this.hashCacheCreator = hashCacheCreator;
        return this;
      }
      /** See {@link #hashCacheCreator(Function)} */
      public Function<Config, HashCache> hashCacheCreatorDefault() {
        return c -> new HashCache.SetBacked();
      }

      /** See {@link #inputQueueCreator(Function)} */
      public Function<Config, InputQueue> inputQueueCreator;
      /**
       * Function to create the {@link InputQueue} for queueing byte arrays. Default is {@link InputQueue.ListBacked}
       * with an {@link ArrayList} and {@link Config#hasher}.
       */
      public Builder inputQueueCreator(Function<Config, InputQueue> inputQueueCreator) {
        this.inputQueueCreator = inputQueueCreator;
        return this;
      }
      /** See {@link #inputQueueCreator(Function)} */
      public Function<Config, InputQueue> inputQueueCreatorDefault() {
        return c -> new InputQueue.ListBacked(new ArrayList<>(), c.hasher);
      }

      /** See {@link #stagesCreator(Function)} */
      public Function<Config, ByteArrayStage[]> stagesCreator;
      /**
       * Function to create the array of {@link ByteArrayStage}s for the generator. Default is the recommended set.
       */
      public Builder stagesCreator(Function<Config, ByteArrayStage[]> stagesCreator) {
        this.stagesCreator = stagesCreator;
        return this;
      }
      /** See {@link #stagesCreator(Function)} */
      public Function<Config, ByteArrayStage[]> stagesCreatorDefault() {
        return config -> new ByteArrayStage[] {
            new ByteArrayStage.FlipBits(1),
            new ByteArrayStage.FlipBits(2),
            new ByteArrayStage.FlipBits(4),
            new ByteArrayStage.FlipBytes(1),
            new ByteArrayStage.FlipBytes(2),
            new ByteArrayStage.FlipBytes(4),
            new ByteArrayStage.Arith8(),
            new ByteArrayStage.Arith16(),
            new ByteArrayStage.Arith32(),
            new ByteArrayStage.Interesting8(),
            new ByteArrayStage.Interesting16(),
            new ByteArrayStage.Interesting32(),
            new ByteArrayStage.OverwriteWithDictionary(),
            new ByteArrayStage.InsertWithDictionary(),
            // TODO: auto extras
            new ByteArrayStage.RandomHavoc(config.havocTweaksCreator.apply(config))
        };
      }

      /** See {@link #havocTweaksCreator(Function)} */
      public Function<Config, RandomHavocTweak[]> havocTweaksCreator;
      /**
       * Function to create the array of {@link RandomHavocTweak}s for use by the {@link ByteArrayStage.RandomHavoc}
       * stage of the generator (when using the defaults for {@link #stagesCreator(Function)}). Default is the
       * recommended set.
       */
      public Builder havocTweaksCreator(Function<Config, RandomHavocTweak[]> havocTweaksCreator) {
        this.havocTweaksCreator = havocTweaksCreator;
        return this;
      }
      /** See {@link #havocTweaksCreator(Function)} */
      public Function<Config, RandomHavocTweak[]> havocTweaksCreatorDefault() {
        return config -> {
          List<RandomHavocTweak> tweaks = new ArrayList<>(Arrays.asList(
              new RandomHavocTweak.FlipSingleBit(),
              new RandomHavocTweak.InterestingByte(),
              new RandomHavocTweak.InterestingShort(),
              new RandomHavocTweak.InterestingInt(),
              new RandomHavocTweak.SubtractFromByte(),
              new RandomHavocTweak.AddToByte(),
              new RandomHavocTweak.SubtractFromShort(),
              new RandomHavocTweak.AddToShort(),
              new RandomHavocTweak.SubtractFromInt(),
              new RandomHavocTweak.AddToInt(),
              new RandomHavocTweak.SetRandomByte(),
              new RandomHavocTweak.DeleteBytes(),
              new RandomHavocTweak.DeleteBytes(),
              new RandomHavocTweak.CloneOrInsertBytes(),
              new RandomHavocTweak.OverwriteRandomOrFixedBytes()
          ));
          if (!config.dictionary.isEmpty()) {
            tweaks.add(new RandomHavocTweak.OverwriteWithDictionary());
            tweaks.add(new RandomHavocTweak.InsertWithDictionary());
          }
          return tweaks.toArray(new RandomHavocTweak[tweaks.size()]);
        };
      }

      /** See {@link #random(Random)} */
      public Random random;
      /**
       * Which {@link Random} to use for the {@link ByteArrayStage.RandomHavoc} stage. Default is
       * {@link Random#Random()}.
       */
      public Builder random(Random random) {
        this.random = random;
        return this;
      }
      /** See {@link #random(Random)} */
      public Random randomDefault() { return new Random(); }

      /** See {@link #reuseLastStageAsInfinite(Boolean)} */
      public Boolean reuseLastStageAsInfinite;
      /**
       * Whether, when the queue is empty and it's not the first run, to use the previous entry and the last stage
       * (which is {@link ByteArrayStage.RandomHavoc} by default) over and over. Default is true.
       */
      public Builder reuseLastStageAsInfinite(Boolean reuseLastStageAsInfinite) {
        this.reuseLastStageAsInfinite = reuseLastStageAsInfinite;
        return this;
      }
      /** See {@link #reuseLastStageAsInfinite(Boolean)} */
      public boolean reuseLastStageAsInfiniteDefault() { return true; }

      /** Build the actual config, using defaults for anything not explicitly set */
      public Config build() {
        return new Config(
            initialValues == null ? initialValuesDefault() : initialValues,
            dictionary == null ? dictionaryDefault() : dictionary,
            hasher == null ? hasherDefault() : hasher,
            hashCacheCreator == null ? hashCacheCreatorDefault() : hashCacheCreator,
            inputQueueCreator == null ? inputQueueCreatorDefault() : inputQueueCreator,
            stagesCreator == null ? stagesCreatorDefault() : stagesCreator,
            havocTweaksCreator == null ? havocTweaksCreatorDefault() : havocTweaksCreator,
            random == null ? randomDefault() : random,
            reuseLastStageAsInfinite == null ? reuseLastStageAsInfiniteDefault() : reuseLastStageAsInfinite,
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

  /** Simple interface for storing hashes as ints and determining if they have been stored before. */
  public interface HashCache extends AutoCloseable {

    /**
     * Store the given hash and return true if it hasn't been stored before. Otherwise, return false. Note, this should
     * be thread safe as it can be called by multiple threads simultaneously.
     */
    boolean checkUniqueAndStore(int hash);

    /** A {@link ByteArrayParamGenerator.HashCache} backed by a thread-safe {@link Set} */
    class SetBacked implements HashCache {
      /** The set being used */
      public final Set<Integer> backingSet;

      /** Create a cache backed by a set made from a {@link ConcurrentHashMap} */
      public SetBacked() {
        this(Collections.newSetFromMap(new ConcurrentHashMap<>()), true);
      }

      /**
       * Create a cache backed by the given set. If alreadySynchronized is false, it is wrapped with
       * {@link Collections#synchronizedSet(java.util.Set)}
       */
      public SetBacked(Set<Integer> backingSet, boolean alreadySynchronized) {
        this.backingSet = alreadySynchronized ? backingSet : Collections.synchronizedSet(backingSet);
      }

      @Override
      public boolean checkUniqueAndStore(int hash) { return backingSet.add(hash); }

      @Override
      public void close() { }
    }
  }

  /**
   * Interface for a byte array queue. Implementors are expected to keep the queue in sorted order where, when
   * {@link #dequeue()} returns the {@link TestCase} with the best {@link TestCase#score} and have at least one
   * non-duplicate branch hash. See {@link ListBacked#cull()} for details.
   */
  public interface InputQueue extends AutoCloseable {

    /**
     * Enqueue a completed test case. This should be thread-safe as it can be called by multiple threads simultaneously.
     * This should never block. The queue should be unbounded, but an exception can be thrown in a worst-case scenario.
     */
    void enqueue(TestCase entry);

    /**
     * Remove and return the test case with the best score and has at least one non-duplicate branch hash (see
     * {@link ListBacked#cull()} for details). This should be thread-safe as it can be called by multiple threads
     * simultaneously. This should never block. Return null if the queue is empty.
     */
    byte[] dequeue();

    /**
     * A thread-safe implementation backed by a {@link List}. Note, this thrashes the list quite a bit (i.e. calling
     * {@link List#sort(Comparator)} + {@link List#clear()} + {@link List#addAll(Collection)}) during {@link #cull()}
     * calls. The list should be optimized for those calls.
     */
    class ListBacked implements InputQueue {
      /** The queue. This should never be accessed without being synchronized on first. */
      public final List<TestCase> queue;
      /** The hasher to determine branch uniqueness for {@link #cull()} */
      public final BranchHit.Hasher hasher;

      private boolean enqueuedSinceLastDequeued = false;

      /**
       * Create a list backed input queue from the given list and hasher. The list does not need to be thread-safe as
       * all accesses are synchronized. See the class docs on {@link ListBacked} for information on how the list is
       * constantly updated/culled.
       */
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

      /**
       * This culls {@link #queue}. The caller should have synchronized on the {@link #queue} before calling this. By
       * default, this is called from {@link #dequeue()} if enqueue(TestCase) has been called since last dequeue.
       * <p>
       * The goal of the culling process is to sort the queue where the top items ran the quickest with the smallest
       * input and contain a first-seen single branch. This is done by first sorting the queue by the
       * {@link TestCase#score} (which is byte len + nanoseconds ran). Then going over each item in the list and
       * keeping up-front the ones that have a branch we haven't seen during this list iteration.
       * <p>
       * Unfortunately, since it is a two-step sorting and can completely change when a new case is added, we can't use
       * something like a {@link PriorityQueue}. The sort has to be re-run after any mutation before dequeuing.
       */
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

  /** An input queue test case */
  public static class TestCase {
    /** The bytes for this test case */
    public final byte[] bytes;
    // Can be null if not result of execution
    /** All branch hits when it was executed */
    public final BranchHit[] branchHits;
    /** The number of nanos the execution took */
    public final long nanoTime;
    /** The score of the test case which is bytes * nanos */
    public final long score;

    public TestCase(byte[] bytes, BranchHit[] branchHits, long nanoTime) {
      this.bytes = bytes;
      this.branchHits = branchHits;
      this.nanoTime = nanoTime;
      score = bytes.length * nanoTime;
    }
  }
}
