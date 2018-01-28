package jwp.fuzz;

import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

public class ParamProviderTest {

  private static List<List<Object>> flushProvider(ParamProvider prov) {
    List<List<Object>> ret = new ArrayList<>();
    prov.iterator().forEachRemaining(i -> ret.add(new ArrayList<>(Arrays.asList(i))));
    return ret;
  }

  @Test
  public void testEvenAllParamChangeSimple() {
    ParamProvider prov = new ParamProvider.EvenAllParamChange(
        ParamGenerator.of(1, 2),
        ParamGenerator.of(3, 4),
        ParamGenerator.of(5, 6),
        ParamGenerator.of(7, 8)
    );
    Assert.assertEquals(
        Arrays.asList(
            Arrays.asList(1, 3, 5, 7),
            Arrays.asList(2, 4, 6, 8)
        ),
        flushProvider(prov)
    );
  }

  @Test
  public void testEvenSingleParamChangeSimple() {
    ParamProvider prov = new ParamProvider.EvenSingleParamChange(
        ParamGenerator.of(1, 2),
        ParamGenerator.of(3, 4),
        ParamGenerator.of(5, 6)
    );
    Assert.assertEquals(
        Arrays.asList(
            Arrays.asList(1, 3, 5),
            Arrays.asList(2, 3, 5),
            Arrays.asList(2, 4, 5),
            Arrays.asList(2, 4, 6),
            Arrays.asList(1, 4, 6),
            Arrays.asList(1, 3, 6)
        ),
        flushProvider(prov)
    );
  }

  @Test
  public void testPartitionedSimple() {
    ParamProvider prov = new ParamProvider.Partitioned(
        new ParamGenerator[] {
            ParamGenerator.of(1, 2),
            ParamGenerator.of(3, 4),
            ParamGenerator.of(5, 6),
            ParamGenerator.of(7, 8)
        },
        // First two all perms, last two even
        (index, gen) -> index < 2,
        ParamProvider.AllPermutations::new,
        ParamProvider.EvenAllParamChange::new
    );
    Assert.assertEquals(
        Arrays.asList(
            Arrays.asList(1, 3, 5, 7),
            Arrays.asList(1, 4, 6, 8),
            Arrays.asList(2, 3, 5, 7),
            Arrays.asList(2, 4, 6, 8)
        ),
        flushProvider(prov)
    );
  }

  @Test
  public void testAllPermutationsSimple() {
    ParamProvider prov = new ParamProvider.AllPermutations(
      ParamGenerator.of(1, 2, 3),
      ParamGenerator.of(true, false)
    );
    Iterator<Object[]> iter = prov.iterator();
    List<List<Object>> all = new ArrayList<>();
    while (iter.hasNext()) all.add(Arrays.asList(iter.next()));
    Assert.assertEquals(
        Arrays.asList(
            Arrays.asList(1, true),
            Arrays.asList(1, false),
            Arrays.asList(2, true),
            Arrays.asList(2, false),
            Arrays.asList(3, true),
            Arrays.asList(3, false)
        ),
        all
    );
  }
}