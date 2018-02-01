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

    $ java -javaagent:jwp-agent.jar Num
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

## Setup

In order to run the fuzzer, you have to have the agent. The agent also installs itself on the classpath at runtime so it
should not be provided to the classpath explicitly.

### Manual

The manual way to use the fuzzer is to just download the
[latest agent JAR](https://jitpack.io/com/github/cretz/javan-warty-pig/agent/master-SNAPSHOT/agent-master-SNAPSHOT-agent.jar).
Then use the JAR on the classpath for `javac` (e.g. `-cp path/to/agent.jar`) and use it as the agent for `java` (e.g.
`-javaagent:path/to/agent.jar`).

### Gradle

Here's an example `build.gradle` for the [Quick Start](#quick-start) Java file assuming it is at
`src/main/java/Num.java`:

```groovy
apply plugin: 'application'
mainClassName = 'Num'

// Set JitPack repo
repositories { maven { url 'https://jitpack.io' } }

dependencies {
    // Compile only since the runtime agent injects it into the classpath
    compileOnly 'com.github.cretz.javan-warty-pig:agent:master-SNAPSHOT:agent@jar'
}

run.doFirst {
    // Get the full path of the agent JAR and set the javaagent
    def agentPath = configurations.compileOnly.find { it.absolutePath.contains 'javan-warty-pig' }.absolutePath
    jvmArgs "-javaagent:$agentPath"
}
```

This compiles with the agent JAR on the classpath and runs with it as the agent. The example can then be executed with
`path/to/gradle run`.

## Usage Details

TODO: enumerate the pieces like fuzzer, param prov, param gen, extras, invoker, tracer, advanced

### Fuzzer

(TODO)

### Parameter Provider

(TODO)

### Parameter Generator

(TODO)

#### Byte Array Generator

(TODO)

### Extras

(TODO)

#### Test Writer

(TODO)

### Other Components

(TODO)

#### Invoker and Tracer

(TODO)

#### Agent and Controller

(TODO)

## How Does it Work

(TODO)

## FAQ

(TODO)

## TODO

* Support remote workers
* Expose stats and a UI
* Create CLI
* Support "auto extras", i.e. auto-created dictionaries
* Support input queue trimming
* Support more default parameter types