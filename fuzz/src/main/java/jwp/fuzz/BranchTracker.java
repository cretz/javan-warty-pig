package jwp.fuzz;

import org.objectweb.asm.Opcodes;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class BranchTracker {

  // Keyed by thread ID. Note, it's possible in some JVMs that the thread ID may be reused, but the
  // purpose of using thread ID is for us to have a quick thread-local that stays around. The values
  // are keyed by branch hash and the value is hit count.
  public static final ConcurrentMap<Long, Map<Integer, Integer>> threadBranches = new ConcurrentHashMap<>();

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

  public static void addBranchHash(int branchHash) {
    // Since we're per thread here, we are safe once inside the map
    Map<Integer, Integer> branches = threadBranches.get(Thread.currentThread().getId());
    int prevHitCount = 0;
    if (branches == null) {
      branches = new HashMap<>();
      threadBranches.put(Thread.currentThread().getId(), branches);
    } else {
      prevHitCount = branches.get(branchHash);
    }
    branches.put(branchHash, prevHitCount + 1);
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
}
