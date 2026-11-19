package org.abdullahcxd.paperhive.host.instance;

import org.abdullahcxd.paperhive.host.software.PreparedServer;
import org.example.fakeserver.FakeServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.ref.WeakReference;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.StandardOpenOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ServerInstanceTest {

    private static final Duration WAIT = Duration.ofSeconds(30);

    @TempDir
    Path tmp;

    private final ApiGateLoader gate = new ApiGateLoader(getClass().getClassLoader());

    @Test
    void exitStopsOnlyTheInstanceAndRunsItsHooks() throws Exception {
        ServerInstance instance = instance();
        PrintStream hostOut = System.out; // after ServerInstance is set up (it installs the routing streams)
        Path markers = tmp.resolve("exit");

        instance.start(List.of("exit", markers.toString()));
        WeakReference<ClassLoader> loader = new WeakReference<>(instance.getClassLoader());

        assertTrue(instance.awaitTermination(WAIT), "instance should stop after System.exit");
        // still here, so the JVM didn't exit; a non-zero status is a crash, like a process
        assertEquals(ServerInstance.State.CRASHED, instance.getState());
        assertEquals(7, instance.getExitStatus());
        assertTrue(Files.exists(markers.resolve("hook-ran")), "registered hook must run on exit");
        assertFalse(Files.exists(markers.resolve("removed-hook-ran")), "removed hook must not run");
        assertFalse(Files.exists(markers.resolve("survived-exit")), "System.exit must not return");
        assertSame(hostOut, System.out, "the instance's System.setOut must not replace the JVM-wide System.out");
        assertLockReleased(markers.resolve("session.lock"));
        assertCollected(loader);
    }

    @Test
    void pluginExitStopsOnlyTheInstance() throws Exception {
        // a class defined by the instance's own loader (like a Paper plugin) must be rewritten too
        ServerInstance instance = instance();
        Path markers = tmp.resolve("plugin");

        instance.start(List.of("plugin-exit", markers.toString(), jarOf("org.example.fakeplugin", "FakePlugin", "fake-plugin.jar").toString()));
        WeakReference<ClassLoader> loader = new WeakReference<>(instance.getClassLoader());

        assertTrue(instance.awaitTermination(WAIT), "instance should stop after the plugin's System.exit");
        assertEquals(11, instance.getExitStatus());
        assertTrue(Files.exists(markers.resolve("hook-ran")));
        assertFalse(Files.exists(markers.resolve("survived-exit")));
        assertCollected(loader);
    }

    @Test
    void relativePathsResolveAgainstTheInstanceRoot() throws Exception {
        ServerInstance instance = instance();
        Path markers = tmp.resolve("files");
        Path cwd = Path.of("").toAbsolutePath();

        instance.start(List.of("files", markers.toString()));
        assertTrue(instance.awaitTermination(WAIT));

        Path root = instance.getRoot();
        for (String file : FakeServer.RELATIVE_FILES) {
            assertTrue(Files.isRegularFile(root.resolve(file)), file + " should be in the instance root");
            assertFalse(Files.exists(cwd.resolve(file)), file + " must not be written to the JVM cwd");
        }
        assertEquals(root.toString(), Files.readString(root.resolve("user-dir.txt")));
        assertEquals(root.toString(), Files.readString(root.resolve("absolute-empty.txt")));
        assertEquals(root.resolve("x").toString(), Files.readString(root.resolve("to-absolute.txt")));
    }

    @Test
    void eachConsoleOnlySeesItsOwnOutput() throws Exception {
        instance(); // sets up ServerInstance, which installs the routing streams
        PrintStream jvmOut = System.out;
        Map<String, List<String>> lines = new java.util.concurrent.ConcurrentHashMap<>();
        List<ServerInstance> instances = new java.util.ArrayList<>();
        for (String tag : List.of("A", "B")) {
            ServerInstance instance = instance("io" + tag);
            List<String> seen = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
            instance.getConsole().addListener((line, error) -> seen.add(line));
            lines.put(tag, seen);
            instances.add(instance);
        }
        for (int i = 0; i < 2; i++) {
            String tag = List.of("A", "B").get(i);
            Files.createDirectories(tmp.resolve("io" + tag));
            instances.get(i).start(List.of("io", tmp.resolve("io" + tag).toString(), tag)); // both at once
        }
        for (ServerInstance instance : instances) assertTrue(instance.awaitTermination(WAIT));

        for (String tag : List.of("A", "B")) {
            String other = tag.equals("A") ? "B" : "A";
            List<String> seen = lines.get(tag);
            for (String kind : List.of("out-", "err-", "thread-", "pool-")) {
                assertTrue(seen.contains(kind + tag), "console " + tag + " misses " + kind + tag + ": " + seen);
            }
            assertTrue(seen.stream().anyMatch(l -> l.contains("trace-" + tag)), "printStackTrace must reach its instance: " + seen);
            assertTrue(seen.contains("jul-" + tag + ":record-" + tag), "JUL record must reach its own root handler: " + seen);
            assertTrue(seen.contains("jul-" + tag + ":jdk-" + tag), "a JVM-wide JUL record from its thread must reach its own root: " + seen);
            assertTrue(seen.stream().noneMatch(l -> l.endsWith("-" + other) || l.startsWith("jul-" + other)),
                    "console " + tag + " got output of " + other + ": " + seen);
            assertTrue(seen.stream().noneMatch(l -> l.contains("redirected")), "System.setOut must redirect only the instance's own stream");
            assertEquals("redirected-" + tag, Files.readString(tmp.resolve("io" + tag).resolve("redirected.txt")).strip());
        }
        assertSame(jvmOut, System.out, "instances must not replace the JVM-wide System.out");
    }

    @Test
    void haltSkipsHooks() throws Exception {
        ServerInstance instance = instance();
        Path markers = tmp.resolve("halt");

        instance.start(List.of("halt", markers.toString()));

        assertTrue(instance.awaitTermination(WAIT));
        assertEquals(9, instance.getExitStatus());
        assertFalse(Files.exists(markers.resolve("hook-ran")), "halt must not run hooks");
    }

    @Test
    void endingWithoutExitStillStopsAndRunsHooks() throws Exception {
        // like Paper when the EULA isn't accepted: main returns, nothing calls System.exit
        ServerInstance instance = instance();
        Path markers = tmp.resolve("return");

        instance.start(List.of("return", markers.toString()));
        WeakReference<ClassLoader> loader = new WeakReference<>(instance.getClassLoader());

        assertTrue(instance.awaitTermination(WAIT));
        assertNull(instance.getExitStatus());
        assertEquals(ServerInstance.State.STOPPED, instance.getState());
        assertTrue(Files.exists(markers.resolve("hook-ran")), "a JVM would run hooks when the last thread ends");
        assertLockReleased(markers.resolve("session.lock"));
        assertCollected(loader);
    }

    @Test
    void stopInterruptsAServerWithoutBukkit() throws Exception {
        ServerInstance instance = instance();
        Path markers = tmp.resolve("serve");

        instance.start(List.of("serve", markers.toString()));
        Thread.sleep(300);
        assertEquals(ServerInstance.State.RUNNING, instance.getState());

        assertTrue(instance.stop(WAIT), "stop should interrupt the instance's threads");
        assertEquals(ServerInstance.State.STOPPED, instance.getState());
        assertTrue(Files.exists(markers.resolve("hook-ran")));
    }

    @Test
    void canRestartAndEveryGenerationIsCollected() throws Exception {
        ServerInstance instance = instance();
        for (int i = 0; i < 3; i++) {
            instance.start(List.of("return", tmp.resolve("gen" + i).toString()));
            WeakReference<ClassLoader> loader = new WeakReference<>(instance.getClassLoader());
            assertTrue(instance.awaitTermination(WAIT), "generation " + i);
            assertCollected(loader);
        }
    }

    @Test
    void cannotStartTwice() throws Exception {
        ServerInstance instance = instance();
        instance.start(List.of("serve", tmp.resolve("twice").toString()));
        try {
            assertThrows(IllegalStateException.class, () -> instance.start(List.of("serve", tmp.toString())));
        } finally {
            assertTrue(instance.stop(WAIT));
        }
    }

    @Test
    void lifecycleTransitionsAreObservable() throws Exception {
        ServerInstance instance = instance();
        List<String> seen = new CopyOnWriteArrayList<>();
        instance.addStateListener((i, from, to) -> seen.add(from + ">" + to));
        assertEquals(ServerInstance.State.CREATED, instance.getState());
        assertFalse(instance.isActive());

        instance.start(List.of("serve", tmp.resolve("life1").toString()));
        assertTrue(instance.isActive());
        assertTrue(instance.stop(WAIT));
        instance.start(List.of("exit", tmp.resolve("life2").toString()));
        assertTrue(instance.awaitTermination(WAIT));

        assertEquals(List.of("CREATED>STARTING", "STARTING>RUNNING", "RUNNING>STOPPING", "STOPPING>STOPPED",
                "STOPPED>STARTING", "STARTING>RUNNING", "RUNNING>STOPPING", "STOPPING>CRASHED"), seen);
        assertFalse(instance.isActive());
    }

    @Test
    void mainThrowingIsACrash() throws Exception {
        ServerInstance instance = instance();
        instance.start(List.of("throw", tmp.resolve("throw").toString()));
        assertTrue(instance.awaitTermination(WAIT));
        assertEquals(ServerInstance.State.CRASHED, instance.getState());
        assertNull(instance.getExitStatus());
    }

    @Test
    void crashRestartGivesUpAfterRepeatedCrashes() throws Exception {
        ServerInstance instance = instance("crashy", onCrash());
        AtomicInteger starts = new AtomicInteger();
        instance.addStateListener((i, from, to) -> {
            if (to == ServerInstance.State.STARTING) starts.incrementAndGet();
        });
        instance.start(List.of("throw", tmp.resolve("crashy").toString()));

        awaitInactive(instance);
        assertEquals(ServerInstance.MAX_CRASHES, starts.get(), "restarted until the crash limit, then gave up");
        assertEquals(ServerInstance.State.CRASHED, instance.getState());
    }

    @Test
    void stopCancelsAPendingCrashRestart() throws Exception {
        ServerInstance instance = instance("cancel", onCrash());
        instance.start(List.of("throw", tmp.resolve("cancel").toString()));
        assertTrue(instance.awaitTermination(WAIT));
        assertEquals(ServerInstance.State.CRASHED, instance.getState());
        assertTrue(instance.isActive(), "a restart is pending");

        assertTrue(instance.stop(WAIT));
        assertFalse(instance.isActive());
        Thread.sleep(ServerInstance.RESTART_DELAY.plusSeconds(1).toMillis());
        assertEquals(ServerInstance.State.CRASHED, instance.getState(), "the cancelled restart still ran");
    }

    @Test
    void stopForcesAServerThatWontEnd() throws Exception {
        ServerInstance instance = instance();
        instance.start(List.of("stubborn", tmp.resolve("stubborn").toString()));
        Thread.sleep(300);

        assertFalse(instance.stop(Duration.ofMillis(500)), "the stop had to be forced");
        assertEquals(ServerInstance.State.STOPPED, instance.getState());
        assertNull(instance.getClassLoader(), "the loader is dropped even though a thread leaked");
        assertFalse(instance.isActive());
    }

    @Test
    void optionsGoIntoTheArgsAndANewServerProperties() throws Exception {
        Path plugins = tmp.resolve("shared-plugins");
        ServerInstance instance = instance("opts", new ServerInstance.Options(25599, plugins, List.of("--extra"), ServerInstance.RestartPolicy.NEVER));
        List<String> args = instance.serverArgs();
        assertEquals(plugins.toAbsolutePath().toString(), args.get(args.indexOf("--plugins") + 1));
        assertEquals("25599", args.get(args.indexOf("--port") + 1));
        assertEquals("--extra", args.getLast());

        instance.start(List.of("return", tmp.resolve("opts-run").toString()));
        assertTrue(instance.awaitTermination(WAIT));
        Path properties = instance.getRoot().resolve("server.properties");
        assertEquals("server-port=25599\n", Files.readString(properties));

        Files.writeString(properties, "server-port=1\nmotd=mine\n");
        instance.start(List.of("return", tmp.resolve("opts-run2").toString()));
        assertTrue(instance.awaitTermination(WAIT));
        assertEquals("server-port=1\nmotd=mine\n", Files.readString(properties), "an existing server.properties is left alone");
    }

    private static ServerInstance.Options onCrash() {
        return new ServerInstance.Options(null, null, List.of(), ServerInstance.RestartPolicy.ON_CRASH);
    }

    private static void awaitInactive(ServerInstance instance) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (instance.isActive() && System.nanoTime() < deadline) Thread.sleep(100);
        assertFalse(instance.isActive(), "instance still active");
    }

    @Test
    void transformerRedirectsOnlyExitCalls() {
        assertArrayEquals(new String[]{InstanceTransformer.HOOKS, "exit", "(I)V"},
                InstanceTransformer.redirect(org.objectweb.asm.Opcodes.INVOKESTATIC, "java/lang/System", "exit", "(I)V"));
        assertNotNull(InstanceTransformer.redirect(org.objectweb.asm.Opcodes.INVOKEVIRTUAL, "java/lang/Runtime", "halt", "(I)V"));
        assertNull(InstanceTransformer.redirect(org.objectweb.asm.Opcodes.INVOKESTATIC, "java/lang/System", "gc", "()V"));
        assertNull(InstanceTransformer.redirect(org.objectweb.asm.Opcodes.INVOKEVIRTUAL, "java/lang/Runtime", "availableProcessors", "()I"));
    }

    private ServerInstance instance() throws IOException {
        return instance("test");
    }

    private ServerInstance instance(String name) throws IOException {
        return instance(name, ServerInstance.Options.DEFAULT);
    }

    private ServerInstance instance(String name, ServerInstance.Options options) throws IOException {
        PreparedServer server = new PreparedServer(fakeServerJar(), List.of(), FakeServer.class.getName(), () -> {});
        return new ServerInstance(name, tmp.resolve("root-" + name), server, gate, options);
    }

    private Path fakeServerJar() throws IOException {
        return jarOf(FakeServer.class.getPackageName(), "FakeServer", "fake-server.jar");
    }

    /** The test classes of {@code pkg} whose names start with {@code prefix} (incl. nested/lambda classes), alone in a jar. */
    private Path jarOf(String pkg, String prefix, String jarName) throws IOException {
        Path jar = tmp.resolve(jarName);
        if (Files.exists(jar)) return jar;
        String dir = pkg.replace('.', '/');
        Path classes = Path.of(FakeServer.class.getProtectionDomain().getCodeSource().getLocation().getPath().replaceFirst("^/(\\w:)", "$1"))
                .resolve(dir);
        try (OutputStream file = Files.newOutputStream(jar);
             JarOutputStream out = new JarOutputStream(file);
             Stream<Path> list = Files.list(classes)) {
            for (Path cls : list.filter(p -> p.getFileName().toString().startsWith(prefix)).toList()) {
                out.putNextEntry(new JarEntry(dir + "/" + cls.getFileName()));
                try (InputStream in = Files.newInputStream(cls)) {
                    in.transferTo(out);
                }
                out.closeEntry();
            }
        }
        return jar;
    }

    private static void assertLockReleased(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE);
             FileLock lock = channel.tryLock()) {
            assertNotNull(lock, "file lock left by the instance must be released on stop");
        } catch (OverlappingFileLockException e) {
            fail("file lock left by the instance is still held: " + file);
        }
    }

    private static void assertCollected(WeakReference<?> ref) throws InterruptedException {
        for (int i = 0; i < 50 && ref.get() != null; i++) {
            System.gc();
            Thread.sleep(100);
        }
        assertNull(ref.get(), "the instance's class loader is still reachable after stop");
    }

}
