package jwp.extras;

import jwp.fuzz.BranchHit;
import jwp.fuzz.ByteArrayParamGenerator;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

public class FilePersistenceTest {

  @Rule
  public final TemporaryFolder tempFolder = new TemporaryFolder();

  @Test
  public void testFileBasedHashCacheSaveAfterCount() {
    FilePersistence.FileBasedHashCache.Config config = new FilePersistence.FileBasedHashCache.Config(
        tempFolder.getRoot().toPath().resolve("testFileBasedHashCacheSaveAfterCount"), null, 5L);
    try (FilePersistence.FileBasedHashCache cache = new FilePersistence.FileBasedHashCache(config)) {
      // File must be there
      Assert.assertTrue(Files.exists(config.filePath));
      // Get the modified time
      long lastMod = config.filePath.toFile().lastModified();
      // Size should not change during first four sets
      for (int i = 0; i < 4; i++) {
        Assert.assertTrue(cache.checkUniqueAndStore(i + 1));
        Assert.assertEquals(lastMod, config.filePath.toFile().lastModified());
      }
      // Add another, and confirm the last mod was changed
      Assert.assertTrue(cache.checkUniqueAndStore(5));
      long newLastMod = config.filePath.toFile().lastModified();
      Assert.assertTrue(lastMod < newLastMod);
      // And one more, no last mod change, but we expect it to save on close
      Assert.assertTrue(cache.checkUniqueAndStore(6));
      Assert.assertEquals(newLastMod, config.filePath.toFile().lastModified());
    }
    // Now re-create it from file and confirm no file change and all the pieces are all there
    long lastMod = config.filePath.toFile().lastModified();
    try (FilePersistence.FileBasedHashCache cache = new FilePersistence.FileBasedHashCache(config)) {
      // Last mod didn't change
      Assert.assertEquals(lastMod, config.filePath.toFile().lastModified());
      // Each item is still there
      for (int i = 0; i < 6; i++) Assert.assertFalse(cache.checkUniqueAndStore(i + 1));
      // But a new one will be added
      Assert.assertTrue(cache.checkUniqueAndStore(7));
    }
  }

  @Test
  public void testFileBasedInputQueueSaveAfterCount() {
    // Generate random test cases...we use a made-up fixed seed just in case we fail
    Random random = new Random(5265);
    ByteArrayParamGenerator.TestCase[] someTestCases = new ByteArrayParamGenerator.TestCase[10];
    for (int i = 0; i < someTestCases.length; i++) {
      BranchHit[] someHits = new BranchHit[random.nextInt(10)];
      for (int j = 0; j < someHits.length; j++) someHits[j] = new BranchHit(random.nextInt(), random.nextInt());
      someTestCases[i] = new ByteArrayParamGenerator.TestCase(
          new byte[random.nextInt(50)], someHits, random.nextLong());
      random.nextBytes(someTestCases[i].bytes);
    }

    FilePersistence.FileBasedInputQueue.Config config = new FilePersistence.FileBasedInputQueue.Config(
        BranchHit.Hasher.WITHOUT_HIT_COUNTS,
        tempFolder.getRoot().toPath().resolve("testFileBasedInputQueueSaveAfterCount"), null, 5L);
    // Keep track of the known queue ourselves
    Set<Integer> queuedByteHashes = new HashSet<>();
    try (FilePersistence.FileBasedInputQueue queue = new FilePersistence.FileBasedInputQueue(config)) {
      // File must be there
      Assert.assertTrue(Files.exists(config.filePath));
      // Get the modified time
      long lastMod = config.filePath.toFile().lastModified();
      // Enqueue all test cases
      for (ByteArrayParamGenerator.TestCase someTestCase : someTestCases) {
        queue.enqueue(someTestCase);
        queuedByteHashes.add(Arrays.hashCode(someTestCase.bytes));
      }
      // Size should not change during first four dequeue
      for (int i = 0; i < 4; i++) {
        Assert.assertTrue(queuedByteHashes.remove(Arrays.hashCode(queue.dequeue())));
        Assert.assertEquals(lastMod, config.filePath.toFile().lastModified());
      }
      // Dequeue another, and confirm the last mod was changed
      Assert.assertTrue(queuedByteHashes.remove(Arrays.hashCode(queue.dequeue())));
      long newLastMod = config.filePath.toFile().lastModified();
      Assert.assertTrue(lastMod < newLastMod);
      // Dequeue one more, no last mod change, but we expect it to save on close
      Assert.assertTrue(queuedByteHashes.remove(Arrays.hashCode(queue.dequeue())));
      Assert.assertEquals(newLastMod, config.filePath.toFile().lastModified());
    }
    // Now re-create it from file and confirm no file change and all the pieces are all there
    long lastMod = config.filePath.toFile().lastModified();
    try (FilePersistence.FileBasedInputQueue queue = new FilePersistence.FileBasedInputQueue(config)) {
      // Last mod didn't change
      Assert.assertEquals(lastMod, config.filePath.toFile().lastModified());
      // Empty the queue
      while (true) {
        byte[] item = queue.dequeue();
        if (item == null) break;
        Assert.assertTrue(queuedByteHashes.remove(Arrays.hashCode(item)));
      }
      // And make sure our known queue is empty
      Assert.assertTrue(queuedByteHashes.isEmpty());
    }
  }
}
