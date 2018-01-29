package jwp.examples.csv;

import com.opencsv.CSVReader;
import jwp.extras.TestWriter;
import jwp.fuzz.*;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class Main {

  public static void main(String[] args) throws Throwable {
    String srcPath = args.length == 0 ? null : args[0];
    String className = args.length < 2 ? "jwp.examples.csv.MainTest" : args[1];

    // Map and test writer for unique branches
    Set<Integer> seenHashes = Collections.newSetFromMap(new ConcurrentHashMap<>());
    TestWriter testWriter = new TestWriter.JUnit4(new TestWriter.Config(className));

    // Create the fuzzer
    System.out.println("Creating fuzzer");
    Fuzzer fuzzer = new Fuzzer(Fuzzer.Config.builder().
        // Static method to the CSV parser
        method(Main.class.getDeclaredMethod("parseCsv", String.class)).

        // Setup single param w/ initial value
        params(new ParamProvider.Suggested(
            // Normal UTF-8 strings are fine
            ByteArrayParamGenerator.stringParamGenerator(
                StandardCharsets.UTF_8,
                new ByteArrayParamGenerator(ByteArrayParamGenerator.Config.builder().
                    initialValues(Arrays.asList("foo,bar\nbaz,\"qux,quux\"".getBytes())).
                    build()
                )
            )
        )).

        // Add unique paths to the test writer
        onSubmit((config, fut) -> fut.thenApply(res -> {
          if (seenHashes.add(BranchHit.Hasher.WITHOUT_HIT_COUNTS.hash(res.branchHits))) {
            if (seenHashes.size() % 500 == 0) System.out.println("Added test number " + seenHashes.size());
            testWriter.append(res);

          }
          return res;
        })).

        // Multithreaded instead of default
        invoker(new Invoker.WithExecutorService(
            new ThreadPoolExecutor(5, 10, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(500), new ThreadPoolExecutor.CallerRunsPolicy())
        )).
        build()
    );

    // Run the fuzzer for 30 seconds
    System.out.println("Running fuzzer");
    fuzzer.fuzzFor(30, TimeUnit.SECONDS);
    System.out.println("Fuzzer complete");

    // Write the code to stdout or to a file
    StringBuilder code = new StringBuilder();
    testWriter.flush(code);
    if (srcPath == null) {
      System.out.println("Code:\n" + code);
    } else {
      Path filePath = Paths.get(srcPath, className.replace('.', '/') + ".java");
      System.out.println("Writing " + seenHashes.size() + " tests to " + filePath);
      Files.createDirectories(filePath.getParent());
      Files.write(filePath, code.toString().getBytes(StandardCharsets.UTF_8));
    }
  }

  public static List<List<String>> parseCsv(String str) throws IOException {
    return new CSVReader(new StringReader(str)).readAll().stream().map(Arrays::asList).collect(Collectors.toList());
  }
}