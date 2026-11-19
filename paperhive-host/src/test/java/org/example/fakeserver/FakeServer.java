package org.example.fakeserver;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.security.CodeSigner;
import java.security.CodeSource;
import java.security.SecureClassLoader;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Logger;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Stand-in for a server, packed into a jar by {@code ServerInstanceTest} and run in an instance loader.
 * Outside {@code org.abdullahcxd.paperhive.*} so the instance gets its own (rewritten) copy.
 * <p>
 * args: {@code <mode> <marker dir> [plugin jar]}; modes: exit, halt, return (like the EULA path), serve (until
 * interrupted), plugin-exit (a plugin, loaded by its own loader, calls System.exit), files (writes
 * {@link #RELATIVE_FILES} through every path API with relative paths, then returns), io (args[2] = tag: prints
 * {@code <kind>-<tag>} through System.out/err, printStackTrace, another thread and a pool, then redirects its own
 * System.out to {@code redirected.txt}), throw (main throws), stubborn (a thread that ignores interrupts for 25s).
 * Every mode also leaves an idle thread pool, a file lock and a registered JDBC driver behind, like Paper does.
 */
public final class FakeServer {

    private static volatile boolean running = true;

    /** Written with relative paths in "files" mode; each must land in the instance root. */
    public static final String[] RELATIVE_FILES = {"file.txt", "sub/child.txt", "path-of.txt", "paths-get.txt", "derived.txt",
            "fs-get-path.txt", "fos.txt", "fos-append.txt", "raf.txt", "writer.txt", "user-dir.txt",
            "absolute-empty.txt", "to-absolute.txt"};

    public static void main(String[] args) {
        String mode = args[0];
        Path markers = Path.of(args[1]);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> touch(markers, "hook-ran")));
        Thread removed = new Thread(() -> touch(markers, "removed-hook-ran"));
        Runtime.getRuntime().addShutdownHook(removed);
        if (!Runtime.getRuntime().removeShutdownHook(removed)) throw new AssertionError("hook not removed");

        // a JVM-global the instance points at its own class; the host must restore it on stop
        System.setOut(new CapturingOut(System.out));

        Thread worker = new Thread(FakeServer::sleepWhileRunning, "fake-worker");
        worker.start();
        // daemon threads simply die on a real JVM exit; here the host has to interrupt them
        Thread daemon = new Thread(FakeServer::sleepUntilInterrupted, "fake-daemon");
        daemon.setDaemon(true);
        daemon.start();

        // an idle pool thread ignores interrupts; only shutting its pool down ends it.
        // daemon, so like a real JVM the instance can still end without System.exit ("return" mode)
        ExecutorService pool = Executors.newFixedThreadPool(1, r -> {
            Thread t = new Thread(r, "fake-pool");
            t.setDaemon(true);
            return t;
        });
        pool.execute(() -> {});
        // a lock left for process exit to release, like a world's session.lock
        lockForever(markers.resolve("session.lock"));
        // DriverManager holds registered drivers JVM-wide; the host must deregister it or the loader leaks
        try {
            DriverManager.registerDriver(new FakeDriver());
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        switch (mode) {
            case "exit" -> new Thread(() -> {
                pause();
                running = false;
                System.exit(7);
                touch(markers, "survived-exit"); // must never run
            }, "fake-server").start();
            case "halt" -> new Thread(() -> {
                pause();
                running = false;
                Runtime.getRuntime().halt(9);
            }, "fake-server").start();
            case "return" -> {
                running = false;
                daemon.interrupt();
            }
            case "serve" -> new Thread(FakeServer::sleepUntilInterrupted, "fake-server").start();
            case "throw" -> { // like a server failing to boot: main throws, nothing calls System.exit
                running = false;
                daemon.interrupt();
                throw new IllegalStateException("fake boot failure");
            }
            case "stubborn" -> { // ignores interrupts for a while, so the host has to give up on it
                running = false;
                new Thread(() -> {
                    long end = System.nanoTime() + 25_000_000_000L;
                    while (System.nanoTime() < end) {
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException ignored) {
                        }
                    }
                }, "fake-stubborn").start();
            }
            case "io" -> {
                String tag = args[2];
                System.out.println("out-" + tag);
                System.err.println("err-" + tag);
                new Exception("trace-" + tag).printStackTrace(); // the JDK writes this to the JVM-wide System.err
                // like Paper: a handler on the JUL root logger, then log through a child logger. With one JVM-wide
                // root, both instances' handlers would get both records (the pause lets both register first)
                java.util.logging.Logger julRoot = java.util.logging.Logger.getLogger("");
                julRoot.addHandler(new java.util.logging.Handler() {
                    @Override public void publish(java.util.logging.LogRecord record) { System.out.println("jul-" + tag + ":" + record.getMessage()); }
                    @Override public void flush() {}
                    @Override public void close() {}
                });
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                java.util.logging.Logger.getLogger("fake.child").info("record-" + tag);
                // like JDK-internal loggers: System.Logger isn't rewritten and logs into the JVM-wide JUL tree
                System.getLogger("fake.jdk").log(System.Logger.Level.INFO, "jdk-" + tag);
                Thread thread = new Thread(() -> System.out.println("thread-" + tag));
                thread.start();
                try {
                    thread.join();
                    pool.submit(() -> System.out.println("pool-" + tag)).get();
                    try (PrintStream redirected = new PrintStream(new FileOutputStream(markers.resolve("redirected.txt").toFile()), true)) {
                        System.setOut(redirected);
                        System.out.println("redirected-" + tag);
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                running = false;
                daemon.interrupt();
            }
            case "files" -> {
                try {
                    writeRelativeFiles();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                running = false;
                daemon.interrupt();
            }
            case "plugin-exit" -> new Thread(() -> {
                pause();
                running = false;
                try {
                    ClassLoader plugins = new FakePluginLoader(Path.of(args[2]), FakeServer.class.getClassLoader());
                    plugins.loadClass("org.example.fakeplugin.FakePlugin").getMethod("run").invoke(null);
                } catch (java.lang.reflect.InvocationTargetException e) {
                    // the plugin's System.exit unwinds through reflection as the cause; let it keep unwinding
                    if (e.getCause() instanceof Error error) throw error;
                    throw new RuntimeException(e);
                } catch (ReflectiveOperationException e) {
                    throw new RuntimeException(e);
                }
                touch(markers, "survived-exit"); // must never run
            }, "fake-server").start();
            default -> throw new IllegalArgumentException(mode);
        }
    }

    private static void writeRelativeFiles() throws IOException {
        try (FileOutputStream out = new FileOutputStream(new File("file.txt"))) {
            out.write(1);
        }
        new File("sub").mkdirs();
        Files.writeString(new File("sub", "child.txt").toPath(), "x");
        Files.writeString(Path.of("path-of.txt"), "x");
        Files.writeString(java.nio.file.Paths.get("paths-get.txt"), "x");
        Files.writeString(java.nio.file.FileSystems.getDefault().getPath("fs-get-path.txt"), "x");
        try (FileOutputStream out = new FileOutputStream("fos.txt")) {
            out.write(1);
        }
        try (FileOutputStream out = new FileOutputStream("fos-append.txt", true)) {
            out.write(1);
        }
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile("raf.txt", "rw")) {
            raf.write(1);
        }
        try (java.io.FileWriter writer = new java.io.FileWriter("writer.txt")) {
            writer.write("x");
        }
        Files.writeString(Path.of("user-dir.txt"), System.getProperty("user.dir"));
        Files.writeString(Path.of("absolute-empty.txt"), new File("").getAbsolutePath());
        // a relative Path the JDK derives (getFileName), opened straight through Files
        Files.writeString(Path.of("derived.txt").getFileName(), "x");
        // a relative Path the JDK made, not created through a rewritten call
        Path relative = Path.of("a").getFileSystem().getPath("x").getFileName();
        Files.writeString(Path.of("to-absolute.txt"), relative.toAbsolutePath().toString());
    }

    @SuppressWarnings("resource")
    private static void lockForever(Path file) {
        try {
            Files.createDirectories(file.getParent());
            FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            if (channel.tryLock() == null) throw new IllegalStateException("already locked: " + file);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void sleepWhileRunning() {
        while (running) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private static void sleepUntilInterrupted() {
        try {
            Thread.sleep(Long.MAX_VALUE);
        } catch (InterruptedException ignored) {
        }
    }

    private static void pause() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException ignored) {
        }
    }

    private static void touch(Path dir, String name) {
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(name), name);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** Defines plugin classes itself, like Paper's plugin loaders: {@code defineClass(name, bytes, 0, len, codeSource)}. */
    private static final class FakePluginLoader extends SecureClassLoader {
        private final Path jar;

        private FakePluginLoader(Path jar, ClassLoader parent) {
            super(parent);
            this.jar = jar;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            try (JarFile file = new JarFile(jar.toFile())) {
                JarEntry entry = file.getJarEntry(name.replace('.', '/') + ".class");
                if (entry == null) throw new ClassNotFoundException(name);
                byte[] bytes;
                try (InputStream in = file.getInputStream(entry)) {
                    bytes = in.readAllBytes();
                }
                return defineClass(name, bytes, 0, bytes.length, new CodeSource(jar.toUri().toURL(), (CodeSigner[]) null));
            } catch (IOException e) {
                throw new ClassNotFoundException(name, e);
            }
        }
    }

    public static final class FakeDriver implements Driver {
        @Override public Connection connect(String url, Properties info) { return null; }
        @Override public boolean acceptsURL(String url) { return false; }
        @Override public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) { return new DriverPropertyInfo[0]; }
        @Override public int getMajorVersion() { return 1; }
        @Override public int getMinorVersion() { return 0; }
        @Override public boolean jdbcCompliant() { return false; }
        @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { throw new SQLFeatureNotSupportedException(); }
    }

    private static final class CapturingOut extends PrintStream {
        private CapturingOut(PrintStream delegate) {
            super(delegate, true);
        }
    }

}
