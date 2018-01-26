package jwp.fuzz;

import org.objectweb.asm.Opcodes;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class BranchTracker {

  public static final ConcurrentMap<Long, BranchHits> branchHits = new ConcurrentHashMap<>();

  public static final MethodBranchAdapter.MethodRefs refs;

  static {
    try {
      Class<?> cls = BranchTracker.class;
      refs = new MethodBranchAdapter.MethodRefs(
          new MethodBranchAdapter.MethodRef(
              cls.getDeclaredMethod("ifZeroCheck", int.class, int.class, int.class)),
          new MethodBranchAdapter.MethodRef(
              cls.getDeclaredMethod("ifIntCheck", int.class, int.class, int.class, int.class)),
          new MethodBranchAdapter.MethodRef(
              cls.getDeclaredMethod("ifObjCheck", Object.class, Object.class, int.class, int.class)),
          new MethodBranchAdapter.MethodRef(
              cls.getDeclaredMethod("ifNullCheck", Object.class, int.class, int.class)),
          new MethodBranchAdapter.MethodRef(
              cls.getDeclaredMethod("tableSwitchCheck", int.class, int.class, int.class, int.class)),
          new MethodBranchAdapter.MethodRef(
              cls.getDeclaredMethod("lookupSwitchCheck", int.class, int[].class, int.class)),
          new MethodBranchAdapter.MethodRef(
              cls.getDeclaredMethod("catchCheck", Throwable.class, int.class))
      );
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  public static void addBranchHash(Integer branchHash) {
    Long threadId = Thread.currentThread().getId();
    BranchHits hits = branchHits.get(threadId);
    if (hits == null) {
      hits = new BranchHits(threadId);
      branchHits.put(threadId, hits);
    }
    hits.addHit(branchHash);
  }

  public static void ifZeroCheck(int value, int opcode, int branchHash) {
    switch (opcode) {
      case Opcodes.IFEQ:
        if (value == 0) addBranchHash(branchHash);
        break;
      case Opcodes.IFNE:
        if (value != 0) addBranchHash(branchHash);
        break;
      case Opcodes.IFLT:
        if (value < 0) addBranchHash(branchHash);
        break;
      case Opcodes.IFLE:
        if (value <= 0) addBranchHash(branchHash);
        break;
      case Opcodes.IFGT:
        if (value > 0) addBranchHash(branchHash);
        break;
      case Opcodes.IFGE:
        if (value >= 0) addBranchHash(branchHash);
        break;
    }
  }

  public static void ifIntCheck(int lvalue, int rvalue, int opcode, int branchHash) {
    switch (opcode) {
      case Opcodes.IF_ICMPEQ:
        if (lvalue == rvalue) addBranchHash(branchHash);
        break;
      case Opcodes.IF_ICMPNE:
        if (lvalue != rvalue) addBranchHash(branchHash);
        break;
      case Opcodes.IF_ICMPLT:
        if (lvalue < rvalue) addBranchHash(branchHash);
        break;
      case Opcodes.IF_ICMPLE:
        if (lvalue <= rvalue) addBranchHash(branchHash);
        break;
      case Opcodes.IF_ICMPGT:
        if (lvalue > rvalue) addBranchHash(branchHash);
        break;
      case Opcodes.IF_ICMPGE:
        if (lvalue >= rvalue) addBranchHash(branchHash);
        break;
    }
  }

  public static void ifObjCheck(Object lvalue, Object rvalue, int opcode, int branchHash) {
    switch (opcode) {
      case Opcodes.IF_ACMPEQ:
        if (lvalue == rvalue) addBranchHash(branchHash);
        break;
      case Opcodes.IF_ACMPNE:
        if (lvalue != rvalue) addBranchHash(branchHash);
        break;
    }
  }

  public static void ifNullCheck(Object value, int opcode, int branchHash) {
    switch (opcode) {
      case Opcodes.IFNULL:
        if (value == null) addBranchHash(branchHash);
        break;
      case Opcodes.IFNONNULL:
        if (value != null) addBranchHash(branchHash);
        break;
    }
  }

  public static void tableSwitchCheck(int value, int min, int max, int branchHash) {
    // We have to construct a new hash here w/ the value if it's in there
    // TODO: Should I check same labels since that is technically the same branch?
    if (value >= min && value <= max) addBranchHash(Arrays.hashCode(new int[] { branchHash, value }));
  }

  public static void lookupSwitchCheck(int value, int[] keys, int branchHash) {
    // We have to construct a new hash here w/ the value if it's in there
    // TODO: Should I check same labels since that is technically the same branch?
    for (int key : keys) {
      if (value == key) {
        addBranchHash(Arrays.hashCode(new int[] { branchHash, value }));
        return;
      }
    }
  }

  public static void catchCheck(Throwable ex, int branchHash) {
    // We don't care what the throwable is TODO: configurable
    addBranchHash(branchHash);
  }

  public static class IntRef {
    public int value;
  }

  public static class BranchHits {
    public final long threadId;
    public final LinkedHashMap<Integer, IntRef> branchHashHits = new LinkedHashMap<>();

    public BranchHits(long threadId) {
      this.threadId = threadId;
    }

    public void addHit(Integer branchHash) {
      IntRef counter = branchHashHits.get(branchHash);
      if (counter == null) {
        counter = new IntRef();
        branchHashHits.put(branchHash, counter);
      }
      counter.value++;
    }
  }
}
