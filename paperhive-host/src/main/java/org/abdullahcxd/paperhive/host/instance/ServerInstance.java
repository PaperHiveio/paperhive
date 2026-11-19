package org.abdullahcxd.paperhive.host.instance;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.abdullahcxd.paperhive.host.paperclip.PatchedServer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import javax.management.JMException;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Provider;
import java.security.Security;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.LogManager;
import java.util.logging.Logger;

/**
 * One Paper server running inside this JVM, in its own {@link InstanceClassLoader}.
 * Boots like Paperclip does: the real main class, on a dedicated thread, with the context loader set.
 * <p>
 * Stopping works like a JVM exit scoped to the instance: {@code System.exit} (rewritten by {@link InstanceTransformer})
 * runs the instance's own shutdown hooks and unwinds the calling thread; remaining instance threads get a grace
 * period, then are interrupted. Once none are left, JVM-wide state the instance changed is undone and the loader
 * is closed and dropped, so it can be garbage collected. The instance can then be started again.
 */
@Slf4j
public final class ServerInstance {

    /**
     * {@code CREATED -> STARTING -> RUNNING -> STOPPING -> STOPPED | CRASHED}, and from {@code STOPPED}/{@code CRASHED}
     * back to {@code STARTING}. A run that ends early skips the states it never reached (e.g. {@code STARTING -> CRASHED}).
     * {@code RUNNING} means booted: Paper's {@code MinecraftServer.isReady()}, or right away for a server without Bukkit.
     * {@code CRASHED}: the run ended with a non-zero exit status (Paper uses 70 after a crash) or its main method threw.
     */
    public enum State { CREATED, STARTING, RUNNING, STOPPING, STOPPED, CRASHED }

    public enum RestartPolicy { NEVER, ON_CRASH }

    /**
     * How the instance runs.
     *
     * @param port    passed as {@code --port} and written into a new {@code server.properties}; null leaves it to Paper
     * @param plugins the plugins folder (relative to the host's working directory), or null for {@code <root>/plugins}
     * @param args    appended to {@link #serverArgs()}
     * @param restartDelay with {@link RestartPolicy#ON_CRASH}: wait this long, and give up after {@code maxCrashes}
     *                     within {@code crashWindow}
     */
    public record Options(@Nullable Integer port, @Nullable Path plugins, List<String> args, RestartPolicy restart,
                          Duration restartDelay, int maxCrashes, Duration crashWindow) {
        // here, not in ServerInstance: making Options must not run ServerInstance's static setup
        static final Duration DEFAULT_RESTART_DELAY = Duration.ofSeconds(3);
        static final int DEFAULT_MAX_CRASHES = 3;
        static final Duration DEFAULT_CRASH_WINDOW = Duration.ofMinutes(10);
        public static final Options DEFAULT = new Options(null, null, List.of(), RestartPolicy.NEVER);

        public Options(@Nullable Integer port, @Nullable Path plugins, List<String> args, RestartPolicy restart) {
            this(port, plugins, args, restart, DEFAULT_RESTART_DELAY, DEFAULT_MAX_CRASHES, DEFAULT_CRASH_WINDOW);
        }
    }

    /** Called on every state change, on the thread making it (keep it quick, don't block). */
    @FunctionalInterface
    public interface StateListener {
        void changed(ServerInstance instance, State from, State to);
    }

    /** The defaults of {@link Options#restartDelay()}, {@link Options#maxCrashes()} and {@link Options#crashWindow()}. */
    static final Duration RESTART_DELAY = Options.DEFAULT_RESTART_DELAY;
    static final int MAX_CRASHES = Options.DEFAULT_MAX_CRASHES;
    static final Duration CRASH_WINDOW = Options.DEFAULT_CRASH_WINDOW;

    // captured before any instance runs, so it can be restored if an instance replaces it
    private static final Thread.UncaughtExceptionHandler HOST_DEFAULT_HANDLER = Thread.getDefaultUncaughtExceptionHandler();
    private static final ThreadGroup HOST_GROUP = Thread.currentThread().getThreadGroup();

    static {
        // Netty's native transports (epoll/kqueue) would be extracted and loaded once per instance loader, on every
        // restart; NIO needs no native library and is plenty for a handful of servers. Respect an explicit choice.
        if (System.getProperty("io.netty.transport.noNative") == null) System.setProperty("io.netty.transport.noNative", "true");
        // initialise JUL now, from the host: whoever touches it first picks the JVM-wide LogManager class,
        // and that must not be an instance's class
        LogManager.getLogManager();
        HiveJul.install();
        // after `log` above set up the host's logging, so its console appender keeps the real stdout
        HiveIo.install();
    }

    private static final Duration HOOK_TIMEOUT = Duration.ofSeconds(30);
    /** After an exit (and its hooks), how long remaining instance threads get before they're interrupted. */
    static final Duration EXIT_GRACE = Duration.ofSeconds(5);
    /** After interrupting, how long before remaining threads are reported as leaked and the instance is dropped anyway. */
    static final Duration LEAK_TIMEOUT = Duration.ofSeconds(10);

    @Getter
    private final String name;
    @Getter
    private final Path root;
    private final PatchedServer server;
    private final ApiGateLoader gate;
    @Getter
    private final Options options;
    private final List<StateListener> listeners = new CopyOnWriteArrayList<>();
    private List<String> lastArgs = List.of();
    private final Deque<Long> crashes = new ArrayDeque<>(); // nanoTimes of recent crashes, for the restart policy
    private @Nullable Object pendingRestart; // identity of the scheduled crash restart, null if none

    // one "generation" per start; all reset by start() and dropped by finish()
    @Getter
    private volatile State state = State.CREATED;
    private volatile boolean mainThrew;
    private long startedAt;
    @Getter
    private volatile @Nullable InstanceClassLoader classLoader;
    private ThreadGroup group;
    private Thread mainThread;
    private final List<Thread> shutdownHooks = new ArrayList<>();
    // weak: tracking must not keep a channel alive that the server already dropped
    private final Set<FileChannel> lockedChannels = Collections.newSetFromMap(new WeakHashMap<>());
    /** Undoes JVM-wide registrations the instance made (see {@link InstanceTransformer}), run when it stops. */
    private final List<Runnable> onStop = new ArrayList<>();
    private final Set<ExecutorService> trackedExecutors = Collections.newSetFromMap(new WeakHashMap<>());
    // loaders inside the instance that defined classes (Paper's plugin loaders, server halves'): JDBC cleanup visits each
    private final Set<ClassLoader> definingLoaders = Collections.newSetFromMap(new WeakHashMap<>());
    private boolean exiting;
    private volatile long exitedAt; // nanoTime once exit hooks finished, 0 before
    @Getter
    private volatile @Nullable Integer exitStatus;
    /** This instance's console output; the same object across restarts. */
    @Getter
    private final InstanceConsole console;
    private volatile InstanceConsole.Sinks sinks;
    private volatile HiveIo.@Nullable InstanceIo io;
    private volatile HiveJul.@Nullable Namespace jul;
    private volatile CountDownLatch terminated = new CountDownLatch(0);

    public ServerInstance(String name, @NotNull Path root, PatchedServer server, ApiGateLoader gate) {
        this(name, root, server, gate, Options.DEFAULT);
    }

    public ServerInstance(String name, @NotNull Path root, PatchedServer server, ApiGateLoader gate, Options options) {
        this.name = name;
        this.root = root.toAbsolutePath().normalize();
        this.server = server;
        this.gate = gate;
        this.options = options;
        this.console = new InstanceConsole(name);
    }

    /** Paper's own flags, pointed at this instance's root (relative paths resolve against it anyway, see {@link HiveFs}). */
    public @NotNull @Unmodifiable List<String> serverArgs() {
        Path plugins = options.plugins() == null ? root.resolve("plugins") : options.plugins().toAbsolutePath().normalize();
        List<String> args = new ArrayList<>(List.of(
                "--nogui",
                // the host owns stdin and forwards commands (dispatchCommand); Paper's own console reader would
                // block on System.in forever and keep the instance alive after stop
                "--noconsole",
                "--universe", root.toString(),
                "--plugins", plugins.toString(),
                "--config", root.resolve("server.properties").toString(),
                "--bukkit-settings", root.resolve("bukkit.yml").toString(),
                "--spigot-settings", root.resolve("spigot.yml").toString(),
                "--commands-settings", root.resolve("commands.yml").toString(),
                "--paper-settings-directory", root.resolve("config").toString()));
        if (options.port() != null) args.addAll(List.of("--port", options.port().toString()));
        args.addAll(options.args());
        return List.copyOf(args);
    }

    public void addStateListener(StateListener listener) {
        listeners.add(listener);
    }

    public void removeStateListener(StateListener listener) {
        listeners.remove(listener);
    }

    /** Running or on its way up or down, or about to be restarted after a crash. */
    public boolean isActive() {
        State s = state;
        return s == State.STARTING || s == State.RUNNING || s == State.STOPPING || pendingRestart != null;
    }

    /** How long the current run has been going, or null if it isn't running. */
    public @Nullable Duration uptime() {
        State s = state;
        return s == State.STARTING || s == State.RUNNING || s == State.STOPPING ? Duration.ofNanos(System.nanoTime() - startedAt) : null;
    }

    public void start() {
        start(serverArgs());
    }

    public synchronized void start(List<String> args) {
        if (state == State.STARTING || state == State.RUNNING || state == State.STOPPING) {
            throw new IllegalStateException("Instance " + name + " is " + state);
        }

        try {
            Files.createDirectories(root); // the instance's working directory; relative paths resolve against it
            Path properties = root.resolve("server.properties");
            if (options.port() != null && Files.notExists(properties)) {
                Files.writeString(properties, "server-port=" + options.port() + "\n");
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create root " + root + " of instance " + name, e);
        }
        lastArgs = List.copyOf(args);
        mainThrew = false;
        exiting = false;
        exitedAt = 0;
        exitStatus = null;
        shutdownHooks.clear();
        lockedChannels.clear();
        onStop.clear();
        sinks = console.newSinks();
        io = new HiveIo.InstanceIo(sinks);
        jul = new HiveJul.Namespace();
        trackedExecutors.clear();
        group = new InstanceThreadGroup("hive-" + name);
        InstanceClassLoader loader = new InstanceClassLoader(name, urls(), gate, this);
        classLoader = loader;

        MethodHandle main;
        try {
            Class<?> mainClass = Class.forName(server.mainClass(), false, loader);
            main = MethodHandles.publicLookup().findStatic(mainClass, "main", MethodType.methodType(void.class, String[].class));
        } catch (ReflectiveOperationException e) {
            classLoader = null;
            closeQuietly(loader);
            throw new IllegalStateException("Cannot find " + server.mainClass() + ".main(String[]) for " + name, e);
        }

        String[] argv = args.toArray(String[]::new);
        Thread main0 = createMain0(main, argv, loader);
        mainThread = main0;
        terminated = new CountDownLatch(1);
        startedAt = System.nanoTime();
        setState(State.STARTING);
        pendingRestart = null; // after STARTING, so isActive() never reads false in between
        if (!isBukkit(loader)) setState(State.RUNNING); // no way to tell when it's booted

        log.info("Starting instance {} from {}", name, root);
        main0.start();
        // a host thread (explicit group and loader), even if start() is called from another instance's thread
        Thread reaper = new Thread(HOST_GROUP, () -> reap(loader, main0), "hive-" + name + "-reaper");
        reaper.setDaemon(true);
        reaper.setContextClassLoader(ServerInstance.class.getClassLoader());
        reaper.start();
    }

    public @NotNull Thread createMain0(MethodHandle main, Object argv, InstanceClassLoader loader) {
        Thread main0 = new Thread(group, () -> {
            try {
                main.invoke(argv);
            } catch (InstanceExit ignored) {
                // exited during boot
            } catch (Throwable t) {
                synchronized (this) {
                    // once stopping, main failing (e.g. "Shutdown in progress" adding a hook) is the stop's doing
                    if (!exiting) mainThrew = true;
                }
                log.error("Instance {} crashed in main", name, t);
            }
        }, "hive-" + name + "-main");
        main0.setContextClassLoader(loader);
        return main0;
    }

    /**
     * Asks the server to shut down (like the {@code stop} command) and waits for it.
     * If it doesn't stop in time, its threads are interrupted and, failing that, reported as leaked.
     *
     * @return true if the instance stopped within {@code timeout} without being forced
     */
    public boolean stop(Duration timeout) {
        InstanceClassLoader loader;
        CountDownLatch done;
        boolean alreadyExiting;
        synchronized (this) {
            pendingRestart = null; // a stop also cancels a scheduled crash restart
            if (state != State.STARTING && state != State.RUNNING && state != State.STOPPING) return true;
            loader = classLoader;
            done = terminated;
            alreadyExiting = exiting;
            setState(State.STOPPING);
        }
        if (!alreadyExiting && loader != null) requestShutdown(loader);
        if (await(done, timeout)) return true;

        log.warn("Instance {} did not stop within {}, forcing it", name, timeout);
        List<Thread> hooks = beginExit();
        if (hooks != null) runHooks(hooks);
        exitedAt = System.nanoTime() - EXIT_GRACE.toNanos(); // let the reaper interrupt right away
        await(done, LEAK_TIMEOUT.plusSeconds(5));
        return false;
    }

    /** Waits until the instance has fully stopped (also after it exits on its own, e.g. the {@code stop} command). */
    public boolean awaitTermination(Duration timeout) {
        return await(terminated, timeout);
    }

    public void awaitTermination() throws InterruptedException {
        terminated.await();
    }

    /** Runs a console command in the instance, as if typed into its console. */
    public boolean dispatchCommand(String command) {
        InstanceClassLoader loader = classLoader;
        if (loader == null || (state != State.STARTING && state != State.RUNNING)) return false;
        try {
            Object craftServer = Class.forName("org.bukkit.Bukkit", true, loader).getMethod("getServer").invoke(null);
            if (craftServer == null) return false; // still booting
            Object minecraft = craftServer.getClass().getMethod("getServer").invoke(craftServer);
            Object source = minecraft.getClass().getMethod("createCommandSourceStack").invoke(minecraft);
            for (Method m : minecraft.getClass().getMethods()) {
                // thread-safe: queued and run on the server thread, same path as Paper's own console
                if (m.getName().equals("handleConsoleInput") && m.getParameterCount() == 2) {
                    m.invoke(minecraft, command, source);
                    return true;
                }
            }
            log.warn("Instance {} has no handleConsoleInput, cannot run '{}'", name, command);
        } catch (ReflectiveOperationException | LinkageError e) {
            log.warn("Could not run '{}' on instance {}", command, name, e);
        }
        return false;
    }

    /** The current run's standard streams, or null when not running. */
    HiveIo.@Nullable InstanceIo io() {
        return io;
    }

    /** The current run's own java.util.logging loggers, or null when not running. */
    HiveJul.@Nullable Namespace julNamespace() {
        return jul;
    }

    /** The instance a thread belongs to (by thread group, then context loader), or null for host threads. */
    /** The instance a thread belongs to (its thread group, else its context loader), or null for host threads. */
    public static @Nullable ServerInstance ofThread(Thread thread) {
        for (ThreadGroup g = thread.getThreadGroup(); g != null; g = g.getParent()) {
            if (g instanceof InstanceThreadGroup group) return group.owner();
        }
        return InstanceHooks.instanceOf(thread.getContextClassLoader());
    }

    // ---- called through InstanceHooks from instance threads ----

    void handleExit(int status, boolean halt) {
        List<Thread> hooks = beginExit();
        if (hooks == null) throw new InstanceExit(status); // already exiting; a real JVM would block here forever
        exitStatus = status;
        log.info("Instance {} called {}({})", name, halt ? "halt" : "exit", status);
        if (!halt) runHooks(hooks);
        exitedAt = System.nanoTime();
        throw new InstanceExit(status);
    }

    synchronized void addShutdownHook(Thread hook) {
        if (exiting) throw new IllegalStateException("Shutdown in progress");
        if (hook.isAlive()) throw new IllegalArgumentException("Hook already running");
        if (shutdownHooks.contains(hook)) throw new IllegalArgumentException("Hook previously registered");
        shutdownHooks.add(hook);
    }

    synchronized boolean removeShutdownHook(Thread hook) {
        if (exiting) throw new IllegalStateException("Shutdown in progress");
        return shutdownHooks.remove(hook);
    }

    synchronized void trackLockedChannel(FileChannel channel) {
        lockedChannels.add(channel);
    }

    synchronized void onStop(Runnable undo) {
        onStop.add(undo);
    }

    synchronized void trackDefiningLoader(ClassLoader loader) {
        definingLoaders.add(loader);
    }

    synchronized ExecutorService trackExecutor(ExecutorService executor) {
        trackedExecutors.add(executor);
        return executor;
    }

    private void shutdownTrackedExecutors() {
        List<ExecutorService> executors;
        synchronized (this) {
            executors = new ArrayList<>(trackedExecutors);
        }
        for (ExecutorService executor : executors) {
            executor.shutdown(); // on some JDK 21 builds only shutdown() (not shutdownNow) unregisters the Cleaner action
            executor.shutdownNow();
        }
    }

    // ---- internals ----

    /** Marks the instance as exiting and hands out its hooks to run, or null if it was already exiting. */
    private synchronized @Nullable List<Thread> beginExit() {
        if (exiting) return null;
        exiting = true;
        setState(State.STOPPING);
        List<Thread> hooks = new ArrayList<>(shutdownHooks);
        shutdownHooks.clear();
        return hooks;
    }

    private void runHooks(List<Thread> hooks) {
        for (Thread hook : hooks) hook.start();
        long deadline = System.nanoTime() + HOOK_TIMEOUT.toNanos();
        for (Thread hook : hooks) {
            try {
                hook.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (hook.isAlive()) log.warn("Shutdown hook {} of instance {} still running after {}", hook.getName(), name, HOOK_TIMEOUT);
        }
    }

    private void requestShutdown(InstanceClassLoader loader) {
        try {
            Class<?> bukkit = Class.forName("org.bukkit.Bukkit", true, loader);
            if (bukkit.getMethod("getServer").invoke(null) != null) {
                bukkit.getMethod("shutdown").invoke(null); // Paper then stops and calls System.exit itself
                return;
            }
        } catch (ReflectiveOperationException | LinkageError e) {
            log.debug("Instance {} has no Bukkit server to shut down", name, e);
        }
        // not up far enough for a graceful shutdown (or not a Bukkit server): treat it like SIGTERM on a JVM,
        // run the hooks, then let the reaper interrupt every instance thread, including ones started later
        List<Thread> hooks = beginExit();
        if (hooks != null) runHooks(hooks);
        exitedAt = System.nanoTime() - EXIT_GRACE.toNanos();
    }

    /** Watches one generation of the instance until all its threads are gone, then finishes it. */
    private void reap(InstanceClassLoader loader, Thread main) {
        boolean reported = false;
        while (true) {
            try {
                Thread.sleep(250);
            } catch (InterruptedException ignored) {
                // keep watching; nothing else stops this generation's cleanup
            }
            if (state == State.STARTING) checkReady(loader);
            List<Thread> live = liveThreads(loader);

            if (live.isEmpty() && !main.isAlive()) {
                finish(loader, List.of());
                return;
            }
            // a JVM ends once only daemon threads are left, so an instance does too (e.g. EULA not accepted):
            // run the hooks like the JVM would; leftover daemon threads then get the grace period and an interrupt
            if (!main.isAlive() && live.stream().allMatch(Thread::isDaemon)) {
                List<Thread> hooks = beginExit();
                if (hooks != null) {
                    runHooks(hooks);
                    exitedAt = System.nanoTime();
                    continue;
                }
            }

            long exited = exitedAt;
            if (exited == 0) continue;
            long since = System.nanoTime() - exited;
            if (since >= EXIT_GRACE.plus(LEAK_TIMEOUT).toNanos()) {
                finish(loader, live);
                return;
            }
            if (since >= EXIT_GRACE.toNanos()) {
                if (!reported) {
                    log.info("Stopping {} remaining thread(s) of instance {}: {}", live.size(), name, names(live));
                    reported = true;
                }
                // every round, so threads started after the first attempt are caught too.
                // Pool threads ignore interrupts, so their pools are shut down (a real exit just kills them)
                shutdownTrackedExecutors();
                LeftoverThreads.shutdownExecutors(live);
                live.forEach(Thread::interrupt);
            }
        }
    }

    private synchronized void finish(InstanceClassLoader loader, List<Thread> leaked) {
        if (classLoader != loader) return; // already finished
        if (!leaked.isEmpty()) {
            log.warn("Instance {} left {} thread(s) running; its classes can't be unloaded: {}", name, leaked.size(), names(leaked));
            for (Thread t : leaked) log.warn("  {} at {}", t.getName(), Arrays.toString(t.getStackTrace()));
        }
        releaseLocks();
        shutdownTrackedExecutors(); // also unregisters the JDK's Cleaner action for single-thread executors
        synchronized (this) {
            trackedExecutors.clear();
        }
        List<Runnable> undos;
        synchronized (this) {
            undos = new ArrayList<>(onStop);
            onStop.clear();
        }
        for (Runnable undo : undos) {
            try {
                undo.run();
            } catch (RuntimeException e) {
                log.warn("Could not undo a JVM-wide registration of instance {}", name, e);
            }
        }
        List<ClassLoader> innerLoaders;
        synchronized (this) {
            innerLoaders = new ArrayList<>(definingLoaders);
            definingLoaders.clear();
        }
        cleanupJvmGlobals(loader, innerLoaders);
        closeQuietly(loader);
        sinks.out().close(); // emits a last line that had no line break
        sinks.err().close();
        io = null;
        jul = null;
        classLoader = null;
        mainThread = null;
        group = null;
        shutdownHooks.clear();
        Integer status = exitStatus;
        boolean crashed = mainThrew || (status != null && status != 0);
        if (crashed) {
            log.warn("Instance {} crashed{}", name, status != null ? " (exit status " + status + ")" : " (its main method threw)");
            scheduleCrashRestart(); // before CRASHED is visible, so isActive() never reads false in between
        } else {
            log.info("Instance {} stopped{}", name, status != null ? " with exit status " + status : "");
        }
        setState(crashed ? State.CRASHED : State.STOPPED);
        terminated.countDown();
    }

    private void scheduleCrashRestart() {
        if (options.restart() != RestartPolicy.ON_CRASH) return;
        long now = System.nanoTime();
        crashes.addLast(now);
        while (!crashes.isEmpty() && now - crashes.peekFirst() > options.crashWindow().toNanos()) crashes.removeFirst();
        if (crashes.size() >= options.maxCrashes()) {
            log.error("Instance {} crashed {} times within {}; not restarting it again", name, crashes.size(), options.crashWindow());
            crashes.clear();
            return;
        }
        Object token = new Object();
        pendingRestart = token;
        log.info("Restarting instance {} in {}s", name, options.restartDelay().toSeconds());
        Thread restart = createRestart(token);
        restart.start();
    }

    public @NotNull Thread createRestart(Object token) {
        Thread restart = new Thread(HOST_GROUP, () -> {
            try {
                Thread.sleep(options.restartDelay().toMillis());
                synchronized (this) {
                    if (pendingRestart != token) return; // stopped or started by hand meanwhile
                    start(lastArgs);
                }
            } catch (InterruptedException | RuntimeException e) {
                log.error("Could not restart instance {}", name, e);
                synchronized (this) {
                    if (pendingRestart == token) pendingRestart = null;
                }
            }
        }, "hive-" + name + "-restart");
        restart.setDaemon(true);
        restart.setContextClassLoader(ServerInstance.class.getClassLoader());
        return restart;
    }

    private void setState(State to) {
        State from;
        synchronized (this) {
            from = state;
            if (from == to) return;
            state = to;
        }
        log.debug("Instance {}: {} -> {}", name, from, to);
        for (StateListener listener : listeners) {
            try {
                listener.changed(this, from, to);
            } catch (RuntimeException e) {
                log.warn("A state listener of instance {} failed", name, e);
            }
        }
    }

    private static boolean isBukkit(InstanceClassLoader loader) {
        try {
            Class.forName("org.bukkit.Bukkit", false, loader);
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    /** STARTING -> RUNNING once Paper reports it has finished booting. */
    private void checkReady(InstanceClassLoader loader) {
        try {
            Object craftServer = Class.forName("org.bukkit.Bukkit", true, loader).getMethod("getServer").invoke(null);
            if (craftServer == null) return;
            Object minecraft = craftServer.getClass().getMethod("getServer").invoke(craftServer);
            if (!Boolean.TRUE.equals(minecraft.getClass().getMethod("isReady").invoke(minecraft))) return;
            synchronized (this) {
                if (state != State.STARTING || classLoader != loader) return;
                setState(State.RUNNING);
            }
            log.info("Instance {} is up after {}s", name, String.format("%.1f", (System.nanoTime() - startedAt) / 1e9));
        } catch (ReflectiveOperationException | LinkageError e) {
            log.debug("Cannot tell whether instance {} has booted", name, e);
        }
    }

    /** Closes channels the instance locked and never closed; process exit would release those locks. */
    private void releaseLocks() {
        List<FileChannel> channels;
        synchronized (this) {
            channels = new ArrayList<>(lockedChannels);
            lockedChannels.clear();
        }
        for (FileChannel channel : channels) {
            if (!channel.isOpen()) continue;
            try {
                channel.close();
                log.debug("Released a file lock left open by instance {}", name);
            } catch (IOException e) {
                log.warn("Could not release a file lock left by instance {}", name, e);
            }
        }
    }

    /** Its live threads now (0 when not running); walks the JVM's threads, so for status displays, not hot paths. */
    public int threadCount() {
        InstanceClassLoader loader = classLoader;
        return loader == null ? 0 : liveThreads(loader).size();
    }

    private List<Thread> liveThreads(InstanceClassLoader loader) {
        ThreadGroup g = group;
        ThreadGroup root = HOST_GROUP;
        while (root.getParent() != null) root = root.getParent();
        Thread[] all = new Thread[root.activeCount() + 16];
        int n = root.enumerate(all, true);
        List<Thread> live = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Thread t = all[i];
            if (t == Thread.currentThread() || !t.isAlive() || LeftoverThreads.isCommonPoolWorker(t)) continue;
            ThreadGroup tg = t.getThreadGroup();
            boolean inGroup = g != null && tg != null && (tg == g || g.parentOf(tg));
            if (inGroup || ownedBy(t.getContextClassLoader(), loader)) live.add(t);
        }
        return live;
    }

    /** Undoes JVM-wide state the instance pointed at its own classes, which would otherwise keep its loader alive. */
    private static void cleanupJvmGlobals(InstanceClassLoader loader, List<ClassLoader> innerLoaders) {
        // System.out/err/in need nothing: instances only ever set their own slots (HiveIo)
        if (owned(Thread.getDefaultUncaughtExceptionHandler(), loader)) {
            Thread.setDefaultUncaughtExceptionHandler(HOST_DEFAULT_HANDLER);
        }

        LogManager logManager = LogManager.getLogManager();
        for (String loggerName : Collections.list(logManager.getLoggerNames())) {
            Logger logger = logManager.getLogger(loggerName);
            if (logger == null) continue;
            for (Handler handler : logger.getHandlers()) {
                if (!owned(handler, loader)) continue;
                logger.removeHandler(handler);
                try {
                    handler.close();
                } catch (RuntimeException ignored) {
                }
            }
            if (owned(logger.getFilter(), loader)) logger.setFilter(null);
        }

        MBeanServer mbeans = ManagementFactory.getPlatformMBeanServer();
        for (ObjectName objectName : mbeans.queryNames(null, null)) {
            try {
                if (ownedBy(mbeans.getClassLoaderFor(objectName), loader)) mbeans.unregisterMBean(objectName);
            } catch (JMException | RuntimeException ignored) {
            }
        }

        for (Provider provider : Security.getProviders()) {
            if (owned(provider, loader)) Security.removeProvider(provider.getName());
        }

        JdbcDrivers.deregister(loader, innerLoaders);
    }

    private static boolean owned(@Nullable Object o, ClassLoader loader) {
        return o != null && ownedBy(o.getClass().getClassLoader(), loader);
    }

    /** True if {@code candidate} is {@code loader} or a child of it (e.g. a plugin loader inside the instance). */
    static boolean ownedBy(@Nullable ClassLoader candidate, ClassLoader loader) {
        for (ClassLoader l = candidate; l != null; l = l.getParent()) if (l == loader) return true;
        return false;
    }

    private static List<String> names(List<Thread> threads) {
        return threads.stream().map(Thread::getName).toList();
    }

    private static boolean await(CountDownLatch latch, Duration timeout) {
        try {
            return latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return latch.getCount() == 0;
        }
    }

    private void closeQuietly(InstanceClassLoader loader) {
        try {
            loader.close();
        } catch (IOException e) {
            log.warn("Could not close the class loader of instance {}", name, e);
        }
    }

    private URL[] urls() {
        List<URL> urls = new ArrayList<>();
        try {
            urls.add(server.serverJar().toUri().toURL());
            for (Path lib : server.libraries()) urls.add(lib.toUri().toURL());
        } catch (MalformedURLException e) {
            throw new IllegalStateException(e);
        }
        return urls.toArray(URL[]::new);
    }

    /** Swallows {@link InstanceExit} (the expected way an exiting thread ends) and logs anything else. */
    private final class InstanceThreadGroup extends ThreadGroup {

        private InstanceThreadGroup(String groupName) {
            super(HOST_GROUP, groupName);
        }

        private ServerInstance owner() {
            return ServerInstance.this;
        }

        @Override
        public void uncaughtException(Thread t, Throwable e) {
            if (e instanceof InstanceExit) return;
            log.error("Uncaught exception in thread {} of instance {}", t.getName(), name, e);
        }
    }

}
