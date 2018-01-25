package jwp.fuzz;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.ListIterator;

public class MethodBranchAdapter extends MethodNode {

  private static InsnList insnList(AbstractInsnNode... insns) {
    InsnList ret = new InsnList();
    for (AbstractInsnNode node : insns) ret.add(node);
    return ret;
  }

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

  @Override
  public void visitEnd() {
    ListIterator<AbstractInsnNode> iter = instructions.iterator();
    // Add a static call before every jump
    while (iter.hasNext()) {
      AbstractInsnNode insn = iter.next();
      switch (insn.getOpcode()) {
        case Opcodes.IFEQ:
        case Opcodes.IFNE:
        case Opcodes.IFLT:
        case Opcodes.IFGE:
        case Opcodes.IFGT:
        case Opcodes.IFLE:
          instructions.insertBefore(insn, insnList(
              // Dup the value
              new InsnNode(Opcodes.DUP),
              // Add the opcode const
              new LdcInsnNode(insn.getOpcode()),
              // Add the branch hash
              new LdcInsnNode(insnHashCode(iter.previousIndex() + 4)),
              // Make the static call
              new MethodInsnNode(Opcodes.INVOKESTATIC, refs.ifZeroRef.classSig,
                  refs.ifZeroRef.methodName, refs.ifZeroRef.methodName, false)
          ));
          break;
        case Opcodes.IF_ICMPEQ:
        case Opcodes.IF_ICMPNE:
        case Opcodes.IF_ICMPLT:
        case Opcodes.IF_ICMPGE:
        case Opcodes.IF_ICMPGT:
        case Opcodes.IF_ICMPLE:
          instructions.insertBefore(insn, insnList(
              // Dup the values
              new InsnNode(Opcodes.DUP2),
              // Add the opcode const
              new LdcInsnNode(insn.getOpcode()),
              // Add the branch hash
              new LdcInsnNode(insnHashCode(iter.previousIndex() + 4)),
              // Make the static call
              new MethodInsnNode(Opcodes.INVOKESTATIC, refs.ifIntRef.classSig,
                  refs.ifIntRef.methodName, refs.ifIntRef.methodName, false)
          ));
          break;
        case Opcodes.IF_ACMPEQ:
        case Opcodes.IF_ACMPNE:
          instructions.insertBefore(insn, insnList(
              // Dup the values
              new InsnNode(Opcodes.DUP2),
              // Add the opcode const
              new LdcInsnNode(insn.getOpcode()),
              // Add the branch hash
              new LdcInsnNode(insnHashCode(iter.previousIndex() + 4)),
              // Make the static call
              new MethodInsnNode(Opcodes.INVOKESTATIC, refs.ifIntRef.classSig,
                  refs.ifIntRef.methodName, refs.ifIntRef.methodName, false)
          ));
          break;
      }
    }
    accept(mv);
  }

  public static class MethodRefs {
    private static final Type OBJECT_TYPE = Type.getType(Object.class);

    // void check(int value, int opcode, int branchHash)
    public final MethodRef ifZeroRef;
    // void check(int lvalue, int rvalue, int opcode, int branchHash)
    public final MethodRef ifIntRef;
    // void check(Object lvalue, Object rvalue, int opcode, int branchHash)
    public final MethodRef ifObjRef;

    public MethodRefs(MethodRef ifZeroRef, MethodRef ifIntRef, MethodRef ifObjRef) {
      ifZeroRef.assertType(Type.VOID_TYPE, Type.INT_TYPE, Type.INT_TYPE, Type.INT_TYPE);
      this.ifZeroRef = ifZeroRef;
      ifIntRef.assertType(Type.VOID_TYPE, Type.INT_TYPE, Type.INT_TYPE, Type.INT_TYPE, Type.INT_TYPE);
      this.ifIntRef = ifIntRef;
      ifObjRef.assertType(Type.VOID_TYPE, OBJECT_TYPE, OBJECT_TYPE, Type.INT_TYPE, Type.INT_TYPE);
      this.ifObjRef = ifObjRef;
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
