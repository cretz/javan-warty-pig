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

public class MethodBranchAdapter extends MethodNode {

  private final MethodRefs refs;
  private final String className;
  private final MethodVisitor mv;

  public MethodBranchAdapter(MethodRefs refs, String className, int access, String name,
      String desc, String signature, String[] exceptions, MethodVisitor mv) {
    super(access, name, desc, signature, exceptions);
    this.refs = refs;
    this.className = className;
    this.mv = mv;
  }

  // Make sure index is the index AFTER nodes are inserted
  private int insnHashCode(int index) {
    return Arrays.hashCode(new int[] { className.hashCode(), name.hashCode(), desc.hashCode(), index });
  }

  private void insertBeforeAndInvokeStaticWithHash(AbstractInsnNode insn, MethodRef ref, AbstractInsnNode... before) {
    InsnList insns = new InsnList();
    int insnIndex = instructions.indexOf(insn);
    for (AbstractInsnNode node : before) insns.add(node);
    // Add branch hash and make static call
    insns.add(new LdcInsnNode(insnIndex + before.length + 2));
    insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, ref.classSig, ref.methodName, ref.methodName, false));
    instructions.insertBefore(insn, insns);
  }

  @Override
  public void visitEnd() {
    // We need the handler labels for catch clauses
    Set<Label> catchHandlerLabels = new HashSet<>(tryCatchBlocks.size());
    for (TryCatchBlockNode catchBlock : tryCatchBlocks) catchHandlerLabels.add(catchBlock.handler.getLabel());
    // Go over each instruction, injecting static calls where necessary
    ListIterator<AbstractInsnNode> iter = instructions.iterator();
    while (iter.hasNext()) {
      AbstractInsnNode insn = iter.next();
      switch (insn.getOpcode()) {
        case Opcodes.IFEQ:
        case Opcodes.IFNE:
        case Opcodes.IFLT:
        case Opcodes.IFGE:
        case Opcodes.IFGT:
        case Opcodes.IFLE:
          // Needs duped value and opcode const
          insertBeforeAndInvokeStaticWithHash(insn, refs.ifZeroRef,
              new InsnNode(Opcodes.DUP), new LdcInsnNode(insn.getOpcode()));
          break;
        case Opcodes.IF_ICMPEQ:
        case Opcodes.IF_ICMPNE:
        case Opcodes.IF_ICMPLT:
        case Opcodes.IF_ICMPGE:
        case Opcodes.IF_ICMPGT:
        case Opcodes.IF_ICMPLE:
          // Needs duped values and opcode const
          insertBeforeAndInvokeStaticWithHash(insn, refs.ifIntRef,
              new InsnNode(Opcodes.DUP2), new LdcInsnNode(insn.getOpcode()));
          break;
        case Opcodes.IF_ACMPEQ:
        case Opcodes.IF_ACMPNE:
          // Needs duped values and opcode const
          insertBeforeAndInvokeStaticWithHash(insn, refs.ifObjRef,
              new InsnNode(Opcodes.DUP2), new LdcInsnNode(insn.getOpcode()));
          break;
        case Opcodes.IFNULL:
        case Opcodes.IFNONNULL:
          // Needs duped value and opcode const
          insertBeforeAndInvokeStaticWithHash(insn, refs.ifNullRef,
              new InsnNode(Opcodes.DUP), new LdcInsnNode(insn.getOpcode()));
          break;
        case Opcodes.TABLESWITCH:
          TableSwitchInsnNode tableInsn = (TableSwitchInsnNode) insn;
          // Needs duped value and the min and max consts
          insertBeforeAndInvokeStaticWithHash(insn, refs.tableSwitchRef,
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
            nodes[i + 3] = new InsnNode(Opcodes.DUP);
            nodes[i + 4] = new LdcInsnNode(i);
            nodes[i + 5] = new LdcInsnNode(lookupSwitch.keys.get(i));
            nodes[i + 6] = new InsnNode(Opcodes.IASTORE);
          }
          insertBeforeAndInvokeStaticWithHash(insn, refs.lookupSwitchRef, nodes);
          break;
        case -1:
          // TODO: Do non-Java langs handle this differently?
          // If this is a handler label, go to the next non-line-num and non-frame insn and insert our stuff
          // before that.
          if (insn instanceof LabelNode && catchHandlerLabels.contains(((LabelNode) insn).getLabel())) {
            AbstractInsnNode next;
            do { next = insn.getNext(); } while (next instanceof LineNumberNode || next instanceof FrameNode);
            // Dupe the exception and call
            insertBeforeAndInvokeStaticWithHash(next, refs.catchRef, new InsnNode(Opcodes.DUP));
          }
          break;
      }
    }
    accept(mv);
  }

  public static class MethodRefs {
    private static final Type OBJECT_TYPE = Type.getType(Object.class);
    private static final Type INT_ARRAY_TYPE = Type.getType(int[].class);
    private static final Type THROWABLE_TYPE = Type.getType(Throwable.class);

    // void check(int value, int opcode, int branchHash)
    public final MethodRef ifZeroRef;
    // void check(int lvalue, int rvalue, int opcode, int branchHash)
    public final MethodRef ifIntRef;
    // void check(Object lvalue, Object rvalue, int opcode, int branchHash)
    public final MethodRef ifObjRef;
    // void check(Object value, int opcode, int branchHash)
    public final MethodRef ifNullRef;
    // void check(int value, int min, int max, int branchHash)
    public final MethodRef tableSwitchRef;
    // void check(int value, int[] keys, int branchHash)
    public final MethodRef lookupSwitchRef;
    // void check(Throwable value, int branchHash)
    public final MethodRef catchRef;

    public MethodRefs(MethodRef ifZeroRef, MethodRef ifIntRef, MethodRef ifObjRef, MethodRef ifNullRef,
        MethodRef tableSwitchRef, MethodRef lookupSwitchRef, MethodRef catchRef) {
      ifZeroRef.assertType(Type.VOID_TYPE, Type.INT_TYPE, Type.INT_TYPE, Type.INT_TYPE);
      this.ifZeroRef = ifZeroRef;
      ifIntRef.assertType(Type.VOID_TYPE, Type.INT_TYPE, Type.INT_TYPE, Type.INT_TYPE, Type.INT_TYPE);
      this.ifIntRef = ifIntRef;
      ifObjRef.assertType(Type.VOID_TYPE, OBJECT_TYPE, OBJECT_TYPE, Type.INT_TYPE, Type.INT_TYPE);
      this.ifObjRef = ifObjRef;
      ifNullRef.assertType(Type.VOID_TYPE, OBJECT_TYPE, Type.INT_TYPE);
      this.ifNullRef = ifNullRef;
      tableSwitchRef.assertType(Type.VOID_TYPE, Type.INT_TYPE, Type.INT_TYPE, Type.INT_TYPE);
      this.tableSwitchRef = tableSwitchRef;
      lookupSwitchRef.assertType(Type.VOID_TYPE, Type.INT_TYPE, INT_ARRAY_TYPE, Type.INT_TYPE);
      this.lookupSwitchRef = lookupSwitchRef;
      catchRef.assertType(Type.VOID_TYPE, THROWABLE_TYPE, Type.INT_TYPE);
      this.catchRef = catchRef;
    }
  }

  public static class MethodRef {
    public final String classSig;
    public final String methodName;
    public final String methodSig;

    public MethodRef(String classSig, String methodName, String methodSig) {
      this.classSig = classSig;
      this.methodName = methodName;
      this.methodSig = methodSig;
    }

    public MethodRef(Method method) {
      this(Type.getInternalName(method.getClass()), method.getName(), Type.getMethodDescriptor(method));
    }

    public void assertType(Type returnType, Type... paramTypes) {
      if (!returnType.equals(Type.getReturnType(methodSig)))
        throw new IllegalArgumentException("Invalid return type");
      if (!Arrays.equals(paramTypes, Type.getArgumentTypes(methodSig)))
        throw new IllegalArgumentException("Invalid arg types");
    }
  }
}
