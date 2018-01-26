package jwp.fuzz;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public class ClassBranchAdapter extends ClassVisitor {

  private final MethodBranchAdapter.MethodRefs refs;
  private String className;

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