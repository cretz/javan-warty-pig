package jwp.examples.simple;

import jwp.fuzz.*;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class Main {
  public static void main(String[] args) throws Throwable {
    // System property to turn on hit count check during print-uniqueness check
    boolean withHitCounts = System.getProperty("jwp.withHitCounts") != null;
    System.out.println("Showing unique paths (with hit counts: " + withHitCounts + ")");

    // Hash map and hasher to only show unique branches
    Set<Integer> seenHashes = Collections.newSetFromMap(new ConcurrentHashMap<>());
    BranchHit.Hasher hasher = withHitCounts ? BranchHit.Hasher.WITH_HIT_COUNTS : BranchHit.Hasher.WITHOUT_HIT_COUNTS;

    // Counter for number of completions
    AtomicLong completeCounter = new AtomicLong();

    // Create the fuzzer to use
    System.out.println("Creating fuzzer");
    Fuzzer fuzzer = new Fuzzer(Fuzzer.Config.builder().
        // Static parser method
        method(Main.class.getDeclaredMethod("parseNumber", String.class)).

        // The suggested param provider w/ the single string param generator
        params(new ParamProvider.Suggested(
            ByteArrayParamGenerator.stringParamGenerator(
                StandardCharsets.US_ASCII,
                // Filter the bytes to only include ascii chars from 32 to 126
                new ByteArrayParamGenerator(
                    ByteArrayParamGenerator.Config.builder().
                        // Initial value that succeeds
                        initialValues(Arrays.asList("+1.2".getBytes())).build()
                ).filter(Main::hasAcceptableChars)
            )
        )).

        // Handler to print out unique paths
        onSubmit((config, fut) -> fut.thenApply(res -> {
          completeCounter.incrementAndGet();
          int hash = hasher.hash(res.branchHits);
          // Print output if the execution path hasn't been seen before. (synchronized to prevent stdout overwrite)
          if (seenHashes.add(hash)) synchronized (Main.class) {
            System.out.println("New path for param '" + res.params[0] + "', result: " +
                (res.exception == null ? res.result : res.exception) + ", hash: " + hash +
                " (after " + completeCounter + " total execs)");
          }
          return res;
        })).

        // Multithreaded instead of default
        invoker(new Invoker.WithExecutorService(
            new ThreadPoolExecutor(5, 10, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(500), new ThreadPoolExecutor.CallerRunsPolicy())
        )).build()
    );

    // Run the fuzzer for 5 seconds
    System.out.println("Running fuzzer");
    fuzzer.fuzzFor(5, TimeUnit.SECONDS);
    System.out.println("Fuzzer complete");
  }

  public static Num parseNumber(String str) {
    if (str.isEmpty()) throw new NumberFormatException("Empty string");
    int index = 0;
    boolean neg = false;
    if (str.charAt(0) == '+') index++;
    else if (str.charAt(0) == '-') {
      neg = true;
      index++;
    }
    String num = "";
    while (index < str.length()) {
      char chr = str.charAt(index);
      if (chr < '0' || chr > '9') break;
      num += chr;
      index++;
    }
    if (num.isEmpty()) throw new NumberFormatException("No leading number(s)");
    String frac = "";
    if (index < str.length() && str.charAt(index) == '.') {
      index++;
      while (index < str.length()) {
        char chr = str.charAt(index);
        if (chr < '0' || chr > '9') break;
        frac += chr;
        index++;
      }
      if (frac.isEmpty()) throw new NumberFormatException("Decimal without trailing numbers(s)");
    }
    if (index != str.length()) throw new NumberFormatException("Unknown char: " + str.charAt(index));
    return new Num(neg, num, frac);
  }

  private static boolean hasAcceptableChars(byte[] bytes) {
    for (byte b : bytes) if (b < 32 || b > 126) return false;
    return true;
  }

  public static class Num {
    public final boolean neg;
    public final String num;
    public final String frac;

    public Num(boolean neg, String num, String frac) {
      this.neg = neg;
      this.num = num;
      this.frac = frac;
    }

    @Override
    public String toString() { return "Num(neg=" + neg + ", num=" + num + ", frac=" + frac + ")"; }
  }
}