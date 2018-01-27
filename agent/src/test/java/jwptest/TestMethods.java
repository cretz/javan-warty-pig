package jwptest;

// We put this in a different package to skip the prefix ignorer
public class TestMethods {
  public static String simpleMethod(int foo, boolean bar) {
    if (foo == 2) return "two";
    if (foo >= 5 && foo <= 7 && bar) return "five to seven and bar";
    if (foo > 20 && !bar) return "over twenty and not bar";
    return "something else";
  }
}
