package jwp.fuzz;

import org.objectweb.asm.Opcodes;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Internally used class that is called by branch tracking operations */
public class BranchTracker {

  /** Map storing all hits by thread */
  public static final ConcurrentMap<Thread, BranchHits> branchHits = new ConcurrentHashMap<>();

  /** The set of method refs for the tracker */
  public static final MethodBranchAdapter.MethodRefs refs;

  static {
    // Setup the refs
    Map<String, Integer> methodNamesToOpcodes = new HashMap<>();
    methodNamesToOpcodes.put("ifEqCheck", Opcodes.IFEQ);
    methodNamesToOpcodes.put("ifNeCheck", Opcodes.IFNE);
    methodNamesToOpcodes.put("ifLtCheck", Opcodes.IFLT);
    methodNamesToOpcodes.put("ifLeCheck", Opcodes.IFLE);
    methodNamesToOpcodes.put("ifGtCheck", Opcodes.IFGT);
    methodNamesToOpcodes.put("ifGeCheck", Opcodes.IFGE);
    methodNamesToOpcodes.put("ifIcmpEqCheck", Opcodes.IF_ICMPEQ);
    methodNamesToOpcodes.put("ifIcmpNeCheck", Opcodes.IF_ICMPNE);
    methodNamesToOpcodes.put("ifIcmpLtCheck", Opcodes.IF_ICMPLT);
    methodNamesToOpcodes.put("ifIcmpLeCheck", Opcodes.IF_ICMPLE);
    methodNamesToOpcodes.put("ifIcmpGtCheck", Opcodes.IF_ICMPGT);
    methodNamesToOpcodes.put("ifIcmpGeCheck", Opcodes.IF_ICMPGE);
    methodNamesToOpcodes.put("ifAcmpEqCheck", Opcodes.IF_ACMPEQ);
    methodNamesToOpcodes.put("ifAcmpNeCheck", Opcodes.IF_ACMPNE);
    methodNamesToOpcodes.put("ifNullCheck", Opcodes.IFNULL);
    methodNamesToOpcodes.put("ifNonNullCheck", Opcodes.IFNONNULL);
    methodNamesToOpcodes.put("tableSwitchCheck", Opcodes.TABLESWITCH);
    methodNamesToOpcodes.put("lookupSwitchCheck", Opcodes.LOOKUPSWITCH);
    methodNamesToOpcodes.put("catchCheck", Opcodes.ATHROW);
    MethodBranchAdapter.MethodRefs.Builder builder = MethodBranchAdapter.MethodRefs.builder();
    for (Method method : BranchTracker.class.getDeclaredMethods()) {
      Integer opcode = methodNamesToOpcodes.get(method.getName());
      if (opcode != null) builder.set(opcode, new MethodBranchAdapter.MethodRef(method));
    }
    refs = builder.build();
  }

  /** Start tracking the given thread. This will fail if the thread is already being tracked. */
  public static void beginTrackingForThread(Thread thread) {
    if (branchHits.putIfAbsent(thread, new BranchHits(thread.getId())) != null)
      throw new IllegalArgumentException("Thread already being tracked");
  }

  /** Stop tracking the given thread. Returns null if never started. */
  public static BranchHits endTrackingForThread(Thread thread) {
    return branchHits.remove(thread);
  }

  /** Internal helper to add a branch hash for the current thread */
  public static void addBranchHash(int branchHash) {
    BranchHits hits = branchHits.get(Thread.currentThread());
    // Even though hits isn't thread safe, we know we're safe since it's essentially thread local.
    if (hits != null) hits.addHit(branchHash);
  }

  /** Called on IFEQ */
  public static void ifEqCheck(int value, int branchHash) {
    if (value == 0) addBranchHash(branchHash);
  }

  /** Called on IFNE */
  public static void ifNeCheck(int value, int branchHash) {
    if (value != 0) addBranchHash(branchHash);
  }

  /** Called on IFLT */
  public static void ifLtCheck(int value, int branchHash) {
    if (value < 0) addBranchHash(branchHash);
  }

  /** Called on IFLE */
  public static void ifLeCheck(int value, int branchHash) {
    if (value <= 0) addBranchHash(branchHash);
  }

  /** Called on IFGT */
  public static void ifGtCheck(int value, int branchHash) {
    if (value > 0) addBranchHash(branchHash);
  }

  /** Called on IFGE */
  public static void ifGeCheck(int value, int branchHash) {
    if (value >= 0) addBranchHash(branchHash);
  }

  /** Called on IF_ICMPEQ */
  public static void ifIcmpEqCheck(int lvalue, int rvalue, int branchHash) {
    if (lvalue == rvalue) addBranchHash(branchHash);
  }

  /** Called on IF_ICMPNE */
  public static void ifIcmpNeCheck(int lvalue, int rvalue, int branchHash) {
    if (lvalue != rvalue) addBranchHash(branchHash);
  }

  /** Called on IF_ICMPLT */
  public static void ifIcmpLtCheck(int lvalue, int rvalue, int branchHash) {
    if (lvalue < rvalue) addBranchHash(branchHash);
  }

  /** Called on IF_ICMPLE */
  public static void ifIcmpLeCheck(int lvalue, int rvalue, int branchHash) {
    if (lvalue <= rvalue) addBranchHash(branchHash);
  }

  /** Called on IF_ICMPGT */
  public static void ifIcmpGtCheck(int lvalue, int rvalue, int branchHash) {
    if (lvalue > rvalue) addBranchHash(branchHash);
  }

  /** Called on IF_ICMPGE */
  public static void ifIcmpGeCheck(int lvalue, int rvalue, int branchHash) {
    if (lvalue >= rvalue) addBranchHash(branchHash);
  }

  /** Called on IF_ACMPEQ */
  public static void ifAcmpEqCheck(Object lvalue, Object rvalue, int branchHash) {
    if (lvalue == rvalue) addBranchHash(branchHash);
  }

  /** Called on IF_ACMPNE */
  public static void ifAcmpNeCheck(Object lvalue, Object rvalue, int branchHash) {
    if (lvalue == rvalue) addBranchHash(branchHash);
  }

  /** Called on IFNULL */
  public static void ifNullCheck(Object value, int branchHash) {
    if (value == null) addBranchHash(branchHash);
  }

  /** Called on IFNONNULL */
  public static void ifNonNullCheck(Object value, int branchHash) {
    if (value != null) addBranchHash(branchHash);
  }

  /** Called on TABLESWITCH */
  public static void tableSwitchCheck(int value, int min, int max, int branchHash) {
    // We have to construct a new hash here w/ the value if it's in there
    // TODO: Should I check same labels since that is technically the same branch?
    if (value >= min && value <= max) addBranchHash(Arrays.hashCode(new int[] { branchHash, value }));
  }

  /** Called on LOOKUPSWITCH */
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

  /** Called at beginning of catch handlers */
  public static void catchCheck(Throwable ex, int branchHash) {
    // We don't care what the throwable is TODO: configurable
    addBranchHash(branchHash);
  }

  /** Internal helper class to store a mutable integer */
  public static class IntRef {
    public int value;
  }

  /** Internal class for holding and incrementing hit counts */
  public static class BranchHits {
    public final long threadId;
    public final LinkedHashMap<Integer, IntRef> branchHashHits = new LinkedHashMap<>();

    public BranchHits(long threadId) {
      this.threadId = threadId;
    }

    /** Add a hit for the given branch count */
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
