# Javan Warty Pig

Javan Warty Pig, or JWP, is an [AFL](http://lcamtuf.coredump.cx/afl/)-like fuzzer for the JVM. It uses bytecode
instrumentation to trace execution. It is written in Java and requires Java 8+. There was
[an earlier version](https://github.com/cretz/javan-warty-pig-kotlin) started in Kotlin and Kotlin Native using
single-step [JVMTI](https://docs.oracle.com/javase/9/docs/specs/jvmti.html), but it was abandoned because it was too
slow.

*This project is in an early alpha stage and many things are not yet supported or completed*

The project is split into multiple projects. In general, to use the agent you use the agent JAR which contains all
classes in the `jwp.fuzz` package. There is an `extras` project that has classes in the `jwp.extras`

See the [Javadoc](https://jitpack.io/com/github/cretz/javan-warty-pig/javan-warty-pig/master-SNAPSHOT/javadoc/) and the
[examples](examples).

## Quick Start

The fuzzer needs a static method to fuzz. Say you have this file in `Num.java`:

```java
public class Num {
  public static void main(String[] args) {
    System.out.println("5.6 is a num: " + isNum("5.6"));
  }

  public static boolean isNum(String str) {
    if (str == null || str.isEmpty()) return false;
    boolean foundDecimal = false;
    for (int i = 0; i < str.length(); i++) {
      char chr = str.charAt(i);
      if (chr == '.') {
        if (foundDecimal) return false;
        foundDecimal = true;
      } else if (!Character.isDigit(chr)) {
        return false;
      }
    }
    return true;
  }
}
```

Compiling with `javac Num.java` will create `Num.class`. Running it gives the expected output:

    $ java Num
    5.6 is a num: true

Now let's test all paths of `isNum`. First grab the latest `master-SNAPSHOT` JAR from
[here](https://jitpack.io/com/github/cretz/javan-warty-pig/agent/master-SNAPSHOT/agent-master-SNAPSHOT-agent.jar) and
name it `jwp-agent.jar`. Now, change main to call the fuzzer:

```java

import jwp.fuzz.*;
import java.util.*;
import java.util.concurrent.*;

public class Num {
  public static void main(String[] args) throws Throwable {
    // Keep track of unique paths
    Set<Integer> seenPathHashes = Collections.newSetFromMap(new ConcurrentHashMap<>());
    // Create a fuzzer from configuration (which is created with a builder)
    Fuzzer fuzzer = new Fuzzer(Fuzzer.Config.builder().
        // Let the fuzzer know to fuzz the isNum method
        method(Num.class.getDeclaredMethod("isNum", String.class)).
        // We need to give the fuzzer a parameter provider. Here, we just use the suggested one.
        params(ParamProvider.suggested(String.class)).
        // Let's print out the parameter and result of each unique path
        onEachResult(res -> {
          // Create hash sans hit counts
          int hash = BranchHit.Hasher.WITHOUT_HIT_COUNTS.hash(res.branchHits);
          // Synchronized to prevent stdout overwrites
          if (seenPathHashes.add(hash)) synchronized (Num.class) {
            System.out.printf("Unique path for param '%s': %s\n", res.params[0],
                res.exception == null ? res.result : res.exception);
          }
        }).
        // Build the configuration
        build()
    );
    // Just run it for 5 seconds
    fuzzer.fuzzFor(5, TimeUnit.SECONDS);
  }

  /* [...isNum method...] */
}
```

Compile it with the agent on the classpath:

    javac -cp jwp-agent.jar Num.java

Now run it with the agent set and see the unique paths:

    Unique path for param 'null', result: false
    Unique path for param 'test', result: false
    Unique path for param '4est', result: false
    Unique path for param '3', result: true
    Unique path for param '.', result: true
    Unique path for param '..', result: false
    Unique path for param '."', result: false
    Unique path for param '6.', result: true
    Unique path for param '.19.', result: false

It usually only takes a few milliseconds to generate the above. While the example is simple, what is happening in the
background is the fuzzer is choosing new strings to try based on path changes in previous runs. We can see errors in
`isNum` such as returning true for `.`.

See more [examples](examples)...

## Installation

TODO: Write this...

## Usage

TODO: Write this...

## How Does it Work

TODO: Write this...

## FAQ

TODO: Write this...

## TODO

* Support remote workers
* Expose stats and a UI
* Create CLI
* Support "auto extras", i.e. auto-created dictionaries
* Support input queue trimming