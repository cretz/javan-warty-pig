package jwp.agent;

import org.junit.Assert;
import org.junit.Test;

public class AgentTest {

  @Test
  public void testArgsSuccess() {
    assertEquals(new Agent.Args(null, null, null), Agent.Args.fromString(null));
    assertEquals(new Agent.Args(null, null, null), Agent.Args.fromString(""));
    assertEquals(new Agent.Args(false, null, null),
        Agent.Args.fromString("noAutoRetransform"));
    assertEquals(new Agent.Args(null, null, new String[] { "foo", "bar" }),
        Agent.Args.fromString("classPrefixesToExclude=foo,bar"));
    assertEquals(new Agent.Args(false, null, new String[] { "foo", "bar" }),
        Agent.Args.fromString("classPrefixesToExclude=foo,bar;noAutoRetransform"));
    assertEquals(new Agent.Args(false, new String[] { "foo", "bar" }, new String[] { "baz", "qux" }),
        Agent.Args.fromString("classPrefixesToInclude=foo,bar;classPrefixesToExclude=baz,qux;noAutoRetransform"));
    assertEquals(new Agent.Args(false, new String[0], new String[0]),
        Agent.Args.fromString("classPrefixesToExclude=;classPrefixesToInclude=;noAutoRetransform"));
  }

  @Test
  public void testArgsFailure() {
    try {
      Agent.Args.fromString("blah");
      Assert.fail();
    } catch (Exception ignored) { }
    try {
      Agent.Args.fromString("classPrefixesToIgnore;noAutoRetransform");
      Assert.fail();
    } catch (Exception ignored) { }
  }

  private void assertEquals(Agent.Args expected, Agent.Args actual) {
    Assert.assertEquals(expected.retransformBoostrapped, actual.retransformBoostrapped);
    Assert.assertArrayEquals(expected.classPrefixesToInclude, actual.classPrefixesToInclude);
    Assert.assertArrayEquals(expected.classPrefixesToExclude, actual.classPrefixesToExclude);
  }
}
