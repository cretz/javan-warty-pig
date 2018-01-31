package jwp.extras;

import jwp.fuzz.BranchHit;
import jwp.fuzz.ByteArrayParamGenerator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Empty package-object holding file-persistent hash caches and input queues for {@link ByteArrayParamGenerator} */
public class FilePersistence {
  private FilePersistence() { }

  /** Helper to read a file into a byte buffer. The buffer is flipped before returned. */
  public static ByteBuffer readFile(Path path, OpenOption... options) {
    try (SeekableByteChannel file = Files.newByteChannel(path, options)) {
      ByteBuffer buf = ByteBuffer.allocateDirect((int) file.size());
      file.read(buf);
      if (buf.hasRemaining()) throw new IOException("Failed reading entire file");
      buf.flip();
      return buf;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** A {@link ByteArrayParamGenerator.HashCache} implementation that saves to a file periodically */
  public static class FileBasedHashCache extends ByteArrayParamGenerator.HashCache.SetBacked {
    /** The config set in the constructor */
    public final Config config;
    protected long lastSaveMs;
    protected long storesSinceLastSave;

    /** Create a new hash cache with the given config. This will load from a file or start an empty file. */
    public FileBasedHashCache(Config config) {
      super(config.backingSet, config.alreadySynchronized);
      this.config = config;
      if (!loadFromFile()) saveToFile();
      lastSaveMs = System.currentTimeMillis();
    }

    @Override
    public boolean checkUniqueAndStore(int hash) {
      if (!super.checkUniqueAndStore(hash)) return false;
      try {
        checkSave();
      } catch (RuntimeException e) {
        if (config.failOnError) throw e;
      }
      return true;
    }

    /** See if a save needs to occur. Increments the store count. */
    protected synchronized void checkSave() {
      storesSinceLastSave++;
      if ((config.saveAfterStoreCount != null && storesSinceLastSave >= config.saveAfterStoreCount) |
          (config.saveNextStoreAfterMilliseconds != null &&
              System.currentTimeMillis() - lastSaveMs >= config.saveNextStoreAfterMilliseconds)) {
        saveToFile();
      }
    }

    /** Save the backing set to a file */
    protected synchronized void saveToFile() {
      lastSaveMs = System.currentTimeMillis();
      storesSinceLastSave = 0;
      ByteBuffer buf = ByteBuffer.allocateDirect(4 + (backingSet.size() * 4));
      buf.putInt(backingSet.size());
      backingSet.forEach(buf::putInt);
      buf.flip();
      try (SeekableByteChannel file = Files.newByteChannel(config.filePath,
          StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE)) {
        file.write(buf);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }

    /**
     * Rebuild the backing set from a file. Should not be called during normal operation because it clears the set and
     * then adds everything.
     */
    protected synchronized boolean loadFromFile() {
      if (!Files.exists(config.filePath)) return false;
      ByteBuffer buf = readFile(config.filePath, StandardOpenOption.READ);
      backingSet.clear();
      int amount = buf.getInt();
      for (int i = 0; i < amount; i++) backingSet.add(buf.getInt());
      if (buf.hasRemaining()) throw new UncheckedIOException(new IOException("Extra bytes at end of file"));
      return true;
    }

    @Override
    public void close() {
      super.close();
      if (config.saveOnClose) saveToFile();
    }

    /** Configuration for the file-based hash cache */
    public static class Config {
      /** The in-memory backing set to use at runtime */
      public final Set<Integer> backingSet;
      /** Whether the backing set is already synchronized */
      public final boolean alreadySynchronized;
      /** The file path to load from and save to */
      public final Path filePath;
      /** The number of milliseconds that must pass before a save is performed on hash store */
      public final Long saveNextStoreAfterMilliseconds;
      /** The number of hash stores before a save is done */
      public final Long saveAfterStoreCount;
      /** If true, throws out of hash store when save fails. Load always fails on error regardless of this setting. */
      public final boolean failOnError;
      /** If true, does another save when the cache is closed */
      public final boolean saveOnClose;

      /**
       * Calls other constructor using defaults of an already-synchronized concurrent hash set, with fail on error and
       * save on close set to true.
       */
      public Config(Path filePath, Long saveNextStoreAfterMilliseconds, Long saveAfterStoreCount) {
        this(Collections.newSetFromMap(new ConcurrentHashMap<>()), true, filePath,
            saveNextStoreAfterMilliseconds, saveAfterStoreCount, true, true);
      }

      /** Build config with given values. See field descriptions for more information. */
      public Config(Set<Integer> backingSet, boolean alreadySynchronized, Path filePath,
          Long saveNextStoreAfterMilliseconds, Long saveAfterStoreCount, boolean failOnError, boolean saveOnClose) {
        this.backingSet = Objects.requireNonNull(backingSet);
        this.alreadySynchronized = alreadySynchronized;
        this.filePath = Objects.requireNonNull(filePath);
        if (saveAfterStoreCount == null && saveNextStoreAfterMilliseconds == null) {
          throw new IllegalArgumentException("Need to provide some count on when to save");
        }
        this.saveNextStoreAfterMilliseconds = saveNextStoreAfterMilliseconds;
        this.saveAfterStoreCount = saveAfterStoreCount;
        this.failOnError = failOnError;
        this.saveOnClose = saveOnClose;
      }
    }
  }

  /** An {@link ByteArrayParamGenerator.InputQueue} implementations that saves to a file periodically */
  public static class FileBasedInputQueue extends ByteArrayParamGenerator.InputQueue.ListBacked {
    /** The config set in the constructor */
    public final Config config;
    protected long lastSaveMs;
    protected long dequeuesSinceLastSave;

    /** Create a new input queue with the given config. This will load from a file or start an empty file. */
    public FileBasedInputQueue(Config config) {
      super(config.queue, config.hasher);
      this.config = config;
      if (!loadFromFile()) saveToFile();
      lastSaveMs = System.currentTimeMillis();
    }

    @Override
    public byte[] dequeue() {
      byte[] item = super.dequeue();
      if (item == null) return null;
      try {
        checkSave();
      } catch (RuntimeException e) {
        if (config.failOnError) throw e;
      }
      return item;
    }

    /** See if a save needs to occur. Increments the dequeue count. */
    protected synchronized void checkSave() {
      dequeuesSinceLastSave++;
      if ((config.saveAfterDequeueCount != null && dequeuesSinceLastSave >= config.saveAfterDequeueCount) ||
          (config.saveNextDequeueAfterMilliseconds != null &&
              System.currentTimeMillis() - lastSaveMs >= config.saveNextDequeueAfterMilliseconds)) {
        saveToFile();
      }
    }

    /** Save the backing queue to a file */
    protected synchronized void saveToFile() {
      lastSaveMs = System.currentTimeMillis();
      dequeuesSinceLastSave = 0;
      ByteArrayParamGenerator.TestCase[] testCases;
      synchronized (queue) {
        testCases = new ByteArrayParamGenerator.TestCase[queue.size()];
        queue.toArray(testCases);
      }
      try (SeekableByteChannel file = Files.newByteChannel(config.filePath,
          StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE)) {
        // Just a simple buf, the testCaseToBytes can overwrite it if needed
        ByteBuffer buf = ByteBuffer.allocateDirect(1024);
        buf.putInt(testCases.length);
        buf.flip();
        file.write(buf);
        for (ByteArrayParamGenerator.TestCase testCase : testCases) {
          buf.clear();
          buf = testCaseToBytes(testCase, buf);
          buf.flip();
          file.write(buf);
        }
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }

    /**
     * Convert a test case to a byte buffer. Uses and returns the given buf if big enough, otherwise creates a new one,
     * uses the new one, and returns it instead. Expects the given buf to be cleared before calling this.
     */
    protected ByteBuffer testCaseToBytes(ByteArrayParamGenerator.TestCase testCase, ByteBuffer buf) {
      int bytesNeeded = 4 + testCase.bytes.length + 4 + (testCase.branchHits.length * 8) + 8;
      if (bytesNeeded > buf.limit()) buf = ByteBuffer.allocateDirect(bytesNeeded);
      buf.putInt(testCase.bytes.length);
      buf.put(testCase.bytes);
      buf.putInt(testCase.branchHits.length);
      for (BranchHit hit : testCase.branchHits) buf.putInt(hit.branchHash).putInt(hit.hitCount);
      buf.putLong(testCase.nanoTime);
      return buf;
    }

    /**
     * Rebuild the backing queue from a file. Should not be called during normal operation because it clears the queue
     * and then adds everything.
     */
    protected synchronized boolean loadFromFile() {
      if (!Files.exists(config.filePath)) return false;
      ByteBuffer buf = readFile(config.filePath, StandardOpenOption.READ);
      ByteArrayParamGenerator.TestCase[] testCases = new ByteArrayParamGenerator.TestCase[buf.getInt()];
      for (int i = 0; i < testCases.length; i++) testCases[i] = testCaseFromBytes(buf);
      if (buf.hasRemaining()) throw new UncheckedIOException(new IOException("Extra bytes at end of file"));
      synchronized (queue) {
        queue.clear();
        queue.addAll(Arrays.asList(testCases));
      }
      return true;
    }

    /** Load a test case from the given buf */
    protected ByteArrayParamGenerator.TestCase testCaseFromBytes(ByteBuffer buf) {
      byte[] bytes = new byte[buf.getInt()];
      buf.get(bytes);
      BranchHit[] hits = new BranchHit[buf.getInt()];
      for (int i = 0; i < hits.length; i++) hits[i] = new BranchHit(buf.getInt(), buf.getInt());
      long nanoTime = buf.getLong();
      return new ByteArrayParamGenerator.TestCase(bytes, hits, nanoTime);
    }

    @Override
    public void close() {
      super.close();
      if (config.saveOnClose) saveToFile();
    }

    /** Configuration for the file-based input queue */
    public static class Config {
      /** The in-memory backing queue to use at runtime */
      public final List<ByteArrayParamGenerator.TestCase> queue;
      /** The hasher to use for culling. This is usually set via {@link ByteArrayParamGenerator.Config#hasher} */
      public final BranchHit.Hasher hasher;
      /** The file path to load from and save to */
      public final Path filePath;
      /** The number of milliseconds that must pass before a save is performed on dequeue */
      public final Long saveNextDequeueAfterMilliseconds;
      /** The number of dequeues before a save is done */
      public final Long saveAfterDequeueCount;
      /** If true, throws out of dequeue when save fails. Load always fails on error regardless of this setting. */
      public final boolean failOnError;
      /** If true, does another save when the input queue is closed */
      public final boolean saveOnClose;

      /**
       * Delegates to constructor with hasher as first param, using the {@link ByteArrayParamGenerator.Config#hasher}
       * value.
       */
      public Config(ByteArrayParamGenerator.Config byteArrayGenConfig, Path filePath,
          Long saveNextDequeueAfterMilliseconds, Long saveAfterDequeueCount) {
        this(byteArrayGenConfig.hasher, filePath, saveNextDequeueAfterMilliseconds, saveAfterDequeueCount);
      }

      /**
       * Delegates to the main constructor using a simple {@link ArrayList}, with fail on error and save on close set to
       * true.
       */
      public Config(BranchHit.Hasher hasher, Path filePath,
          Long saveNextDequeueAfterMilliseconds, Long saveAfterDequeueCount) {
        this(new ArrayList<>(), hasher, filePath,
            saveNextDequeueAfterMilliseconds, saveAfterDequeueCount, true, true);
      }

      /** Build config with given values. See field descriptions for more information. */
      public Config(List<ByteArrayParamGenerator.TestCase> queue, BranchHit.Hasher hasher, Path filePath,
          Long saveNextDequeueAfterMilliseconds, Long saveAfterDequeueCount, boolean failOnError, boolean saveOnClose) {
        this.queue = Objects.requireNonNull(queue);
        this.hasher = Objects.requireNonNull(hasher);
        this.filePath = Objects.requireNonNull(filePath);
        if (saveAfterDequeueCount == null && saveNextDequeueAfterMilliseconds == null) {
          throw new IllegalArgumentException("Need to provide some count on when to save");
        }
        this.saveNextDequeueAfterMilliseconds = saveNextDequeueAfterMilliseconds;
        this.saveAfterDequeueCount = saveAfterDequeueCount;
        this.failOnError = failOnError;
        this.saveOnClose = saveOnClose;
      }
    }
  }
}
