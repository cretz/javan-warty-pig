package jwp.examples.htmlsan;

import jwp.extras.AflDictionary;
import jwp.extras.FilePersistence;
import jwp.extras.TestWriter;
import jwp.fuzz.*;
import org.owasp.encoder.Encode;
import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;
import org.owasp.html.examples.EbayPolicyExample;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

public class Main {
  public static void main(String[] args) throws Throwable {
    Files.createDirectories(Paths.get("build/tmp/"));

    // Whether to use persistence
    boolean persist = System.getProperty("jwp.noPersist") == null;

    // Whether to run the encoder instead
    boolean encoderInsteadOfSanitizer = System.getProperty("jwp.fuzzEncoder") != null;

    // Keep track of total counts and last new hash
    AtomicLong completeCounter = new AtomicLong();
    AtomicLong lastNewHashAtCounter = new AtomicLong();

    // Keep track of our seen hashes (not to be confused with the hash cache we will give to the byte array generator)
    ByteArrayParamGenerator.HashCache.SetBacked seenHashes;
    // Creators for byte gen hash cache and input queue
    Function<ByteArrayParamGenerator.Config, ByteArrayParamGenerator.HashCache> hashCacheCreator = null;
    Function<ByteArrayParamGenerator.Config, ByteArrayParamGenerator.InputQueue> inputQueueCreator = null;

    // Build persisted versions
    if (persist) {
      seenHashes = new FilePersistence.FileBasedHashCache(
          new FilePersistence.FileBasedHashCache.Config(
              Paths.get("build/tmp/seen-hashes"), null, 30L
          )
      );
      System.out.println("Reloaded " + seenHashes.backingSet.size() + " seen hashes");
      hashCacheCreator = config -> new FilePersistence.FileBasedHashCache(
          new FilePersistence.FileBasedHashCache.Config(
              Paths.get("build/tmp/byte-array-cache"), null, 50L
          )
      );
      inputQueueCreator = config -> new FilePersistence.FileBasedInputQueue(
          new FilePersistence.FileBasedInputQueue.Config(
              config, Paths.get("build/tmp/byte-array-queue"), null, 50L
          )
      );
    } else {
      seenHashes = new ByteArrayParamGenerator.HashCache.SetBacked();
    }

    // Create the fuzzer
    System.out.println(String.format("Creating fuzzer %s persistence to fuzz the %s",
        persist ? "with" : "without", encoderInsteadOfSanitizer ? "encoder" : "sanitizer"));
    Fuzzer fuzzer = new Fuzzer(Fuzzer.Config.builder().
        // Static parser method
        method(Main.class.getDeclaredMethod(encoderInsteadOfSanitizer ? "encode" : "sanitize", String.class)).

        // The suggested param provider w/ the single string param generator
        params(new ParamProvider.Suggested(
            ByteArrayParamGenerator.stringParamGenerator(
                StandardCharsets.UTF_8,
                // Filter the bytes to only include ascii chars from 32 to 126
                new ByteArrayParamGenerator(
                    ByteArrayParamGenerator.Config.builder().
                        // Initial value that succeeds
                        initialValues(Arrays.asList("<pre>test</pre>".getBytes())).
                        // Pre-created (maybe) hash cache and input queue
                        hashCacheCreator(hashCacheCreator).
                        inputQueueCreator(inputQueueCreator).
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
          int hash = BranchHit.Hasher.WITHOUT_HIT_COUNTS.hash(res.branchHits);
          // Only print output of failures (synchronized to prevent stdout overwrite)
          if (seenHashes.checkUniqueAndStore(hash)) {
            lastNewHashAtCounter.set(count);
            if (res.exception != null || (res.result != null &&
                (res.result.toString().indexOf('<') != -1 || res.result.toString().indexOf('>') != -1))) {
              synchronized (Main.class) {
                System.out.println("New path for param " + TestWriter.doubleQuotedString(res.params[0].toString()) +
                    ", result: " + (res.exception == null ? res.result : res.exception) + ", hash: " + hash +
                    " (after " + completeCounter + " total execs this run)");
              }
            }
          }
          if (count % 10000 == 0) {
            System.out.println("Completed " + count + " executions with " +
                seenHashes.backingSet.size() + " unique paths");
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

  private static final PolicyFactory allowNothingPolicy = new HtmlPolicyBuilder().toFactory();

  public static String sanitize(String str) {
    // Remove the comment section sometimes embedded
    return allowNothingPolicy.sanitize(str).replace("<!-- -->", "");
  }

  public static String encode(String str) {
    return Encode.forHtml(str);
  }
}