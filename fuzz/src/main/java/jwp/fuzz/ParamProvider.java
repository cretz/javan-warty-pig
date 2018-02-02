package jwp.fuzz;

import java.util.*;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Base class for all parameter providers. A parameter provider provides full sets of parameters based on individual
 * parameter generators. Implementors only have to override {@link #iterator()} and have to call the
 * {@link #ParamProvider(ParamGenerator[])} constructor. Parameter providers should have {@link #close()} when they are
 * no longer in use. There are several nested classes to help.
 */
public abstract class ParamProvider implements AutoCloseable {
  /**
   * Get the {@link Suggested} parameter provider for the parameter types.
   * Uses {@link ParamGenerator#suggested(Class)} for each class.
   */
  public static Suggested suggested(Class<?>... parameterTypes) {
    ParamGenerator<?>[] gens = new ParamGenerator[parameterTypes.length];
    for (int i = 0; i < gens.length; i++) gens[i] = ParamGenerator.suggested(parameterTypes[i]);
    return new Suggested(gens);
  }

  /**
   * The immutable set of parameter generators that are closed when this is and have their
   * {@link ParamGenerator#onResult(ExecutionResult, int, Object)} called via {@link #onResult(ExecutionResult)}.
   */
  public final ParamGenerator[] paramGenerators;

  public ParamProvider(ParamGenerator... paramGenerators) {
    this.paramGenerators = paramGenerators;
  }

  /**
   * Create a new iterator for parameter sets. Implementors can trust that the parameter array is copied before use so
   * the same object array can be returned over and over. Of course there are no guarantees about the objects inside the
   * arrays though. This iterator may be called multiple times.
   */
  public abstract Iterator<Object[]> iterator();

  /**
   * Called on completion of an execution and the default implementation simply delegates to
   * {@link ParamGenerator#onResult(ExecutionResult, int, Object)}
   */
  @SuppressWarnings("unchecked")
  public void onResult(ExecutionResult result) {
    for (int i = 0; i < paramGenerators.length; i++) {
      paramGenerators[i].onResult(result, i, result.params[i]);
    }
  }

  /** Delegates to {@link ParamGenerator#close()} */
  @Override
  public void close() throws Exception {
    for (ParamGenerator paramGen : paramGenerators) paramGen.close();
  }

  /**
   * A parameter provided that does suggested uses of generators. For every infinite generator, it uses
   * {@link EvenSingleParamChange} which changes only one parameter at a time across them, left to right. For the first
   * three finite generators, it uses {@link AllPermutations}. For the rest, it uses {@link RandomSingleParamChange}
   * which changes only one parameter at a time across them, but which one is changed is random.
   */
  public static class Suggested extends ParamProvider {
    protected final ParamProvider prov;

    public Suggested(ParamGenerator... paramGenerators) {
      super(paramGenerators);
      prov = new Partitioned(
          paramGenerators,
          (index, gen) -> gen.isInfinite(),
          EvenSingleParamChange::new,
          gensFixed -> new Partitioned(
              gensFixed,
              (index, gen) -> index < 3,
              AllPermutations::new,
              RandomSingleParamChange::new
          )
      );
    }

    @Override
    public Iterator<Object[]> iterator() { return prov.iterator(); }
  }

  /**
   * A partitioned parameter provider that delegates to other parameter providers based on a delegate. See the
   * constructor for more details.
   */
  public static class Partitioned extends ParamProvider {
    /** The predicate to determine which provider to use */
    public final BiPredicate<Integer, ParamGenerator> predicate;
    /** Which parameter provider to use when the predicate is true */
    public final Function<ParamGenerator[], ParamProvider> trueProvider;
    /** Which parameter provider to use when the predicate is true */
    public final Function<ParamGenerator[], ParamProvider> falseProvider;
    /** Stops the iterator if both sub-providers have ended at least once */
    public final boolean stopWhenBothHaveEndedOnce;

    /** Delegates to other constructor and sets it to * stop when both providers have completed once */
    public Partitioned(ParamGenerator[] paramGenerators,
        BiPredicate<Integer, ParamGenerator> predicate,
        Function<ParamGenerator[], ParamProvider> trueProvider,
        Function<ParamGenerator[], ParamProvider> falseProvider) {
      this(paramGenerators, predicate, trueProvider, falseProvider, true);
    }

    /**
     * Create a partitioned provider. The predicate is called with the index and the parameter generator from the
     * initial generator array. If true, it will call and use the resulting provider from the "trueProvider" function.
     * If false, it will call and use the resulting provider from the "falseProvider" function.
     * If "stopWhenBothHaveEnded" is true, this provider's iterator ends when both of the sub-providers have completed
     * at least once.
     */
    public Partitioned(ParamGenerator[] paramGenerators,
        BiPredicate<Integer, ParamGenerator> predicate,
        Function<ParamGenerator[], ParamProvider> trueProvider,
        Function<ParamGenerator[], ParamProvider> falseProvider,
        boolean stopWhenBothHaveEndedOnce) {
      super(paramGenerators);
      this.predicate = predicate;
      this.trueProvider = trueProvider;
      this.falseProvider = falseProvider;
      this.stopWhenBothHaveEndedOnce = stopWhenBothHaveEndedOnce;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Iterator<Object[]> iterator() {
      List<ParamGenerator> trueGens = new ArrayList<>();
      List<Integer> trueIndexList = new ArrayList<>();
      List<ParamGenerator> falseGens = new ArrayList<>();
      List<Integer> falseIndexList = new ArrayList<>();
      for (int i = 0; i < paramGenerators.length; i++) {
        if (predicate.test(i, paramGenerators[i])) {
          trueGens.add(paramGenerators[i]);
          trueIndexList.add(i);
        } else {
          falseGens.add(paramGenerators[i]);
          falseIndexList.add(i);
        }
      }
      if (trueGens.isEmpty()) return falseProvider.apply(falseGens.toArray(new ParamGenerator[0])).iterator();
      if (falseGens.isEmpty()) return trueProvider.apply(trueGens.toArray(new ParamGenerator[0])).iterator();

      ParamProvider trueProv = trueProvider.apply(trueGens.toArray(new ParamGenerator[0]));
      int[] trueIndices = new int[trueIndexList.size()];
      for (int i = 0; i < trueIndices.length; i++) trueIndices[i] = trueIndexList.get(i);
      ParamProvider falseProv = falseProvider.apply(falseGens.toArray(new ParamGenerator[0]));
      int[] falseIndices = new int[falseIndexList.size()];
      for (int i = 0; i < falseIndices.length; i++) falseIndices[i] = falseIndexList.get(i);
      return new Util.NullMeansCompleteIterator<Object[]>() {
        private Iterator<Object[]> trueIter = trueProv.iterator();
        private boolean trueCompletedAtLeastOnce;
        private Iterator<Object[]> falseIter = falseProv.iterator();
        private boolean falseCompletedAtLeastOnce;

        @Override
        protected Object[] doNext() {
          if (!trueIter.hasNext()) {
            if (!trueCompletedAtLeastOnce) trueCompletedAtLeastOnce = true;
            trueIter = trueProv.iterator();
          }
          if (!falseIter.hasNext()) {
            if (!falseCompletedAtLeastOnce) falseCompletedAtLeastOnce = true;
            falseIter = falseProv.iterator();
          }
          if (stopWhenBothHaveEndedOnce && trueCompletedAtLeastOnce && falseCompletedAtLeastOnce) return null;
          Object[] params = new Object[paramGenerators.length];
          Object[] trueParams = trueIter.next();
          for (int i = 0; i < trueIndices.length; i++) { params[trueIndices[i]] = trueParams[i]; }
          Object[] falseParams = falseIter.next();
          for (int i = 0; i < falseIndices.length; i++) { params[falseIndices[i]] = falseParams[i]; }
          return params;
        }
      };
    }
  }

  /** A parameter provider that evenly iterates over the given generators from left to right */
  public static class EvenAllParamChange extends ParamProvider {
    /** Whether to end the provider when all generators have completed at least once */
    public final boolean completeWhenAllCycledAtLeastOnce;

    /** Delegates to other constructor setting true for "completeWhenAllCycledAtLeastOnce" */
    public EvenAllParamChange(ParamGenerator... paramGenerators) {
      this(paramGenerators, true);
    }

    /**
     * Create a provider that evenly iterates the generators on each iteration. If "completeWhenAllCycledAtLeastOnce" is
     * true, this provider ends when all of the generators have completed at least once.
     */
    public EvenAllParamChange(ParamGenerator[] paramGenerators, boolean completeWhenAllCycledAtLeastOnce) {
      super(paramGenerators);
      this.completeWhenAllCycledAtLeastOnce = completeWhenAllCycledAtLeastOnce;
    }

    @Override
    public Iterator<Object[]> iterator() {
      return new Util.NullMeansCompleteIterator<Object[]>() {
        private final Iterator[] iters = new Iterator[paramGenerators.length];
        private final boolean[] completedOnce = new boolean[paramGenerators.length];
        private final Object[] params = new Object[paramGenerators.length];

        { for (int i = 0; i < params.length; i++) iters[i] = paramGenerators[i].iterator(); }

        private boolean allCompleted() {
          for (boolean iterCompleted : completedOnce) if (!iterCompleted) return false;
          return true;
        }

        @Override
        protected Object[] doNext() {
          for (int i = 0; i < params.length; i++) {
            if (!iters[i].hasNext()) {
              if (completeWhenAllCycledAtLeastOnce && !completedOnce[i]) {
                completedOnce[i] = true;
                if (allCompleted()) return null;
              }
              iters[i] = paramGenerators[i].iterator();
            }
            params[i] = iters[i].next();
          }
          return params;
        }
      };
    }
  }

  /** A parameter provider that iterates only one parameter at a time from left to right */
  public static class EvenSingleParamChange extends ParamProvider {
    /** Whether to complete the provider when all generators have completed at least once */
    public final boolean completeWhenAllCycledAtLeastOnce;

    /** Delegates to other constructor with true set for "completeWhenAllCycledAtLeastOnce" */
    public EvenSingleParamChange(ParamGenerator... paramGenerators) {
      this(paramGenerators, true);
    }

    /**
     * Create a parameter provider that iterates one generator at a time on each iteration, from left to right. If
     * "completeWhenAllCycledAtLeastOnce" is true, this provider will be complete when each generator has completed at
     * least once.
     */
    public EvenSingleParamChange(ParamGenerator[] paramGenerators, boolean completeWhenAllCycledAtLeastOnce) {
      super(paramGenerators);
      this.completeWhenAllCycledAtLeastOnce = completeWhenAllCycledAtLeastOnce;
    }

    @Override
    public Iterator<Object[]> iterator() {
      return new Util.NullMeansCompleteIterator<Object[]>() {
        private final Iterator[] iters = new Iterator[paramGenerators.length];
        private int currIterIndex;
        private final boolean[] completedOnce = new boolean[paramGenerators.length];
        private boolean firstRun = true;
        private final Object[] params = new Object[paramGenerators.length];

        {
          for (int i = 0; i < params.length; i++) {
            iters[i] = paramGenerators[i].iterator();
            params[i] = iters[i].next();
          }
        }

        private boolean allCompleted() {
          for (boolean iterCompleted : completedOnce) if (!iterCompleted) return false;
          return true;
        }

        @Override
        protected Object[] doNext() {
          if (firstRun) {
            firstRun = false;
            return params;
          }
          if (!iters[currIterIndex].hasNext()) {
            if (completeWhenAllCycledAtLeastOnce && !completedOnce[currIterIndex]) {
              completedOnce[currIterIndex] = true;
              if (allCompleted()) return null;
            }
            iters[currIterIndex] = paramGenerators[currIterIndex].iterator();
          }
          params[currIterIndex] = iters[currIterIndex].next();
          if (++currIterIndex >= iters.length) currIterIndex = 0;
          return params;
        }
      };
    }
  }

  /** A parameter provider that generates all permutations of the generators */
  public static class AllPermutations extends ParamProvider {
    /** Create the provider to use every permutation of the given generators */
    public AllPermutations(ParamGenerator... paramGenerators) {
      super(paramGenerators);
    }

    @Override
    public Iterator<Object[]> iterator() { return stream().iterator(); }

    @SuppressWarnings("unchecked")
    public Stream<Object[]> stream() {
      Stream<Object[]> ret = Stream.of(new Object[][]{ new Object[paramGenerators.length] });
      for (int i = 0; i < paramGenerators.length; i++) {
        if (paramGenerators[i].isInfinite())
          throw new IllegalStateException("Cannot have infinite generator for all permutations");
        int index = i;
        ret = ret.flatMap(arr -> {
          Stream<?> paramStream = StreamSupport.stream(Spliterators.spliteratorUnknownSize(
              paramGenerators[index].iterator(), Spliterator.ORDERED), false);
          return paramStream.map(param -> {
            Object[] newArr = Arrays.copyOf(arr, arr.length);
            newArr[index] = param;
            return newArr;
          });
        });
      }
      return ret;
    }
  }

  /** A parameter provider that randomly iterates just one generator per iteration and avoids duplicates */
  public static class RandomSingleParamChange extends ParamProvider {
    /** The random to use to determine which generator to iterate */
    public final Random random;
    /**
     * An internal hash set is used to keep track of the indices of each generator we have tried before. This value is
     * the number of these generator-index-sets to keep before clearing out the set and starting over.
     */
    public final int hashSetMaxBeforeReset;
    /**
     * Every attempted parameter set is checked against a hash set to see if it has been seen before. This value sets
     * the maximum number of parameter-set duplicates it will tolerate before ending the provider.
     */
    public final int maxDupeGenBeforeQuit;

    /**
     * Delegates to other constructor using {@link Random#Random()}, "hashSetMaxBeforeReset" as 20000, and
     * "maxDupeGenBeforeQuit" as 200
     */
    public RandomSingleParamChange(ParamGenerator... paramGenerators) {
      this(paramGenerators, new Random(), 20000, 200);
    }

    /**
     * Create a new parameter provider that randomly iterates a single generator on each iteration and avoids
     * duplicates. The decision of which generator to iterate uses the given random. See {@link #hashSetMaxBeforeReset}
     * and {@link #maxDupeGenBeforeQuit} fields for information on their use.
     */
    public RandomSingleParamChange(ParamGenerator[] paramGenerators,
        Random random, int hashSetMaxBeforeReset, int maxDupeGenBeforeQuit) {
      super(paramGenerators);
      this.random = random;
      this.hashSetMaxBeforeReset = hashSetMaxBeforeReset;
      this.maxDupeGenBeforeQuit = maxDupeGenBeforeQuit;
    }

    @Override
    public Iterator<Object[]> iterator() {
      return new Util.NullMeansCompleteIterator<Object[]>() {
        private final Iterator<?>[] iters = new Iterator[paramGenerators.length];
        private final int[] iterIndices = new int[paramGenerators.length];
        private final Set<Integer> seenParamIndexSets = new HashSet<>();
        private Object[] params;

        @Override
        protected Object[] doNext() {
          int currAttempts = 0;
          do {
            if (currAttempts++ >= maxDupeGenBeforeQuit) return null;
            if (seenParamIndexSets.size() >= hashSetMaxBeforeReset) seenParamIndexSets.clear();
            if (params == null) {
              params = new Object[iters.length];
              for (int i = 0; i < iters.length; i++) {
                iters[i] = paramGenerators[i].iterator();
                params[i] = iters[i].next();
              }
            } else {
              int index = random.nextInt(iters.length);
              if (!iters[index].hasNext()) {
                iters[index] = paramGenerators[index].iterator();
                iterIndices[index] = -1;
              }
              params[index] = iters[index].next();
              iterIndices[index]++;
            }
          } while (!seenParamIndexSets.add(Arrays.hashCode(iterIndices)));
          return params;
        }
      };
    }
  }
}
