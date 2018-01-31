package jwp.examples.htmlsan;

import jwp.extras.AflDictionary;
import jwp.extras.FilePersistence;
import jwp.extras.TestWriter;
import jwp.fuzz.*;
import org.owasp.html.examples.EbayPolicyExample;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class Main {
  public static void main(String[] args) throws Throwable {
    Files.createDirectories(Paths.get("build/tmp/"));
    // Keep track of total counts and last new hash
    AtomicLong completeCounter = new AtomicLong();
    AtomicLong lastNewHashAtCounter = new AtomicLong();
    // Keep track of our seen hashes in a file instead of memory (not to be confused with the
    //  file-based hash cache we will give to the byte array generator)
    FilePersistence.FileBasedHashCache seenHashes = new FilePersistence.FileBasedHashCache(
        new FilePersistence.FileBasedHashCache.Config(
            Paths.get("build/tmp/seen-hashes"), null, 30L
        )
    );
    System.out.println("Reloaded " + seenHashes.backingSet.size() + " seen hashes");

    // Create the fuzzer
    System.out.println("Creating fuzzer");
    Fuzzer fuzzer = new Fuzzer(Fuzzer.Config.builder().
        // Static parser method
        method(Main.class.getDeclaredMethod("sanitize", String.class)).

        // The suggested param provider w/ the single string param generator
        params(new ParamProvider.Suggested(
            ByteArrayParamGenerator.stringParamGenerator(
                StandardCharsets.UTF_8,
                // Filter the bytes to only include ascii chars from 32 to 126
                new ByteArrayParamGenerator(
                    ByteArrayParamGenerator.Config.builder().
                        // Initial value that succeeds
                        initialValues(Arrays.asList("<pre>test</pre>".getBytes())).
                        // File-backed hash cache and input queue
                        hashCacheCreator(config -> new FilePersistence.FileBasedHashCache(
                            new FilePersistence.FileBasedHashCache.Config(
                                Paths.get("build/tmp/byte-array-cache"), null, 50L
                            )
                        )).
                        inputQueueCreator(config -> new FilePersistence.FileBasedInputQueue(
                            new FilePersistence.FileBasedInputQueue.Config(
                                config, Paths.get("build/tmp/byte-array-queue"), null, 50L
                            )
                        )).
                        // Dictionary of existing terms
                        dictionary(AflDictionary.read(Main.class.getResource("html_tags.dict")).listOfBytes()).
                        // Build the complete config
                        build()
                )
            )
        )).

        // Handler to print out unique paths
        onSubmit((config, fut) -> fut.thenApply(res -> {
          long count = completeCounter.incrementAndGet();
          if (count % 1000 == 0) System.out.println("Completed " + count + " executions");
          int hash = BranchHit.Hasher.WITHOUT_HIT_COUNTS.hash(res.branchHits);
          // Only print output of failures (synchronized to prevent stdout overwrite)
          if (seenHashes.checkUniqueAndStore(hash)) {
            lastNewHashAtCounter.set(count);
            if (res.exception != null) synchronized (Main.class) {
              System.out.println("New path for param " + TestWriter.doubleQuotedString(res.params[0].toString()) +
                  ", result: " + res.exception + ", hash: " + hash +
                  " (after " + completeCounter + " total execs this run)");
            }
          }
          return res;
        })).

        // Multithreaded instead of default
        invoker(new Invoker.WithExecutorService(
            new ThreadPoolExecutor(5, 10, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(500), new ThreadPoolExecutor.CallerRunsPolicy())
        )).build()
    );

    // Run the fuzzer for a minute
    System.out.println("Running fuzzer");
    fuzzer.fuzzFor(1, TimeUnit.MINUTES);
    System.out.println("Fuzzer complete, ran " + completeCounter + " executions (last new hash found at execution " +
        lastNewHashAtCounter + ")");
  }

  public static String sanitize(String str) {
    return EbayPolicyExample.POLICY_DEFINITION.sanitize(str);
  }
}