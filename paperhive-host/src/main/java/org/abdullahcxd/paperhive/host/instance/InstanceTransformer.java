package org.abdullahcxd.paperhive.host.instance;

import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.Set;

/**
 * Rewrites calls in instance classes that assume "this process is the server" so they act on the calling instance
 * instead of the whole JVM. Targets live in {@link InstanceHooks} (lifecycle) and {@link HiveFs} (working directory):
 * <ul>
 *   <li>{@code System.exit(int)}, {@code Runtime.exit(int)}, {@code Runtime.halt(int)}</li>
 *   <li>{@code Runtime.addShutdownHook(Thread)}, {@code Runtime.removeShutdownHook(Thread)}</li>
 *   <li>{@code FileChannel.lock(..)} / {@code tryLock(..)}: the channel is recorded, so a lock the server leaves for
 *       process exit to release (e.g. a world's {@code session.lock}) is released when the instance stops</li>
 *   <li>{@code FlightRecorder.addListener/addPeriodicEvent/register(..)}: undone when the instance stops
 *       (Minecraft's JFR profiler uses all three; JFR keeps them JVM-wide)</li>
 *   <li>{@code Executors.newSingleThreadExecutor(..)}: shut down when the instance stops. The JDK keeps its pool in a
 *       JVM-wide {@code Cleaner} until the returned wrapper is shut down or collected, and the pool's thread factory
 *       keeps the wrapper (through the instance) from ever being collected</li>
 *   <li>{@code ClassLoader/SecureClassLoader.defineClass(..)} (all {@code final}, so safe to replace): the bytes go
 *       through this transformer first, so classes defined by the instance's own loaders (plugins) are rewritten too</li>
 *   <li>Relative paths: path arguments of {@code File}, {@code FileInputStream}, {@code RandomAccessFile}, ...
 *       constructors, {@code Path.of}, {@code Paths.get}, {@code FileSystem.getPath}, {@code File.getAbsolutePath} and
 *       friends, {@code System.getProperty("user.dir")}, and the directory of child processes resolve against the
 *       instance root instead of the JVM's cwd</li>
 *   <li>{@code System.out/err/in} reads and {@code System.setOut/setErr/setIn}: the calling instance's own streams
 *       ({@link HiveIo}), so one instance redirecting {@code System.out} doesn't take it from the others</li>
 *   <li>{@code Logger.getLogger/getGlobal}, {@code LogManager.getLogger/addLogger/getLoggerNames/reset}: the calling
 *       instance's own java.util.logging namespace ({@link HiveJul})</li>
 * </ul>
 * Every rewrite keeps the stack shape (replacements take the receiver as first argument; constructor arguments are
 * rewritten in place, at most with {@code SWAP}), so frames and max stack stay valid and nothing needs recomputing.
 * <p>
 * shortcut: calls made through reflection aren't caught; add those if a library needs them.
 */
final class InstanceTransformer {

    static final String HOOKS = InstanceHooks.class.getName().replace('.', '/');
    static final String FS = HiveFs.class.getName().replace('.', '/');
    static final String IO = HiveIo.class.getName().replace('.', '/');
    static final String JUL = HiveJul.class.getName().replace('.', '/');

    private static final String STRING = "Ljava/lang/String;";

    /**
     * Pre-check: every member {@link #redirect} or the path/stream rewrites can touch, as {@code owner.name}. A class
     * is only run through ASM if its constant pool references one of these (or any {@code defineClass}, whose owner
     * varies). Keep in sync with the rewrites; {@code InstanceTransformerTest} checks each is caught.
     */
    static final Set<String> TARGETS = withFiles(
            "java/lang/System.exit", "java/lang/System.out", "java/lang/System.err", "java/lang/System.in",
            "java/lang/System.setOut", "java/lang/System.setErr", "java/lang/System.setIn", "java/lang/System.getProperty",
            "java/lang/Runtime.exit", "java/lang/Runtime.halt", "java/lang/Runtime.addShutdownHook",
            "java/lang/Runtime.removeShutdownHook", "java/lang/Runtime.exec", "java/lang/ProcessBuilder.start",
            "java/util/concurrent/Executors.newSingleThreadExecutor",
            "jdk/jfr/FlightRecorder.addListener", "jdk/jfr/FlightRecorder.addPeriodicEvent", "jdk/jfr/FlightRecorder.register",
            "java/nio/channels/FileChannel.lock", "java/nio/channels/FileChannel.tryLock",
            "java/nio/file/Path.of", "java/nio/file/Path.toAbsolutePath", "java/nio/file/Paths.get", "java/nio/file/FileSystem.getPath",
            "java/io/File.<init>", "java/io/File.getAbsolutePath", "java/io/File.getCanonicalPath",
            "java/io/File.getAbsoluteFile", "java/io/File.getCanonicalFile",
            "java/io/FileInputStream.<init>", "java/io/FileOutputStream.<init>", "java/io/FileReader.<init>",
            "java/io/FileWriter.<init>", "java/io/PrintWriter.<init>", "java/io/PrintStream.<init>", "java/io/RandomAccessFile.<init>",
            "java/util/logging/Logger.getLogger", "java/util/logging/Logger.getGlobal",
            "java/util/logging/LogManager.getLogger", "java/util/logging/LogManager.addLogger",
            "java/util/logging/LogManager.getLoggerNames", "java/util/logging/LogManager.reset");

    private static Set<String> withFiles(String... targets) {
        Set<String> all = new java.util.HashSet<>(Set.of(targets));
        all.addAll(FilesMirror.TARGETS); // every java/nio/file/Files method taking a Path
        return Set.copyOf(all);
    }

    /** {@code ClassLoader} / {@code SecureClassLoader} {@code defineClass} overloads that take class bytes. */
    static final Set<String> DEFINE_CLASS_DESCRIPTORS = Set.of(
            "(Ljava/lang/String;[BII)Ljava/lang/Class;",
            "(Ljava/lang/String;[BIILjava/security/ProtectionDomain;)Ljava/lang/Class;",
            "(Ljava/lang/String;[BIILjava/security/CodeSource;)Ljava/lang/Class;",
            "(Ljava/lang/String;Ljava/nio/ByteBuffer;Ljava/security/ProtectionDomain;)Ljava/lang/Class;",
            "(Ljava/lang/String;Ljava/nio/ByteBuffer;Ljava/security/CodeSource;)Ljava/lang/Class;");

    /** Constructors whose last argument is a file name: resolve it on top of the stack. */
    private static final Set<String> PATH_LAST = Set.of(
            "java/io/File(Ljava/lang/String;)V",
            "java/io/FileInputStream(Ljava/lang/String;)V",
            "java/io/FileOutputStream(Ljava/lang/String;)V",
            "java/io/FileReader(Ljava/lang/String;)V",
            "java/io/FileWriter(Ljava/lang/String;)V",
            "java/io/PrintWriter(Ljava/lang/String;)V",
            "java/io/PrintStream(Ljava/lang/String;)V");

    /** Constructors with a file name followed by one more (single-slot) argument: SWAP, resolve, SWAP. */
    private static final Set<String> PATH_SECOND_LAST = Set.of(
            "java/io/FileOutputStream(Ljava/lang/String;Z)V",
            "java/io/FileWriter(Ljava/lang/String;Z)V",
            "java/io/FileReader(Ljava/lang/String;Ljava/nio/charset/Charset;)V",
            "java/io/FileWriter(Ljava/lang/String;Ljava/nio/charset/Charset;)V",
            "java/io/RandomAccessFile(Ljava/lang/String;Ljava/lang/String;)V",
            "java/io/PrintWriter(Ljava/lang/String;Ljava/lang/String;)V",
            "java/io/PrintWriter(Ljava/lang/String;Ljava/nio/charset/Charset;)V",
            "java/io/PrintStream(Ljava/lang/String;Ljava/lang/String;)V",
            "java/io/PrintStream(Ljava/lang/String;Ljava/nio/charset/Charset;)V");

    private InstanceTransformer() {}

    static byte[] transform(byte @NotNull [] bytes) {
        ClassReader reader = new ClassReader(bytes); // only indexes the constant pool
        if (!mayNeedRewrite(reader)) return bytes;

        ClassWriter writer = new ClassWriter(reader, 0);
        boolean[] changed = {false};
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public @Nullable MethodVisitor visitMethod(int access, @NotNull String name, @NotNull String desc, @NotNull String signature, String @NotNull [] exceptions) {
                return new MethodVisitor(Opcodes.ASM9, super.visitMethod(access, name, desc, signature, exceptions)) {
                    // method references (Executors::newSingleThreadExecutor, FlightRecorder::register) are handles, not calls
                    @Override
                    public void visitInvokeDynamicInsn(@NotNull String name, @NotNull String desc, @NotNull Handle bsm, Object @NotNull ... args) {
                        Object[] mapped = args.clone();
                        for (int i = 0; i < mapped.length; i++) if (mapped[i] instanceof Handle h) mapped[i] = redirectHandle(h);
                        super.visitInvokeDynamicInsn(name, desc, bsm, mapped);
                    }

                    @Override
                    public void visitLdcInsn(@NotNull Object value) {
                        super.visitLdcInsn(value instanceof Handle h ? redirectHandle(h) : value);
                    }

                    private Handle redirectHandle(Handle h) {
                        int opcode = switch (h.getTag()) {
                            case Opcodes.H_INVOKESTATIC -> Opcodes.INVOKESTATIC;
                            case Opcodes.H_INVOKEVIRTUAL -> Opcodes.INVOKEVIRTUAL;
                            case Opcodes.H_INVOKEINTERFACE -> Opcodes.INVOKEINTERFACE;
                            default -> -1;
                        };
                        String[] target = opcode < 0 ? null : InstanceTransformer.redirect(opcode, h.getOwner(), h.getName(), h.getDesc());
                        if (target == null) return h;
                        changed[0] = true;
                        // static replacement takes the receiver as first argument, which a method reference handles too
                        return new Handle(Opcodes.H_INVOKESTATIC, target[0], target[1], target[2], false);
                    }

                    @Override
                    public void visitFieldInsn(int opcode, @NotNull String owner, @NotNull String name, @NotNull String desc) {
                        if (opcode == Opcodes.GETSTATIC && owner.equals("java/lang/System")
                                && ((name.equals("out") || name.equals("err")) && desc.equals("Ljava/io/PrintStream;")
                                || name.equals("in") && desc.equals("Ljava/io/InputStream;"))) {
                            changed[0] = true;
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, IO, name, "()" + desc, false);
                            return;
                        }
                        super.visitFieldInsn(opcode, owner, name, desc);
                    }

                    @Override
                    public void visitMethodInsn(int opcode, @NotNull String owner, @NotNull String name, @NotNull String desc, boolean itf) {
                        if (opcode == Opcodes.INVOKESPECIAL && name.equals("<init>") && rewritePathConstructor(owner, desc)) {
                            changed[0] = true;
                            return;
                        }
                        String[] target = redirect(opcode, owner, name, desc);
                        if (target == null) {
                            super.visitMethodInsn(opcode, owner, name, desc, itf);
                        } else {
                            changed[0] = true;
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, target[0], target[1], target[2], false);
                        }
                    }

                    /** Makes the file name argument absolute right before the constructor runs; the object stays a plain JDK one. */
                    private boolean rewritePathConstructor(String owner, String desc) {
                        String key = owner + desc;
                        if (PATH_LAST.contains(key)) {
                            resolveTop();
                        } else if (PATH_SECOND_LAST.contains(key)) {
                            super.visitInsn(Opcodes.SWAP);
                            resolveTop();
                            super.visitInsn(Opcodes.SWAP);
                        } else if (key.equals("java/io/File(Ljava/lang/String;Ljava/lang/String;)V")
                                || key.equals("java/io/File(Ljava/io/File;Ljava/lang/String;)V")) {
                            // new File(parent, child) -> new File(HiveFs.join(parent, child))
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, FS, "join", "(" + desc.substring(1, desc.length() - 2) + ")" + STRING, false);
                            super.visitMethodInsn(Opcodes.INVOKESPECIAL, owner, "<init>", "(" + STRING + ")V", false);
                            return true;
                        } else {
                            return false;
                        }
                        super.visitMethodInsn(Opcodes.INVOKESPECIAL, owner, "<init>", desc, false);
                        return true;
                    }

                    private void resolveTop() {
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, FS, "resolve", "(" + STRING + ")" + STRING, false);
                    }
                };
            }
        }, 0);
        return changed[0] ? writer.toByteArray() : bytes;
    }

    /** @return {owner, name, descriptor} of the static replacement, or null to keep the call */
    static String[] redirect(int opcode, String owner, String name, String desc) {
        String[] fs = redirectFs(opcode, owner, name, desc);
        if (fs != null) return fs;
        String[] hook = redirectHook(opcode, owner, name, desc);
        return hook == null ? null : new String[]{HOOKS, hook[0], hook[1]};
    }

    /** Working-directory, standard-stream and JUL rewrites. */
    private static String[] redirectFs(int opcode, String owner, String name, String desc) {
        String call = name + desc;
        if (opcode == Opcodes.INVOKESTATIC && owner.equals("java/util/logging/Logger")) {
            return switch (call) {
                case "getLogger(Ljava/lang/String;)Ljava/util/logging/Logger;",
                     "getLogger(Ljava/lang/String;Ljava/lang/String;)Ljava/util/logging/Logger;",
                     "getGlobal()Ljava/util/logging/Logger;" -> new String[]{JUL, name, desc};
                default -> null;
            };
        }
        if (opcode == Opcodes.INVOKEVIRTUAL && owner.equals("java/util/logging/LogManager")) {
            return switch (call) {
                case "getLogger(Ljava/lang/String;)Ljava/util/logging/Logger;", "addLogger(Ljava/util/logging/Logger;)Z",
                     "getLoggerNames()Ljava/util/Enumeration;", "reset()V" ->
                        new String[]{JUL, name, "(Ljava/util/logging/LogManager;" + desc.substring(1)};
                default -> null;
            };
        }
        if (opcode == Opcodes.INVOKESTATIC) {
            if (owner.equals("java/nio/file/Files") && FilesMirror.METHODS.contains(call)) {
                return new String[]{FilesMirror.NAME, name, desc};
            }
            if ((owner.equals("java/nio/file/Path") && call.equals("of(Ljava/lang/String;[Ljava/lang/String;)Ljava/nio/file/Path;"))
                    || (owner.equals("java/nio/file/Paths") && call.equals("get(Ljava/lang/String;[Ljava/lang/String;)Ljava/nio/file/Path;"))) {
                return new String[]{FS, "path", desc};
            }
            if (owner.equals("java/lang/System") && (call.equals("setOut(Ljava/io/PrintStream;)V")
                    || call.equals("setErr(Ljava/io/PrintStream;)V") || call.equals("setIn(Ljava/io/InputStream;)V"))) {
                return new String[]{IO, name, desc};
            }
            if (owner.equals("java/lang/System") && name.equals("getProperty")
                    && (desc.equals("(Ljava/lang/String;)Ljava/lang/String;") || desc.equals("(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"))) {
                return new String[]{FS, "getProperty", desc};
            }
            return null;
        }
        if (opcode == Opcodes.INVOKEINTERFACE && owner.equals("java/nio/file/Path") && call.equals("toAbsolutePath()Ljava/nio/file/Path;")) {
            return new String[]{FS, "toAbsolutePath", "(Ljava/nio/file/Path;)Ljava/nio/file/Path;"};
        }
        if (opcode != Opcodes.INVOKEVIRTUAL) return null;
        return switch (owner) {
            case "java/nio/file/FileSystem" -> call.equals("getPath(Ljava/lang/String;[Ljava/lang/String;)Ljava/nio/file/Path;")
                    ? new String[]{FS, "getPath", "(Ljava/nio/file/FileSystem;" + desc.substring(1)} : null;
            case "java/io/File" -> switch (call) {
                case "getAbsolutePath()Ljava/lang/String;", "getCanonicalPath()Ljava/lang/String;",
                     "getAbsoluteFile()Ljava/io/File;", "getCanonicalFile()Ljava/io/File;" ->
                        new String[]{FS, name, "(Ljava/io/File;" + desc.substring(1)};
                default -> null;
            };
            case "java/lang/ProcessBuilder" -> call.equals("start()Ljava/lang/Process;")
                    ? new String[]{FS, "start", "(Ljava/lang/ProcessBuilder;)Ljava/lang/Process;"} : null;
            case "java/lang/Runtime" -> switch (call) {
                case "exec(Ljava/lang/String;)Ljava/lang/Process;", "exec(Ljava/lang/String;[Ljava/lang/String;)Ljava/lang/Process;",
                     "exec([Ljava/lang/String;)Ljava/lang/Process;", "exec([Ljava/lang/String;[Ljava/lang/String;)Ljava/lang/Process;" ->
                        new String[]{FS, "exec", "(Ljava/lang/Runtime;" + desc.substring(1)};
                default -> null;
            };
            default -> null;
        };
    }

    /** Lifecycle rewrites into {@link InstanceHooks}: {name, descriptor}. */
    private static String[] redirectHook(int opcode, String owner, String name, String desc) {
        if (opcode == Opcodes.INVOKESTATIC && owner.equals("java/lang/System")
                && name.equals("exit") && desc.equals("(I)V")) {
            return new String[]{"exit", "(I)V"};
        }
        if ((opcode == Opcodes.INVOKEVIRTUAL || opcode == Opcodes.INVOKESPECIAL) && name.equals("defineClass")
                && DEFINE_CLASS_DESCRIPTORS.contains(desc) && InstanceHooks.canDefineClasses()) {
            // the owner is usually the calling loader's own class (inherited method), so match on the descriptor;
            // the receiver becomes the first argument
            return new String[]{"defineClass", "(Ljava/lang/Object;" + desc.substring(1)};
        }
        if (opcode == Opcodes.INVOKESTATIC && owner.equals("java/util/concurrent/Executors") && name.equals("newSingleThreadExecutor")
                && (desc.equals("()Ljava/util/concurrent/ExecutorService;")
                || desc.equals("(Ljava/util/concurrent/ThreadFactory;)Ljava/util/concurrent/ExecutorService;"))) {
            return new String[]{"newSingleThreadExecutor", desc};
        }
        if (opcode == Opcodes.INVOKESTATIC && owner.equals("jdk/jfr/FlightRecorder")) {
            return switch (name + desc) {
                case "addListener(Ljdk/jfr/FlightRecorderListener;)V" -> new String[]{"addFlightRecorderListener", desc};
                case "addPeriodicEvent(Ljava/lang/Class;Ljava/lang/Runnable;)V" -> new String[]{"addPeriodicEvent", desc};
                case "register(Ljava/lang/Class;)V" -> new String[]{"registerEvent", desc};
                default -> null;
            };
        }
        if (opcode == Opcodes.INVOKEVIRTUAL && owner.equals("java/nio/channels/FileChannel")) {
            return switch (name + desc) {
                case "lock()Ljava/nio/channels/FileLock;" ->
                        new String[]{"lock", "(Ljava/nio/channels/FileChannel;)Ljava/nio/channels/FileLock;"};
                case "lock(JJZ)Ljava/nio/channels/FileLock;" ->
                        new String[]{"lock", "(Ljava/nio/channels/FileChannel;JJZ)Ljava/nio/channels/FileLock;"};
                case "tryLock()Ljava/nio/channels/FileLock;" ->
                        new String[]{"tryLock", "(Ljava/nio/channels/FileChannel;)Ljava/nio/channels/FileLock;"};
                case "tryLock(JJZ)Ljava/nio/channels/FileLock;" ->
                        new String[]{"tryLock", "(Ljava/nio/channels/FileChannel;JJZ)Ljava/nio/channels/FileLock;"};
                default -> null;
            };
        }
        if (opcode != Opcodes.INVOKEVIRTUAL || !owner.equals("java/lang/Runtime")) return null;
        return switch (name + desc) {
            case "exit(I)V" -> new String[]{"runtimeExit", "(Ljava/lang/Runtime;I)V"};
            case "halt(I)V" -> new String[]{"halt", "(Ljava/lang/Runtime;I)V"};
            case "addShutdownHook(Ljava/lang/Thread;)V" ->
                    new String[]{"addShutdownHook", "(Ljava/lang/Runtime;Ljava/lang/Thread;)V"};
            case "removeShutdownHook(Ljava/lang/Thread;)Z" ->
                    new String[]{"removeShutdownHook", "(Ljava/lang/Runtime;Ljava/lang/Thread;)Z"};
            default -> null;
        };
    }

    /** True if the constant pool references a member in {@link #TARGETS} or any {@code defineClass}. */
    static boolean mayNeedRewrite(ClassReader reader) {
        char[] buf = new char[reader.getMaxStringLength()];
        for (int i = 1; i < reader.getItemCount(); i++) {
            int offset = reader.getItem(i); // 0 for the unused slot after a long/double
            if (offset == 0) continue;
            int tag = reader.readByte(offset - 1);
            if (tag < 9 || tag > 11) continue; // Fieldref, Methodref, InterfaceMethodref (method handles point at these)
            String name = reader.readUTF8(reader.getItem(reader.readUnsignedShort(offset + 2)), buf);
            if (name.equals("defineClass") || TARGETS.contains(reader.readClass(offset, buf) + '.' + name)) return true;
        }
        return false;
    }

}
