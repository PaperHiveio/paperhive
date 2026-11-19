package org.abdullahcxd.paperhive.host.instance;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * {@code HiveFiles}: a class generated at startup with one static method per {@code java.nio.file.Files} method that
 * takes a {@code Path}, same name and descriptor. Each makes its relative {@code Path} arguments absolute against the
 * calling instance's root ({@link HiveFs#file}) and calls the real {@code Files} method. {@link InstanceTransformer}
 * points instance code's {@code Files.*} calls at it, so a relative {@code Path} the JDK derived later
 * ({@code getFileName()}, {@code relativize}) still opens inside the instance, not in the JVM's working directory.
 * <p>
 * Generated from {@code Files} itself, so every such method is covered whatever the JDK version; it's defined next to
 * {@link HiveFs} (same loader and package), which instances already see through {@link ApiGateLoader}.
 */
final class FilesMirror {

    static final String NAME = "org/abdullahcxd/paperhive/host/instance/HiveFiles";
    private static final String PATH = Type.getDescriptor(Path.class);
    // not InstanceTransformer.FS: InstanceTransformer initializes this class while it's still being initialized itself
    private static final String FS = Type.getInternalName(HiveFs.class);

    /** {@code name + descriptor} of every mirrored {@code Files} method. */
    static final Set<String> METHODS = new HashSet<>();
    /** {@code java/nio/file/Files.<name>} of every mirrored method, for {@link InstanceTransformer#TARGETS}. */
    static final Set<String> TARGETS = new HashSet<>();

    static {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, NAME, null, "java/lang/Object", null);
        for (Method m : Files.class.getMethods()) {
            if (!Modifier.isStatic(m.getModifiers()) || m.getDeclaringClass() != Files.class) continue;
            if (Arrays.stream(m.getParameterTypes()).noneMatch(t -> t == Path.class)) continue;
            String desc = Type.getMethodDescriptor(m);
            if (!METHODS.add(m.getName() + desc)) continue;
            TARGETS.add("java/nio/file/Files." + m.getName());

            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | (m.isVarArgs() ? Opcodes.ACC_VARARGS : 0),
                    m.getName(), desc, null, null);
            mv.visitCode();
            int slot = 0;
            for (Type arg : Type.getArgumentTypes(desc)) {
                mv.visitVarInsn(arg.getOpcode(Opcodes.ILOAD), slot);
                if (arg.getDescriptor().equals(PATH)) {
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, FS, "file", "(" + PATH + ")" + PATH, false);
                }
                slot += arg.getSize();
            }
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/nio/file/Files", m.getName(), desc, false);
            mv.visitInsn(Type.getReturnType(desc).getOpcode(Opcodes.IRETURN));
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        try {
            MethodHandles.lookup().defineClass(cw.toByteArray());
        } catch (IllegalAccessException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private FilesMirror() {}

}
