package jwp.fuzz;

import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.ListIterator;
import java.util.Set;
import java.util.function.Consumer;

/**
 * {@link MethodVisitor} that manipulates method bytecode before calling the delegating visitor. This inserts static
 * calls to references in the given {@link MethodRefs} on each branch. The values are also duplicated and sent to the
 * static methods as needed. The bytecodes that the static calls are inserted before are: IFEQ, IFNE, IFLT, IFGE, IFGT,
 * IFLE, IF_ICMPEQ, IF_ICMPNE, IF_ICMPLT, IF_ICMPGE, IF_ICMPGT, IF_ICMPLE, IF_ACMPEQ, IF_ACMPNE, IFNULL, IFNONNULL,
 * TABLESWITCH, and LOOKUPSWITCH. Also, a static call is made at the start of each catch handler as that is considered
 * a branch as well.
 */
public class MethodBranchAdapter extends MethodNode {

  private final MethodRefs refs;
  private final String className;
  private final MethodVisitor mv;
  private boolean alreadyTransformed;

  /**
   * Create this adapter with a set of {@link MethodRefs}, the internal class name for the method, values given from
   * {@link org.objectweb.asm.ClassVisitor#visitMethod(int, String, String, String, String[])}, and a
   * {@link MethodVisitor} to delegate to.
   */
  public MethodBranchAdapter(MethodRefs refs, String className, int access, String name,
      String desc, String signature, String[] exceptions, MethodVisitor mv) {
    super(Opcodes.ASM6, access, name, desc, signature, exceptions);
    this.refs = refs;
    this.className = className;
    this.mv = mv;
  }

  /** Note, this must be the index AFTER nodes are inserted */
  private int insnHashCode(int index) {
    return Arrays.hashCode(new int[] { className.hashCode(), name.hashCode(), desc.hashCode(), index });
  }

  private void insertBeforeAndInvokeStaticWithHash(AbstractInsnNode insn, MethodRef ref, AbstractInsnNode... before) {
    InsnList insns = new InsnList();
    int insnIndex = instructions.indexOf(insn);
    for (AbstractInsnNode node : before) insns.add(node);
    // Add branch hash and make static call
    insns.add(new LdcInsnNode(insnHashCode(insnIndex + before.length + 2)));
    insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, ref.classSig, ref.methodName, ref.methodSig, false));
    instructions.insertBefore(insn, insns);
  }

  @Override
  public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
    super.visitMethodInsn(opcode, owner, name, desc, itf);
    // We have to mark this method as already transformed if there is a call to refs sig
    if (refs.commonClassSig.equals(owner)) alreadyTransformed = true;
  }

  @Override
  public void visitEnd() {
    if (alreadyTransformed) {
      System.err.println("Skipping already transformed method " + className + ":" + name);
      accept(mv);
      return;
    }
    // We need the handler labels for catch clauses
    Set<Label> catchHandlerLabels = new HashSet<>(tryCatchBlocks.size());
    for (TryCatchBlockNode catchBlock : tryCatchBlocks) catchHandlerLabels.add(catchBlock.handler.getLabel());
    // Go over each instruction, injecting static calls where necessary
    ListIterator<AbstractInsnNode> iter = instructions.iterator();
    while (iter.hasNext()) {
      AbstractInsnNode insn = iter.next();
      int op = insn.getOpcode();
      switch (op) {
        case Opcodes.IFEQ:
        case Opcodes.IFNE:
        case Opcodes.IFLT:
        case Opcodes.IFGE:
        case Opcodes.IFGT:
        case Opcodes.IFLE:
          // Needs duped value
          insertBeforeAndInvokeStaticWithHash(insn, refs.refsByOpcode[op], new InsnNode(Opcodes.DUP));
          break;
        case Opcodes.IF_ICMPEQ:
        case Opcodes.IF_ICMPNE:
        case Opcodes.IF_ICMPLT:
        case Opcodes.IF_ICMPGE:
        case Opcodes.IF_ICMPGT:
        case Opcodes.IF_ICMPLE:
          // Needs duped values
          insertBeforeAndInvokeStaticWithHash(insn, refs.refsByOpcode[op], new InsnNode(Opcodes.DUP2));
          break;
        case Opcodes.IF_ACMPEQ:
        case Opcodes.IF_ACMPNE:
          // Needs duped values
          insertBeforeAndInvokeStaticWithHash(insn, refs.refsByOpcode[op], new InsnNode(Opcodes.DUP2));
          break;
        case Opcodes.IFNULL:
        case Opcodes.IFNONNULL:
          // Needs duped value
          insertBeforeAndInvokeStaticWithHash(insn, refs.refsByOpcode[op], new InsnNode(Opcodes.DUP));
          break;
        case Opcodes.TABLESWITCH:
          TableSwitchInsnNode tableInsn = (TableSwitchInsnNode) insn;
          // Needs duped value and the min and max consts
          insertBeforeAndInvokeStaticWithHash(insn, refs.refsByOpcode[op],
              new InsnNode(Opcodes.DUP), new LdcInsnNode(tableInsn.min), new LdcInsnNode(tableInsn.max));
          break;
        case Opcodes.LOOKUPSWITCH:
          // Needs duped value and an array of all the jump keys
          // XXX: should we really be creating this array here on every lookup? We could just assume this is always
          // a branch and hash off the value. We could also have our own lookup switch but it doesn't give much. We
          // could also put this array as a synthetic field on the class.
          LookupSwitchInsnNode lookupSwitch = (LookupSwitchInsnNode) insn;
          AbstractInsnNode[] nodes = new AbstractInsnNode[(4 * lookupSwitch.keys.size()) + 3];
          nodes[0] = new InsnNode(Opcodes.DUP);
          nodes[1] = new LdcInsnNode(lookupSwitch.keys.size());
          nodes[2] = new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_INT);
          for (int i = 0; i < lookupSwitch.keys.size(); i++) {
            nodes[(i * 4) + 3] = new InsnNode(Opcodes.DUP);
            nodes[(i * 4) + 4] = new LdcInsnNode(i);
            nodes[(i * 4) + 5] = new LdcInsnNode(lookupSwitch.keys.get(i));
            nodes[(i * 4) + 6] = new InsnNode(Opcodes.IASTORE);
          }
          insertBeforeAndInvokeStaticWithHash(insn, refs.refsByOpcode[op], nodes);
          break;
        case -1:
          // TODO: Do non-Java langs handle this differently?
          // If this is a handler label, go to the next non-line-num and non-frame insn and insert our stuff
          // before that.
          if (insn instanceof LabelNode && catchHandlerLabels.contains(((LabelNode) insn).getLabel())) {
            AbstractInsnNode next = insn.getNext();
            while (next instanceof LineNumberNode || next instanceof FrameNode) { next = next.getNext(); }
            // Dupe the exception and call
            insertBeforeAndInvokeStaticWithHash(next, refs.refsByOpcode[Opcodes.ATHROW], new InsnNode(Opcodes.DUP));
          }
          break;
      }
    }
    accept(mv);
  }

  /** A set of {@link MethodRef}s by opcode. The {@link Builder} must be used to create it. */
  public static class MethodRefs {

    /** The builder to create the {@link MethodRefs} */
    public static Builder builder() { return new Builder(); }

    /**
     * The JVM internal class sig that, if seen in a static method call, considers the method already transformed. This
     * is set as the same one that is shared by all methods.
     */
    public final String commonClassSig;
    private final MethodRef[] refsByOpcode;

    private MethodRefs(String commonClassSig, MethodRef[] refsByOpcode) {
      this.commonClassSig = commonClassSig;
      this.refsByOpcode = refsByOpcode;
    }

    /**
     * The builder to create a {@link MethodRefs} instance. This does validation to make sure all proper methods are set
     * and are of the proper type. And it makes sure that all methods are defined in the same class.
     */
    public static class Builder {
      private static final Type OBJECT_TYPE = Type.getType(Object.class);
      private static final Type INT_ARRAY_TYPE = Type.getType(int[].class);
      private static final Type THROWABLE_TYPE = Type.getType(Throwable.class);

      @SuppressWarnings("unchecked")
      private final static Consumer<MethodRef>[] validityCheckers = new Consumer[Opcodes.IFNONNULL + 1];

      static {
        // void check(int value, int branchHash)
        addChecks(m -> m.assertType(Type.VOID_TYPE, Type.INT_TYPE, Type.INT_TYPE),
            Opcodes.IFEQ, Opcodes.IFNE, Opcodes.IFLT, Opcodes.IFGE, Opcodes.IFGT, Opcodes.IFLE);
        // void check(int lvalue, int rvalue, int branchHash)
        addChecks(m -> m.assertType(Type.VOID_TYPE, Type.INT_TYPE, Type.INT_TYPE, Type.INT_TYPE),
            Opcodes.IF_ICMPEQ, Opcodes.IF_ICMPNE, Opcodes.IF_ICMPLT, Opcodes.IF_ICMPGE,
            Opcodes.IF_ICMPGT, Opcodes.IF_ICMPLE);
        // void check(Object lvalue, Object rvalue, int branchHash)
        addChecks(m -> m.assertType(Type.VOID_TYPE, OBJECT_TYPE, OBJECT_TYPE, Type.INT_TYPE),
            Opcodes.IF_ACMPEQ, Opcodes.IF_ACMPNE);
        // void check(Object value, int branchHash)
        addChecks(m -> m.assertType(Type.VOID_TYPE, OBJECT_TYPE, Type.INT_TYPE),
            Opcodes.IFNULL, Opcodes.IFNONNULL);
        // void check(int value, int min, int max, int branchHash)
        addChecks(m -> m.assertType(Type.VOID_TYPE, Type.INT_TYPE, Type.INT_TYPE, Type.INT_TYPE, Type.INT_TYPE),
            Opcodes.TABLESWITCH);
        // void check(int value, int[] keys, int branchHash)
        addChecks(m -> m.assertType(Type.VOID_TYPE, Type.INT_TYPE, INT_ARRAY_TYPE, Type.INT_TYPE),
            Opcodes.LOOKUPSWITCH);
        // void check(Throwable value, int branchHash)
        addChecks(m -> m.assertType(Type.VOID_TYPE, THROWABLE_TYPE, Type.INT_TYPE),
            Opcodes.ATHROW);
      }

      private static void addChecks(Consumer<MethodRef> check, int... opcodes) {
        for (int opcode : opcodes) validityCheckers[opcode] = check;
      }

      private final MethodRef[] refsByOpcode = new MethodRef[validityCheckers.length];

      /**
       * Set a specific {@link MethodRef} for a specific opcode. Each branching opcode must have a method set and it
       * must have an accurate set of parameters. For the catch handler, it should be assigned to the ATHROW
       * opcode. Validation does not occur until {@link #build()}. It is an error to call this multiple times with
       * method refs that are defined on different classes.
       */
      public void set(int opcode, MethodRef ref) { refsByOpcode[opcode] = ref; }

      /** Validate and build the refs */
      public MethodRefs build() {
        // Do validity checks
        String commonClassSig = null;
        for (int i = 0; i < validityCheckers.length; i++) {
          Consumer<MethodRef> check = validityCheckers[i];
          MethodRef ref = refsByOpcode[i];
          if (ref != null && check == null) throw new RuntimeException("Expecting no ref for opcode " + i);
          if (ref == null && check != null) throw new RuntimeException("Expecting ref for opcode " + i);
          if (check != null) {
            check.accept(ref);
            if (commonClassSig == null) commonClassSig = ref.classSig;
            else if (!commonClassSig.equals(ref.classSig)) throw new RuntimeException("All methods not on same class");
          }
        }
        return new MethodRefs(commonClassSig, refsByOpcode);
      }
    }
  }

  /** A reference to a static method call */
  public static class MethodRef {
    /** The internal JVM name/signature of the class containing the method */
    public final String classSig;
    /** The name of the method */
    public final String methodName;
    /** The JVM signature of the method */
    public final String methodSig;

    /** Simple constructor that just sets the fields */
    public MethodRef(String classSig, String methodName, String methodSig) {
      this.classSig = classSig;
      this.methodName = methodName;
      this.methodSig = methodSig;
    }

    /** Create the ref from the given reflected method */
    public MethodRef(Method method) {
      this(Type.getInternalName(method.getDeclaringClass()), method.getName(), Type.getMethodDescriptor(method));
    }

    /** Confirm that this method has the given return and param types or throw */
    public void assertType(Type returnType, Type... paramTypes) {
      Type actualReturnType = Type.getReturnType(methodSig);
      if (!returnType.equals(actualReturnType))
        throw new IllegalArgumentException("Invalid return type, expected " + returnType + ", got " + actualReturnType);
      Type[] actualParamTypes = Type.getArgumentTypes(methodSig);
      if (!Arrays.equals(paramTypes, actualParamTypes))
        throw new IllegalArgumentException("Invalid arg types, expected " + Arrays.toString(paramTypes) +
            ", got " + Arrays.toString(actualParamTypes));
    }
  }
}
