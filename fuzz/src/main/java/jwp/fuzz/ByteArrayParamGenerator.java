package jwp.fuzz;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Stream;

public class ByteArrayParamGenerator implements ParamGenerator<byte[]> {

  public static ParamGenerator<ByteBuffer> byteBufferParamGenerator(ParamGenerator<byte[]> gen) {
    return gen.mapNotNull(ByteBuffer::wrap, ByteBuffer::array);
  }

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

  public static ParamGenerator<String> stringParamGenerator(Charset cs, ParamGenerator<byte[]> gen) {
    return charBufferParamGenerator(cs, gen).mapNotNull(CharBuffer::toString, CharBuffer::wrap);
  }

  public final Config config;

  protected final HashCache seenBranchesCache;
  protected final InputQueue inputQueue;
  protected final ByteArrayStage[] stages;

  protected final Object varMutex = new Object();
  protected long startMs = -1L;
  protected long queueCycle = 0;
  protected byte[] lastEntry;

  public ByteArrayParamGenerator(Config config) {
    this.config = config;
    seenBranchesCache = config.hashCacheCreator.apply(config);
    inputQueue = config.inputQueueCreator.apply(config);
    stages = config.stagesCreator.apply(config);
  }

  @Override
  public Iterator<byte[]> iterator() { return stream().iterator(); }

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

  @Override
  public boolean isInfinite() { return true; }

  @Override
  public void onComplete(ExecutionResult result, int myParamIndex, byte[] myParam) {
    // If it's a unique path, then our param goes to the input queue if it's not null
    if (myParam == null) return;
    if (seenBranchesCache.checkUniqueAndStore(config.hasher.hash(result.branchHits))) {
      inputQueue.enqueue(new TestCase(myParam, result.branchHits, result.nanoTime));
    }
  }

  @Override
  public void close() throws Exception {
    try {
      seenBranchesCache.close();
    } finally {
      inputQueue.close();
    }
  }

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

  public static class Config {
    public static Builder builder() { return new Builder(); }

    public final List<byte[]> initialValues;
    public final List<byte[]> dictionary;
    public final BranchHit.Hasher hasher;
    public final Function<Config, HashCache> hashCacheCreator;
    public final Function<Config, InputQueue> inputQueueCreator;
    public final Function<Config, ByteArrayStage[]> stagesCreator;
    public final Function<Config, RandomHavocTweak[]> havocTweaksCreator;
    public final Random random;
    public final boolean reuseLastStageAsInfinite;
    public final int arithMax;
    public final int havocCycles;
    public final int havocStackPower;
    public final int havocBlockSmall;
    public final int havocBlockMedium;
    public final int havocBlockLarge;
    public final int havocBlockXLarge;
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

      public Function<Config, ByteArrayStage[]> stagesCreator;
      public Builder stagesCreator(Function<Config, ByteArrayStage[]> stagesCreator) {
        this.stagesCreator = stagesCreator;
        return this;
      }
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

      public Function<Config, RandomHavocTweak[]> havocTweaksCreator;
      public Builder havocTweaksCreator(Function<Config, RandomHavocTweak[]> havocTweaksCreator) {
        this.havocTweaksCreator = havocTweaksCreator;
        return this;
      }
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

      public Random random;
      public Builder random(Random random) {
        this.random = random;
        return this;
      }
      public Random randomDefault() { return new Random(); }

      public Boolean reuseLastStageAsInfinite;
      public Builder reuseLastStageAsInfinite(Boolean reuseLastStageAsInfinite) {
        this.reuseLastStageAsInfinite = reuseLastStageAsInfinite;
        return this;
      }
      public boolean reuseLastStageAsInfiniteDefault() { return true; }

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

  public interface HashCache extends AutoCloseable {
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

  public interface InputQueue extends AutoCloseable {
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
}
