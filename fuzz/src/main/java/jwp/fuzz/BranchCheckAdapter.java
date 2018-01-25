package jwp.fuzz;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.JSRInlinerAdapter;

public class BranchCheckAdapter extends ClassVisitor {

  public BranchCheckAdapter(ClassVisitor cv) {
    this(Opcodes.ASM6, cv);
  }

  protected BranchCheckAdapter(int api, ClassVisitor cv) {
    super(api, cv);
  }

  @Override
  public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
    throw new RuntimeException("TODO");
  }
}