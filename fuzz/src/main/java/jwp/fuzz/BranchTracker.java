package jwp.fuzz;

import org.objectweb.asm.Opcodes;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class BranchTracker {

  public static final ConcurrentMap<Thread, BranchHits> branchHits = new ConcurrentHashMap<>();

  public static final MethodBranchAdapter.MethodRefs refs;

  static {
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

  public static void beginTrackingForThread(Thread thread) {
    if (branchHits.putIfAbsent(thread, new BranchHits(thread.getId())) != null)
      throw new IllegalArgumentException("Thread already being tracked");
  }

  public static BranchHits endTrackingForThread(Thread thread) {
    return branchHits.remove(thread);
  }

  public static void addBranchHash(Integer branchHash) {
    BranchHits hits = branchHits.get(Thread.currentThread());
    if (hits != null) hits.addHit(branchHash);
  }

  public static void ifEqCheck(int value, int branchHash) {
    if (value == 0) addBranchHash(branchHash);
  }

  public static void ifNeCheck(int value, int branchHash) {
    if (value != 0) addBranchHash(branchHash);
  }

  public static void ifLtCheck(int value, int branchHash) {
    if (value < 0) addBranchHash(branchHash);
  }

  public static void ifLeCheck(int value, int branchHash) {
    if (value <= 0) addBranchHash(branchHash);
  }

  public static void ifGtCheck(int value, int branchHash) {
    if (value > 0) addBranchHash(branchHash);
  }

  public static void ifGeCheck(int value, int branchHash) {
    if (value >= 0) addBranchHash(branchHash);
  }

  public static void ifIcmpEqCheck(int lvalue, int rvalue, int branchHash) {
    if (lvalue == rvalue) addBranchHash(branchHash);
  }

  public static void ifIcmpNeCheck(int lvalue, int rvalue, int branchHash) {
    if (lvalue != rvalue) addBranchHash(branchHash);
  }

  public static void ifIcmpLtCheck(int lvalue, int rvalue, int branchHash) {
    if (lvalue < rvalue) addBranchHash(branchHash);
  }

  public static void ifIcmpLeCheck(int lvalue, int rvalue, int branchHash) {
    if (lvalue <= rvalue) addBranchHash(branchHash);
  }

  public static void ifIcmpGtCheck(int lvalue, int rvalue, int branchHash) {
    if (lvalue > rvalue) addBranchHash(branchHash);
  }

  public static void ifIcmpGeCheck(int lvalue, int rvalue, int branchHash) {
    if (lvalue >= rvalue) addBranchHash(branchHash);
  }

  public static void ifAcmpEqCheck(Object lvalue, Object rvalue, int branchHash) {
    if (lvalue == rvalue) addBranchHash(branchHash);
  }

  public static void ifAcmpNeCheck(Object lvalue, Object rvalue, int branchHash) {
    if (lvalue == rvalue) addBranchHash(branchHash);
  }

  public static void ifNullCheck(Object value, int branchHash) {
    if (value == null) addBranchHash(branchHash);
  }

  public static void ifNonNullCheck(Object value, int branchHash) {
    if (value != null) addBranchHash(branchHash);
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
