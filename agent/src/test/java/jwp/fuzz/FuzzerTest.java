package jwp.fuzz;

import jwptest.TestMethods;
import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Predicate;

public class FuzzerTest {

  @Test(expected = Fuzzer.FuzzException.FirstRunFailed.class)
  public void testWrongArgs() throws Throwable {
    new Fuzzer(Fuzzer.Config.builder().
        method(TestMethods.class.getDeclaredMethod("simpleMethod", Integer.TYPE, Boolean.TYPE)).
        params(new ParamProvider.Suggested(ParamGenerator.suggestedFinite(Boolean.TYPE))).build()
    ).fuzz();
  }

  @Test
  public void testSimpleFunction() throws Throwable {
    // Let's store the unique paths. Keyed by hash path, value is param set.
    ConcurrentMap<Integer, ExecutionResult> uniquePaths = new ConcurrentHashMap<>();

    // Create the fuzzer
    Fuzzer fuzzer = new Fuzzer(Fuzzer.Config.builder().
        method(TestMethods.class.getDeclaredMethod("simpleMethod", Integer.TYPE, Boolean.TYPE)).
        params(new ParamProvider.Suggested(
            ParamGenerator.suggestedFinite(Integer.TYPE),
            ParamGenerator.suggestedFinite(Boolean.TYPE)
        )).onSubmit((config, fut) ->  fut.thenApply(res -> {
          uniquePaths.putIfAbsent(BranchHit.Hasher.WITHOUT_HIT_COUNTS.hash(res.branchHits), res);
          return res;
        })).build()
    );
    // This terminates on its own
    fuzzer.fuzz();

    // Helpers for checking the branches
    class Checker {
      void assertUnique(Predicate<Integer> param1, Predicate<Boolean> param2, String result) {
        Assert.assertTrue(uniquePaths.values().stream().anyMatch(res ->
            result.equals(res.result) && (param1 == null || param1.test((Integer) res.params[0])) &&
                (param2 == null || param2.test((Boolean) res.params[1]))
        ));
      }
    }
    Checker checker = new Checker();

    // Basically just validate the conditionals. Here is what the method looks like
    //    public static String simpleMethod(int foo, boolean bar) {
    //        if (foo == 2) return "two";
    //        if (foo >= 5 && foo <= 7 && bar) return "five to seven and bar";
    //        if (foo > 20 && !bar) return "over twenty and not bar";
    //        return "something else";
    //    }
    // Simple 2 check
    checker.assertUnique(i -> i == 2, null, "two");
    // In 5 to 7 and bar true
    checker.assertUnique(i -> i >= 5 && i <= 7, b -> b, "five to seven and bar");
    // In 5 to 7, but not bar is technically satisfying the first part of the if, so new branch
    checker.assertUnique(i -> i >= 5 && i <= 7, b -> !b, "something else");
    // Over 20, but not bar
    checker.assertUnique(i -> i > 20, b -> !b, "over twenty and not bar");
    // What about over 20 and bar? That's technically a new branch...
    checker.assertUnique(i -> i > 20, b -> b, "something else");
    // How about something that doesn't hit any of the branches? We need one that hits
    // both parts of the 5 and 7 range check...
    checker.assertUnique(i -> i != 2 && i < 5 && i < 20, null, "something else");
    checker.assertUnique(i -> i != 2 && i > 7 && i < 20, null, "something else");
    // So...7 branches
    Assert.assertEquals(7, uniquePaths.size());
  }
}