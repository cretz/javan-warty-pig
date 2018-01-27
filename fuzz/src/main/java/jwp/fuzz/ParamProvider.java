package jwp.fuzz;

import java.util.*;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public abstract class ParamProvider implements AutoCloseable {
  public final ParamGenerator<? super Object>[] paramGenerators;

  public ParamProvider(ParamGenerator<? super Object>[] paramGenerators) {
    this.paramGenerators = paramGenerators;
  }

  // Can trust the array is not edited (so it can be reused). No guarantees about the values inside though.
  public abstract Iterator<Object[]> iterator();

  public void onResult(ExecutionResult result) {
    for (int i = 0; i < paramGenerators.length; i++) {
      paramGenerators[i].onComplete(result, i, result.params[i]);
    }
  }

  @Override
  public void close() throws Exception {
    for (ParamGenerator<?> paramGen : paramGenerators) paramGen.close();
  }

  // Makes infinite ones change one at a time evenly amongst each other forever. Makes the first 3 non-infinite
  // ones use all permutations amongst each other. Makes the rest do random single changes.
  public static class Suggested extends ParamProvider {
    protected final ParamProvider prov;

    public Suggested(ParamGenerator<? super Object>[] paramGenerators) {
      super(paramGenerators);
      prov = new Partitioned(
          paramGenerators,
          (index, gen) -> gen.isInfinite(),
          EvenSingleParamChange::new,
          gensFixed -> new Partitioned(
              gensFixed,
              (index, gen) -> index < 4,
              AllPermutations::new,
              RandomSingleParamChange::new
          )
      );
    }

    @Override
    public Iterator<Object[]> iterator() { return prov.iterator(); }
  }

  public static class Partitioned extends ParamProvider {
    public final BiPredicate<Integer, ParamGenerator<?>> predicate;
    public final Function<ParamGenerator<? super Object>[], ParamProvider> trueProvider;
    public final Function<ParamGenerator<? super Object>[], ParamProvider> falseProvider;
    public final boolean stopWhenBothHaveEndedOnce;

    public Partitioned(ParamGenerator<? super Object>[] paramGenerators,
        BiPredicate<Integer, ParamGenerator<?>> predicate,
        Function<ParamGenerator<? super Object>[], ParamProvider> trueProvider,
        Function<ParamGenerator<? super Object>[], ParamProvider> falseProvider) {
      this(paramGenerators, predicate, trueProvider, falseProvider, true);
    }

    public Partitioned(ParamGenerator<? super Object>[] paramGenerators,
        BiPredicate<Integer, ParamGenerator<?>> predicate,
        Function<ParamGenerator<? super Object>[], ParamProvider> trueProvider,
        Function<ParamGenerator<? super Object>[], ParamProvider> falseProvider,
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
      List<ParamGenerator<? super Object>> trueGens = new ArrayList<>();
      List<Integer> trueIndexList = new ArrayList<>();
      List<ParamGenerator<? super Object>> falseGens = new ArrayList<>();
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

  public static class EvenSingleParamChange extends ParamProvider {
    public final boolean completeWhenAllCycledAtLeastOnce;

    public EvenSingleParamChange(ParamGenerator<? super Object>[] paramGenerators) {
      this(paramGenerators, true);
    }

    public EvenSingleParamChange(ParamGenerator<? super Object>[] paramGenerators,
        boolean completeWhenAllCycledAtLeastOnce) {
      super(paramGenerators);
      this.completeWhenAllCycledAtLeastOnce = completeWhenAllCycledAtLeastOnce;
    }

    @Override
    public Iterator<Object[]> iterator() {
      return new Util.NullMeansCompleteIterator<Object[]>() {
        private final Iterator<?>[] iters = new Iterator[paramGenerators.length];
        private int currIterIndex;
        private final boolean[] completedOnce = new boolean[paramGenerators.length];
        private final Object[] params = new Object[paramGenerators.length];

        {
          for (int i = 0; i < params.length; i++) {
            iters[i] = paramGenerators[i].iterator();
            params[i] = iters[i].next();
          }
        }

        @Override
        protected Object[] doNext() {
          if (!iters[currIterIndex].hasNext()) {
            if (completeWhenAllCycledAtLeastOnce && !completedOnce[currIterIndex]) {
              completedOnce[currIterIndex] = true;
              boolean foundOneNotCompleted = false;
              for (boolean iterCompleted : completedOnce) {
                if (!iterCompleted) {
                  foundOneNotCompleted = true;
                  break;
                }
              }
              if (!foundOneNotCompleted) return null;
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

  public static class AllPermutations extends ParamProvider {
    public AllPermutations(ParamGenerator<? super Object>[] paramGenerators) {
      super(paramGenerators);
    }

    @Override
    public Iterator<Object[]> iterator() { return stream().iterator(); }

    public Stream<Object[]> stream() {
      Stream<Object[]> ret = Stream.empty();
      for (int i = 0; i < paramGenerators.length; i++) {
        if (paramGenerators[i].isInfinite())
          throw new IllegalStateException("Cannot have infinite generator for all permutations");
        int index = i;
        ret = ret.flatMap(arr -> {
          Stream<?> paramStream = StreamSupport.stream(Spliterators.spliteratorUnknownSize(
              paramGenerators[index].iterator(), Spliterator.ORDERED), false);
          return paramStream.map(param -> {
            arr[index] = param;
            return arr;
          });
        });
      }
      return ret;
    }
  }

  public static class RandomSingleParamChange extends ParamProvider {
    public final Random random;
    public final int hashSetMaxBeforeReset;
    public final int maxDupeGenBeforeQuit;

    public RandomSingleParamChange(ParamGenerator<? super Object>[] paramGenerators) {
      this(paramGenerators, new Random(), 20000, 200);
    }

    public RandomSingleParamChange(ParamGenerator<? super Object>[] paramGenerators,
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
