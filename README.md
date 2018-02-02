# Javan Warty Pig

Javan Warty Pig, or JWP, is an [AFL](http://lcamtuf.coredump.cx/afl/)-like
[fuzzer](https://en.wikipedia.org/wiki/Fuzzing) for the JVM. It uses bytecode instrumentation to trace execution. It is
written in Java and requires Java 8+. There was [an earlier version](https://github.com/cretz/javan-warty-pig-kotlin)
started in Kotlin and Kotlin Native using single-step [JVMTI](https://docs.oracle.com/javase/9/docs/specs/jvmti.html),
but it was abandoned because it was too slow.

*This project is in a beta stage and some things are not yet supported or completed*

The project is split into multiple projects. In general, to use the fuzzer you use the agent JAR which contains all
classes in the `jwp.fuzz` package. There is an `extras` project that has classes in the `jwp.extras` package.

See the [Javadoc](https://jitpack.io/com/github/cretz/javan-warty-pig/javan-warty-pig/master-SNAPSHOT/javadoc/), some
[examples](examples), the [quick start](#quick-start), how to [setup](#setup), [usage details](#usage-details), and
[how it works](#how-it-works).

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

The fuzzer contains several components explained below. There are defaults for many common use cases. The detailed
documentation is in
[Javadoc](https://jitpack.io/com/github/cretz/javan-warty-pig/javan-warty-pig/master-SNAPSHOT/javadoc/).

### Fuzzer

The
[Fuzzer](https://jitpack.io/com/github/cretz/javan-warty-pig/javan-warty-pig/master-SNAPSHOT/javadoc/jwp/fuzz/Fuzzer.html)
class is the main entrypoint to fuzzing. It has methods to run for a fixed amount of time, forever, or until an
`AtomicBoolean` is set. A
[Config](https://jitpack.io/com/github/cretz/javan-warty-pig/javan-warty-pig/master-SNAPSHOT/javadoc/jwp/fuzz/Fuzzer.Config.html)
must be provided to instantiate the `Fuzzer`. `Fuzzer.Config.builder()` returns a builder that makes it easier to create
the configuration and has defaults. Here are common configuration values (see Javadoc for more information):

* `method` - The method to run. This has to be static for now. Required.
* `params` - The parameter provider to use (see [Parameter Provider](#parameter-provider) below). Required.
* `onSubmit` - A function to transform/handle an execution future. Can use `addOnSubmit` to chain. Can use
  `onEachResult` or `addOnEachResult` to access result instead. Optional.

### Parameter Provider

A
[ParamProvider](https://jitpack.io/com/github/cretz/javan-warty-pig/javan-warty-pig/master-SNAPSHOT/javadoc/jwp/fuzz/ParamProvider.html)
provides object arrays to use as parameters when fuzzing. It is essentially a collection of individual
[Parameter Generator](#parameter-generator)s that are iterated in different ways. There are nested classes for
traversing all permutations, one at a time evenly or random, all each iteration, and different ways based on a
predicate. The `ParamProvider.suggested` can be called with a set of classes which uses `ParamGenerator.suggested` along
with the `ParamProvider.Suggested` provider which has some defaults. See the Javadoc for more info.

### Parameter Generator

A
[ParamGenerator](https://jitpack.io/com/github/cretz/javan-warty-pig/javan-warty-pig/master-SNAPSHOT/javadoc/jwp/fuzz/ParamGenerator.html)
provides values for a single parameter. It essentially just wraps an iterator with a close method and designates whether
it is infinite or not (so, basically like a stream). There are suggested generators for some generators that are
returned from `ParamGenerator.suggested`. `ParamGenerator`s can be mapped/filtered like streams as well.

#### Byte Array Generator

The
[ByteArrayParamGenerator](https://jitpack.io/com/github/cretz/javan-warty-pig/javan-warty-pig/master-SNAPSHOT/javadoc/jwp/fuzz/ByteArrayParamGenerator.html)
is a special type of `ParamGenerator` that produces an infinite set of byte arrays. It is built to closely follow the
logic of [AFL](http://lcamtuf.coredump.cx/afl/). Parameter generators for other types such as `ByteBuffer`,
`CharBuffer`, and `String` are sourced from the `ByteArrayParamGenerator`.

A default byte generator can be used with `ByteArrayParamGenerator.suggested` but it is not very powerful. Instead,
users are encouraged to instantiate their own `ByteArrayParamGenerator` with a
[ByteArrayParamGenerator.Config](https://jitpack.io/com/github/cretz/javan-warty-pig/javan-warty-pig/master-SNAPSHOT/javadoc/jwp/fuzz/ByteArrayParamGenerator.Config.html).
The `Config.builder()` method should be used to create a builder which helps with defaults. Here are some commonly set
configuration values (see Javadoc for more information):

* `initialValues` - This should be set to a byte array that succeeds. This will really help kickstart the fuzzer. By
  default it's just set to the string "test" which is not that useful.
* `dictionary` - This is a set of keywords that will help the fuzzer find new paths. To read an
  [AFL](http://lcamtuf.coredump.cx/afl/)-formatted dictionary, see the [AFL Dictionary](#afl-dictionary) extra.
* `hasher` - The
  [BranchHit.Hasher](https://jitpack.io/com/github/cretz/javan-warty-pig/javan-warty-pig/master-SNAPSHOT/javadoc/jwp/fuzz/BranchHit.Hasher.html)
  the generator will use to hash a set of `BranchHit`s and determine whether a path is new. By default it uses
  `Hasher.WITH_HIT_COUNTS` which means, like AFL, it will group numbers of branch hits into buckets for uniqueness
  purposes.
* `hashCacheCreator` - A callback that will create a
  [ByteArrayParamGenerator.HashCache](https://jitpack.io/com/github/cretz/javan-warty-pig/javan-warty-pig/master-SNAPSHOT/javadoc/jwp/fuzz/ByteArrayParamGenerator.HashCache.html)
  for the generator to use to hold on to seen hashes. By default it's just an in-memory `Set`, but see
  [File Persistence](#file-persistence) extras for a persistent one.
* `inputQueueCreator` - A callback that will create a
  [ByteArrayParamGenerator.InputQueue](https://jitpack.io/com/github/cretz/javan-warty-pig/javan-warty-pig/master-SNAPSHOT/javadoc/jwp/fuzz/ByteArrayParamGenerator.InputQueue.html)
  for the generator to use to enqueue interesting byte arrays and dequeue them as needed. By default it's just an
  in-memory `ArrayList`, but see [File Persistence](#file-persistence) extras for a persistent one.

See [Stages and Tweaks](#stages-and-tweaks) for a bit more on the byte array mutations.

### Extras

There is a separate project with a few helpers called "extras". It doesn't have a runtime dependency on the agent/fuzz
project because it is expected to be loaded as an agent. Since it is completely separate, it should be depended upon
like any other dependency. For example, in Gradle using JitPack:

```groovy
repositories {
    maven { url 'https://jitpack.io' }
}

dependencies {
    compile 'com.github.cretz.javan-warty-pig:extras:master-SNAPSHOT'
}
```

The [Javadoc](https://jitpack.io/com/github/cretz/javan-warty-pig/javan-warty-pig/master-SNAPSHOT/javadoc/jwp/extras/package-summary.html)
applies to the `jwp.extras` package.

#### File Persistence

There are two parts of the [Byte Array Generator](#byte-array-generator), the `HashCache` and the `InputQueue`, that
guide the generated byte code. To be able to resume fuzzing across JVM runs, they need to be persisted and then
reloaded.

There is a
[FileBasedHashCache](https://jitpack.io/com/github/cretz/javan-warty-pig/javan-warty-pig/master-SNAPSHOT/javadoc/jwp/extras/FilePersistence.FileBasedHashCache.html)
that reads from a file on first load if it exists, and saves to a file every so often. Its constructor accepts a
[FileBasedHashCache.Config](https://jitpack.io/com/github/cretz/javan-warty-pig/javan-warty-pig/master-SNAPSHOT/javadoc/jwp/extras/FilePersistence.FileBasedHashCache.Config.html)
instance that contains the following:

* `backingSet` - A `Set` to be used at runtime. If it's not already concurrency safe, `alreadySynchronized` below should
  be set to false. There is a shortcut constructor that automatically constructs this as
  `Collections.newSetFromMap(new ConcurrentHashMap<>())` and sets `alreadySynchronized` to true.
* `alreadySynchronized` - True if `backingSet` is concurrency-safe, false otherwise. This is automatically set to true
  with the shortcut constructor that uses a `Set` from a `ConcurrentHashMap`.
* `filePath` - The `Path` to a file to load from and save to.
* `saveNextStoreAfterMilliseconds` - The number of milliseconds, after which if any more hashes are stored it will
  trigger a save. The timer is reset on each save. Can be null but only if `saveAfterStoreCount` is set.
* `saveAfterStoreCount` - When the number of hashes stored reaches this number, it will trigger a save. The counter is
  reset after each save. Can be null but only if `saveNextStoreAfterMilliseconds` is set.
* `saveOnClose` - If true, when the cache is closed a save is triggered. This is set to true by default from the
  shortcut constructor.
* `failOnError` - If true, exceptions thrown while saving are not ignored. This is set to true by default from the
  shortcut constructor.

There is also a
[FileBasedInputQueue](https://jitpack.io/com/github/cretz/javan-warty-pig/javan-warty-pig/master-SNAPSHOT/javadoc/jwp/extras/FilePersistence.FileBasedInputQueue.html)
that reads from a file on first load if it exists, and saves to a file every so often. Its constructor accepts a
[FileBasedInputQueue.Config](https://jitpack.io/com/github/cretz/javan-warty-pig/javan-warty-pig/master-SNAPSHOT/javadoc/jwp/extras/FilePersistence.FileBasedInputQueue.Config.html)
instance that contains the following:

* `queue` - A `List` to be used at runtime. It does not have to be thread safe. There is a shortcut constructor that
  automatically constructs this as `new ArrayList<>()`.
* `hasher` - A
  [BranchHit.Hasher](https://jitpack.io/com/github/cretz/javan-warty-pig/javan-warty-pig/master-SNAPSHOT/javadoc/jwp/fuzz/BranchHit.Hasher.html)
  to use to construct the hashes for the base class implementation. There is a shortcut constructor that accepts a
  `ByteArrayGenerator.Config` and uses its `hasher` which is recommended.
* `filePath` - The `Path` to a file to load from and save to.
* `saveNextDequeueAfterMilliseconds` - The number of milliseconds, after which if any more cases are dequeued it will
  trigger a save. The timer is reset on each save. Can be null but only if `saveAfterDequeueCount` is set.
* `saveAfterDequeueCount` - When the number of cases dequeued reaches this number, it will trigger a save. The counter
  is reset after each save. Can be null but only if `saveNextDequeueAfterMilliseconds` is set.
* `saveOnClose` - If true, when the cache is closed a save is triggered. This is set to true by default from the
  shortcut constructor.
* `failOnError` - If true, exceptions thrown while saving are not ignored. This is set to true by default from the
  shortcut constructor.

#### AFL Dictionary

To load a dictionary file in [AFL](http://lcamtuf.coredump.cx/afl/) format, use the
[AflDictionary](https://jitpack.io/com/github/cretz/javan-warty-pig/javan-warty-pig/master-SNAPSHOT/javadoc/jwp/extras/AflDictionary.html)
class and the static `read` methods. The `listOfBytes` method on the instance can then be used to fetch a list of byte
arrays to set on the `dictionary` value of the `ByteArrayParamGenerator.Config`.

#### Test Writer

There is a
[TestWriter](https://jitpack.io/com/github/cretz/javan-warty-pig/javan-warty-pig/master-SNAPSHOT/javadoc/jwp/extras/TestWriter.html)
that accepts `ExecutionResult`s via the `append` method. Used in combination with `Fuzzer.Config`s `onEachResult`, the
result can be tested to see if it's unique (with a `BranchHit.Hasher`) and then appended to the test writer. Currently,
the only `TestWriter` is `TestWriter.JUnit4` but the base `TestWriter` is easily extensible. A `TestWriter` is built
with a `TestWriter.Config`. The configuration accepts a `className` for the test and `TestWriter.Namer` instance to
set the naming. The default namer is `TestWriter.Namer.SuccessOrFailCounted`.

Note, currently very few types of parameters and return values are supported. Until more are added, the `appendItem`
method can easily be overridden.

### Other Components

There are a few other components that make up the system that are usually not seen. They are all still public and
extensible like everything else.

#### Stages and Tweaks

The [Byte Array Generator](#byte-array-generator) is built using stages. A
[ByteArrayStage](https://jitpack.io/com/github/cretz/javan-warty-pig/javan-warty-pig/master-SNAPSHOT/javadoc/jwp/fuzz/ByteArrayStage.html)
is a stage that accepts a byte array to work from. Each stage returns multiple mutations of a copied version of the
array. The set of stages is returned as an array from the `stagesCreator` on the `ByteArrayParamGenerator.Config`. The
default configuration value returns a set of `ByteArrayStage`s that implement logic from `AFL`.

The last stage is the
[ByteArrayStage.RandomHavoc](https://jitpack.io/com/github/cretz/javan-warty-pig/javan-warty-pig/master-SNAPSHOT/javadoc/jwp/fuzz/ByteArrayStage.RandomHavoc.html)
stage. Begin the last stage, this stage is repeatedly run against the last queue entry when there are no more cases in
the input queue. This stage uses a set of
[RandomHavocTweak](https://jitpack.io/com/github/cretz/javan-warty-pig/javan-warty-pig/master-SNAPSHOT/javadoc/jwp/fuzz/RandomHavocTweak.html)s.
Each stage iteration can run multiple tweaks on the same byte array. The set of tweaks the default random havoic stage
uses are returned as an array from the `havocTweaksCreator` on the `ByteArrayParamGenerator.Config`. The default
configuration value returns a set of `RandomHavocTweak`s that implement logic from `AFL`.

#### Invoker and Tracer

Every method execution is invoked via an
[Invoker](https://jitpack.io/com/github/cretz/javan-warty-pig/javan-warty-pig/master-SNAPSHOT/javadoc/jwp/fuzz/Invoker.html)
which is set via the `invoker` on the `Fuzzer.Config`. By default the
[Invoker.WithExecutorService](https://jitpack.io/com/github/cretz/javan-warty-pig/javan-warty-pig/master-SNAPSHOT/javadoc/jwp/fuzz/Invoker.WithExecutorService.html)
is used with a
[Util.CurrentThreadExecutor](https://jitpack.io/com/github/cretz/javan-warty-pig/javan-warty-pig/master-SNAPSHOT/javadoc/jwp/fuzz/Util.CurrentThreadExecutorService.html).
A different `ExecutorService` can be provided. Currently the fuzzer sends as many execution requests as it can to the
invoker. The only thing that slows it down is the invoker's bounded queue. Therefore, developers are encouraged not to
use `ExecutorService`s with unbounded queues lest the memory shoot up very quickly as the fuzzer continually submits. So
a manually created `ThreadPoolExecutor` is ideal. If unbounded queues are a must, the `Fuzzer.Config` does have a
`sleepAfterSubmit` value.

A
[Tracer](https://jitpack.io/com/github/cretz/javan-warty-pig/javan-warty-pig/master-SNAPSHOT/javadoc/jwp/fuzz/Tracer.html)
is used to track
[BranchHit](https://jitpack.io/com/github/cretz/javan-warty-pig/javan-warty-pig/master-SNAPSHOT/javadoc/jwp/fuzz/BranchHit.html)s.
Currently it only tracks for a single thread of the execution. It is started via `startTrace` and stopped via
`stopTrace` which returns the array of `BranchHit`s. The tracer is set via `tracer` on `Fuzzer.Config`. The default
implementation is the `Tracer.Instrumenting` which uses the normal instrumenter to track branch hits.

#### Agent and Controller

There is a global agent doing the instrumentation that implements
[Agent](https://jitpack.io/com/github/cretz/javan-warty-pig/javan-warty-pig/master-SNAPSHOT/javadoc/jwp/fuzz/Agent.html).
This agent is set early on via the `setAgent` method of the
[Agent.Controller](https://jitpack.io/com/github/cretz/javan-warty-pig/javan-warty-pig/master-SNAPSHOT/javadoc/jwp/fuzz/Agent.Controller.html).
The `Agent.Controller` is a singleton which can be accessed via the `getInstance` method. It has calls to delegate to
the agent for retransforming classes, seeing what classes are loaded, setting which classes are included/excluded, etc.
Care should be taken on some of these calls.

The actual agent that starts does take parameters. The parameters of a Java agent are set after an equals sign, e.g.:

    -javaagent:path/to/jar=OPTIONS

The `OPTIONS` is a string for the options. The agent will fail to start with invalid options. There are three possible
options, separated by a semicolon. They are:

* `noAutoRetransform` - When present, this tells the agent not to eagerly retransform classes that are already on the
  classpath when the agent starts (i.e. the JVM classes on the bootloader path). By default this is not set which means
  the agent will eagerly retransform classes already loaded, but it usually doesn't transform any because the default
  `classPrefixesToExclude` excludes all the core JVM classes.
* `classPrefixesToInclude=string1,string2` - A set of string prefixes that, if a fully-qualified class name starts with
  any of, it will be instrumented. If a class is prefixed by any of these values, it is transformed regardless of what
  `classPrefixesToExclude` is set as. By default this is not set which means all non-excluded classes are instrumented.
* `classPrefixesToExclude=class1,class2` - A set of string prefixes that, if a fully-qualified class name starts with
  any of, it will not be instrumented. This does not override `classPrefixesToInclude` so if a prefix is found there, it
  is included regardless of what is set here. By default this is set to: `com.sun.`, `java.`, `jdk.`, `jwp.agent.`,
  `jwp.fuzz.`, `kotlin.`, `org.netbeans.lib.profiler.`, `scala.`, and `sun.`.

These options rarely need to be set and depending on what they are set to can cause stack overflow issues, especially
when classes to transform are the same ones used by the transformer.

## How it Works

JWP uses bytecode instrumentation via the
[java.lang.instrument](https://docs.oracle.com/javase/8/docs/api/java/lang/instrument/package-summary.html#package.description)
package. The agent is loaded and sets itself up as a classfile transformer. Then, as classfiles are seen and are ones
we want to transform, [ASM](http://asm.ow2.org/) is used to insert specific calls at branching operations.

The bytecodes that are considered branches are
[ones that compare to 0](https://docs.oracle.com/javase/specs/jvms/se8/html/jvms-6.html#jvms-6.5.if_cond) (`IFEQ`,
`IFNE`, `IFLT`, `IFGE`, `IFGT`, and `IFLE`),
[ones that compare two ints](https://docs.oracle.com/javase/specs/jvms/se8/html/jvms-6.html#jvms-6.5.if_icmp_cond),
(`IF_ICMPEQ`, `IF_ICMPNE`, `IF_ICMPLT`, `IF_ICMPGE`, `IF_ICMPGT`, and `IF_ICMPLE`),
[ones that compare two objects](https://docs.oracle.com/javase/specs/jvms/se8/html/jvms-6.html#jvms-6.5.if_acmp_cond)
(`IF_ACMPEQ` and `IF_ACMPNE`), ones that compare objects to null (
[IFNULL](https://docs.oracle.com/javase/specs/jvms/se8/html/jvms-6.html#jvms-6.5.ifnull) and
[IFNONNULL](https://docs.oracle.com/javase/specs/jvms/se8/html/jvms-6.html#jvms-6.5.ifnonnull)), switches (
[TABLESWITCH](https://docs.oracle.com/javase/specs/jvms/se8/html/jvms-6.html#jvms-6.5.tableswitch) and
[LOOKUPSWITCH](https://docs.oracle.com/javase/specs/jvms/se8/html/jvms-6.html#jvms-6.5.lookupswitch)), and
[catch handlers](https://docs.oracle.com/javase/specs/jvms/se8/html/jvms-3.html#jvms-3.12). Each branch is given a hash
built from the JVM class signature, the method name, the JVM method signature, and the index of the instruction (after
our instructions are inserted).

For simple "if" instructions, the values to check are duplicated on the stack and then a static method is called with
those values and the branch hash. The method checks what the branch instruction would check and if the branch
instruction will be invoked, a "hit" is stored. For the "switch" instructions, the value to check is duplicated on the
stack and passed along with the range/set of "branchable" values and the branch hash to a static method call. The method
checks if the value would cause a branch and if so, registers a "hit". The hash for the switch hit is actually a
combination of the branch hash and the value since different values can go to different places. For the catch handlers,
the branch already happened so a simple static method call is made saying so with the hash.

Branch hits are stored by thread + branch hash and keep track of the number of times they were hit. Each "hit" will get
or create a new branch hit instance and increment the count. When the tracer is started for a thread, the hit tracker is
notified that the thread needs to be tracked. When a "hit" is made it is not incremented/stored unless the thread is
being tracked. Once the tracer is stopped for a thread, the hits are serialized, sorted, and returned.

[Byte Array Generator](#byte-array-generator)s use the hashes of those branch hits to determine whether a path has been
seen before. There are two types of common hashes: ones just for the branch and one for the branch and the number of
times it was hit grouped into buckets. By default the latter is used and the hit counts are grouped into buckets of
1, 2, 3, 4, 8, 16, 32, or 128. This mimics [AFL](http://lcamtuf.coredump.cx/afl/).

On first run, since the input queue is empty, the byte array generator uses the initial values specified in the config.
The byte array then goes through the stages to generate several mutated versions of itself and those are used as
parameters. For each never-before-seen path, the byte array that was used as a parameter for it is enqueued into the
input queue. The input queue is ordered to prioritize the ones that ran the shortest and hit more unique branch pieces.
For each successive byte array generator iteration, an item is dequeued off the input queue and ran through the stages
to generate more parameters. If the input queue is empty, the last entry is just ran through the last stage (the random
stage) over and over again. All of this also mostly mimics [AFL](http://lcamtuf.coredump.cx/afl/).

## TODO

* Support cross-thread fuzzing with only one-at-a-time runs
* Support remote workers
* Expose stats and a UI
* Create CLI
* Support "auto extras", i.e. auto-created dictionaries
* Support input queue trimming
* Support more default parameter types
* Suppoer more test writer parameter and return types
* Timeouts to catch infinite loops
* Executors with unbounded queues