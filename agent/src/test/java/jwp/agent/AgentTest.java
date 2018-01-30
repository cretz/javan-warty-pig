package jwp.agent;

import org.junit.Assert;
import org.junit.Test;

public class AgentTest {

  @Test
  public void testArgsSuccess() {
    assertEquals(new Agent.Args(null, null), Agent.Args.fromString(null));
    assertEquals(new Agent.Args(null, null), Agent.Args.fromString(""));
    assertEquals(new Agent.Args(false, null),
        Agent.Args.fromString("noAutoRetransform"));
    assertEquals(new Agent.Args(null, new String[] { "foo", "bar" }),
        Agent.Args.fromString("classPrefixesToIgnore=foo,bar"));
    assertEquals(new Agent.Args(false, new String[] { "foo", "bar" }),
        Agent.Args.fromString("classPrefixesToIgnore=foo,bar;noAutoRetransform"));
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
    Assert.assertArrayEquals(expected.classPrefixesToIgnore, actual.classPrefixesToIgnore);
  }
}
