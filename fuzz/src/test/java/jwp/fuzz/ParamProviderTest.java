package jwp.fuzz;

import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

public class ParamProviderTest {
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