package jwp.fuzz;

import org.objectweb.asm.*;

/** The {@link ClassVisitor} that uses {@link MethodBranchAdapter} to insert branch calls in methods */
public class ClassBranchAdapter extends ClassVisitor {

  /** Create new classfile bytecode set from given original classfile bytecode using this adapter */
  public static byte[] transform(byte[] origBytes) {
    ClassReader reader = new ClassReader(origBytes);
    ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
    reader.accept(new ClassBranchAdapter(BranchTracker.refs, writer), 0);
    return writer.toByteArray();
  }

  private final MethodBranchAdapter.MethodRefs refs;
  private String className;

  /** Create this adapter with the given {@link MethodBranchAdapter.MethodRefs} to call */
  public ClassBranchAdapter(MethodBranchAdapter.MethodRefs refs, ClassVisitor cv) {
    super(Opcodes.ASM6, cv);
    this.refs = refs;
  }

  @Override
  public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
    super.visit(version, access, name, signature, superName, interfaces);
    className = name;
  }

  @Override
  public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
    MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
    return new MethodBranchAdapter(refs, className, access, name, desc, signature, exceptions, mv);
  }
}