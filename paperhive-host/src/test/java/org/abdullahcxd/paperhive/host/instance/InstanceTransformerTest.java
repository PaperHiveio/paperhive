package org.abdullahcxd.paperhive.host.instance;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.io.InputStream;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** The constant-pool pre-check must let through every class that {@link InstanceTransformer} would change. */
class InstanceTransformerTest {

    /** {opcode, owner, name, desc} of every call {@link InstanceTransformer#redirect} rewrites. */
    private static final List<Object[]> REDIRECTED_CALLS = List.of(
            new Object[]{Opcodes.INVOKESTATIC, "java/lang/System", "exit", "(I)V"},
            new Object[]{Opcodes.INVOKESTATIC, "java/lang/System", "setOut", "(Ljava/io/PrintStream;)V"},
            new Object[]{Opcodes.INVOKESTATIC, "java/lang/System", "setErr", "(Ljava/io/PrintStream;)V"},
            new Object[]{Opcodes.INVOKESTATIC, "java/lang/System", "setIn", "(Ljava/io/InputStream;)V"},
            new Object[]{Opcodes.INVOKESTATIC, "java/lang/System", "getProperty", "(Ljava/lang/String;)Ljava/lang/String;"},
            new Object[]{Opcodes.INVOKEVIRTUAL, "java/lang/Runtime", "exit", "(I)V"},
            new Object[]{Opcodes.INVOKEVIRTUAL, "java/lang/Runtime", "halt", "(I)V"},
            new Object[]{Opcodes.INVOKEVIRTUAL, "java/lang/Runtime", "addShutdownHook", "(Ljava/lang/Thread;)V"},
            new Object[]{Opcodes.INVOKEVIRTUAL, "java/lang/Runtime", "removeShutdownHook", "(Ljava/lang/Thread;)Z"},
            new Object[]{Opcodes.INVOKEVIRTUAL, "java/lang/Runtime", "exec", "(Ljava/lang/String;)Ljava/lang/Process;"},
            new Object[]{Opcodes.INVOKEVIRTUAL, "java/lang/ProcessBuilder", "start", "()Ljava/lang/Process;"},
            new Object[]{Opcodes.INVOKESTATIC, "java/util/concurrent/Executors", "newSingleThreadExecutor", "()Ljava/util/concurrent/ExecutorService;"},
            new Object[]{Opcodes.INVOKESTATIC, "jdk/jfr/FlightRecorder", "addListener", "(Ljdk/jfr/FlightRecorderListener;)V"},
            new Object[]{Opcodes.INVOKESTATIC, "jdk/jfr/FlightRecorder", "addPeriodicEvent", "(Ljava/lang/Class;Ljava/lang/Runnable;)V"},
            new Object[]{Opcodes.INVOKESTATIC, "jdk/jfr/FlightRecorder", "register", "(Ljava/lang/Class;)V"},
            new Object[]{Opcodes.INVOKEVIRTUAL, "java/nio/channels/FileChannel", "lock", "()Ljava/nio/channels/FileLock;"},
            new Object[]{Opcodes.INVOKEVIRTUAL, "java/nio/channels/FileChannel", "tryLock", "()Ljava/nio/channels/FileLock;"},
            new Object[]{Opcodes.INVOKESTATIC, "java/nio/file/Path", "of", "(Ljava/lang/String;[Ljava/lang/String;)Ljava/nio/file/Path;"},
            new Object[]{Opcodes.INVOKEINTERFACE, "java/nio/file/Path", "toAbsolutePath", "()Ljava/nio/file/Path;"},
            new Object[]{Opcodes.INVOKESTATIC, "java/nio/file/Paths", "get", "(Ljava/lang/String;[Ljava/lang/String;)Ljava/nio/file/Path;"},
            new Object[]{Opcodes.INVOKEVIRTUAL, "java/nio/file/FileSystem", "getPath", "(Ljava/lang/String;[Ljava/lang/String;)Ljava/nio/file/Path;"},
            new Object[]{Opcodes.INVOKEVIRTUAL, "java/io/File", "getAbsolutePath", "()Ljava/lang/String;"},
            new Object[]{Opcodes.INVOKEVIRTUAL, "java/io/File", "getCanonicalFile", "()Ljava/io/File;"},
            new Object[]{Opcodes.INVOKESTATIC, "java/util/logging/Logger", "getLogger", "(Ljava/lang/String;)Ljava/util/logging/Logger;"},
            new Object[]{Opcodes.INVOKESTATIC, "java/util/logging/Logger", "getGlobal", "()Ljava/util/logging/Logger;"},
            new Object[]{Opcodes.INVOKEVIRTUAL, "java/util/logging/LogManager", "getLogger", "(Ljava/lang/String;)Ljava/util/logging/Logger;"},
            new Object[]{Opcodes.INVOKEVIRTUAL, "java/util/logging/LogManager", "addLogger", "(Ljava/util/logging/Logger;)Z"},
            new Object[]{Opcodes.INVOKEVIRTUAL, "java/util/logging/LogManager", "getLoggerNames", "()Ljava/util/Enumeration;"},
            new Object[]{Opcodes.INVOKEVIRTUAL, "java/util/logging/LogManager", "reset", "()V"});

    @Test
    void preCheckCatchesEveryRedirectedCall() {
        for (Object[] call : REDIRECTED_CALLS) {
            int opcode = (int) call[0];
            String owner = (String) call[1], name = (String) call[2], desc = (String) call[3];
            assertNotNull(InstanceTransformer.redirect(opcode, owner, name, desc), owner + "." + name + " is no longer redirected");
            assertTrue(mayNeedRewrite(cw -> cw.newMethod(owner, name, desc, opcode == Opcodes.INVOKEINTERFACE)),
                    owner + "." + name + " slips past the pre-check");
        }
    }

    @Test
    void preCheckCatchesStreamsConstructorsAndDefineClass() {
        for (String stream : List.of("out", "err")) {
            assertTrue(mayNeedRewrite(cw -> cw.newField("java/lang/System", stream, "Ljava/io/PrintStream;")), "System." + stream);
        }
        assertTrue(mayNeedRewrite(cw -> cw.newField("java/lang/System", "in", "Ljava/io/InputStream;")), "System.in");
        for (String owner : List.of("java/io/File", "java/io/FileInputStream", "java/io/FileOutputStream", "java/io/FileReader",
                "java/io/FileWriter", "java/io/PrintWriter", "java/io/PrintStream", "java/io/RandomAccessFile")) {
            assertTrue(mayNeedRewrite(cw -> cw.newMethod(owner, "<init>", "(Ljava/lang/String;)V", false)), owner + " constructor");
        }
        // inherited, so the owner is whatever loader class makes the call
        assertTrue(mayNeedRewrite(cw -> cw.newMethod("com/example/PluginLoader", "defineClass", "(Ljava/lang/String;[BII)Ljava/lang/Class;", false)));
    }

    @Test
    void preCheckSkipsClassesThatOnlyMentionTheTypes() throws Exception {
        // takes and prints to streams, locks, has "exit" in names: none of that is a call that gets rewritten
        assertFalse(mayNeedRewrite(cw -> {
            cw.newMethod("java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
            cw.newMethod("java/io/InputStream", "read", "()I", false);
            cw.newMethod("java/util/concurrent/locks/ReentrantLock", "lock", "()V", false);
            cw.newMethod("com/example/Game", "exit", "(I)V", false);
            cw.newField("com/example/Game", "out", "Ljava/io/PrintStream;");
            cw.newConst(1L); // a long takes two constant pool slots
        }));
        // and a real class with none of these calls is left alone byte for byte
        byte[] bytes;
        try (InputStream in = getClass().getResourceAsStream("/org/example/isolated/IsolatedCounter.class")) {
            bytes = in.readAllBytes();
        }
        assertSame(bytes, InstanceTransformer.transform(bytes));
    }

    private static boolean mayNeedRewrite(Consumer<ClassWriter> constants) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "test/Probe", null, "java/lang/Object", null);
        constants.accept(cw);
        cw.visitEnd();
        return InstanceTransformer.mayNeedRewrite(new ClassReader(cw.toByteArray()));
    }

}
