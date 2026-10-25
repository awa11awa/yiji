import java.io.*;
import java.util.zip.*;
import org.jetbrains.org.objectweb.asm.*;

/** Backport two XMLUtil calls to Kotlin's equivalent Java-8 collection helper. */
public final class BackportXmlutil {
    public static void main(String[] args) throws Exception {
        final int[] replacements = {0};
        try (ZipInputStream input = new ZipInputStream(new FileInputStream(args[0]));
             ZipOutputStream output = new ZipOutputStream(new FileOutputStream(args[1]))) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = input.getNextEntry()) != null) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                int count; while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
                byte[] data = bytes.toByteArray();
                if (entry.getName().equals("nl/adaptivity/xmlutil/core/internal/InjectingInOutBuffer.class")) {
                    ClassReader reader = new ClassReader(data);
                    ClassWriter writer = new ClassWriter(reader, 0);
                    reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                        @Override public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                            return new MethodVisitor(Opcodes.ASM9, super.visitMethod(access, name, desc, signature, exceptions)) {
                                @Override public void visitMethodInsn(int opcode, String owner, String method, String descriptor, boolean itf) {
                                    if (owner.equals("java/util/List") && method.equals("removeLast") && descriptor.equals("()Ljava/lang/Object;")) {
                                        replacements[0]++;
                                        super.visitMethodInsn(Opcodes.INVOKESTATIC, "kotlin/collections/CollectionsKt", "removeLast", "(Ljava/util/List;)Ljava/lang/Object;", false);
                                    } else super.visitMethodInsn(opcode, owner, method, descriptor, itf);
                                }
                            };
                        }
                    }, 0);
                    data = writer.toByteArray();
                }
                ZipEntry copy = new ZipEntry(entry.getName()); copy.setTime(entry.getTime());
                output.putNextEntry(copy); output.write(data); output.closeEntry();
            }
        }
        if (replacements[0] != 2) throw new IllegalStateException("Unexpected XMLUtil call count: " + replacements[0]);
    }
}
