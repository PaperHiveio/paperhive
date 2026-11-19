package org.abdullahcxd.paperhive.host.instance;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.LoggerFactory;

import jdk.jfr.Event;
import jdk.jfr.FlightRecorder;
import jdk.jfr.FlightRecorderListener;

import java.util.List;
import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.ByteBuffer;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.Arrays;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Targets of the calls {@link InstanceTransformer} rewrites. Public because instance classes call them;
 * shared with instances through {@link ApiGateLoader}. Not meant to be called directly.
 * <p>
 * Each method finds the calling instance from the caller's class loader and acts on that instance only.
 */
public final class InstanceHooks {

    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    private InstanceHooks() {}

    /** Replaces {@code System.exit}: runs the instance's shutdown hooks, then unwinds the calling thread. */
    public static void exit(int status) {
        caller().handleExit(status, false);
    }

    /** Replaces {@code Runtime.exit}. */
    public static void runtimeExit(@NotNull Runtime runtime, int status) {
        caller().handleExit(status, false);
    }

    /** Replaces {@code Runtime.halt}: like exit, but without running shutdown hooks. */
    public static void halt(@NotNull Runtime runtime, int status) {
        caller().handleExit(status, true);
    }

    /** Replaces {@code Runtime.addShutdownHook}: the hook runs when this instance exits or is stopped. */
    public static void addShutdownHook(@NotNull Runtime runtime, @NotNull Thread hook) {
        caller().addShutdownHook(hook);
    }

    /** Replaces {@code Runtime.removeShutdownHook}. */
    public static boolean removeShutdownHook(@NotNull Runtime runtime, @NotNull Thread hook) {
        return caller().removeShutdownHook(hook);
    }

    /** Replaces {@code FileChannel.lock()}: the channel is closed (lock released) when the instance stops. */
    public static @NotNull FileLock lock(@NotNull FileChannel channel) throws IOException {
        return track(channel, channel.lock());
    }

    public static @NotNull FileLock lock(@NotNull FileChannel channel, long position, long size, boolean shared) throws IOException {
        return track(channel, channel.lock(position, size, shared));
    }

    /** Replaces {@code FileChannel.tryLock()}. */
    public static @NotNull FileLock tryLock(@NotNull FileChannel channel) throws IOException {
        return track(channel, channel.tryLock());
    }

    public static @NotNull FileLock tryLock(@NotNull FileChannel channel, long position, long size, boolean shared) throws IOException {
        return track(channel, channel.tryLock(position, size, shared));
    }

    /** Replaces {@code FlightRecorder.addListener}: the listener is removed again when the instance stops. */
    public static void addFlightRecorderListener(@NotNull FlightRecorderListener listener) {
        ServerInstance instance = caller();
        FlightRecorder.addListener(listener);
        instance.onStop(() -> FlightRecorder.removeListener(listener));
    }

    /** Replaces {@code FlightRecorder.addPeriodicEvent}: removed again when the instance stops. */
    public static void addPeriodicEvent(@NotNull Class<? extends Event> eventClass, @NotNull Runnable hook) {
        ServerInstance instance = caller();
        FlightRecorder.addPeriodicEvent(eventClass, hook);
        instance.onStop(() -> FlightRecorder.removePeriodicEvent(hook));
    }

    /** Replaces {@code FlightRecorder.register}: unregistered again when the instance stops. */
    public static void registerEvent(@NotNull Class<? extends Event> eventClass) {
        ServerInstance instance = caller();
        FlightRecorder.register(eventClass);
        instance.onStop(() -> FlightRecorder.unregister(eventClass));
    }

    /** Replaces {@code Executors.newSingleThreadExecutor()}: shut down when the instance stops. */
    public static @NotNull ExecutorService newSingleThreadExecutor() {
        return caller().trackExecutor(Executors.newSingleThreadExecutor());
    }

    public static @NotNull ExecutorService newSingleThreadExecutor(@NotNull ThreadFactory threadFactory) {
        return caller().trackExecutor(Executors.newSingleThreadExecutor(threadFactory));
    }

    // classes defined by the instance's own loaders (Paper's plugin loaders, server halves') are rewritten too

    /** {@code ClassLoader.defineClass(String, byte[], int, int, ProtectionDomain)}, or null without --add-opens java.base/java.lang. */
    private static final @Nullable MethodHandle DEFINE_CLASS = defineClassHandle();

    private static @Nullable MethodHandle defineClassHandle() {
        try {
            return MethodHandles.privateLookupIn(ClassLoader.class, MethodHandles.lookup()).findVirtual(ClassLoader.class,
                    "defineClass", MethodType.methodType(Class.class, String.class, byte[].class, int.class, int.class, ProtectionDomain.class));
        } catch (ReflectiveOperationException | RuntimeException e) {
            LoggerFactory.getLogger(InstanceHooks.class).warn("Classes defined by an instance's own loaders (e.g. plugins) won't be rewritten, "
                    + "so a plugin calling System.exit ends the JVM; start with --add-opens java.base/java.lang=ALL-UNNAMED ({})", e.toString());
            return null;
        }
    }

    /**
     * Defines {@code bytes} as is (not rewritten) in {@code loader}, or returns the class if it already defined it; null
     * when classes can't be defined in other loaders (no --add-opens java.base/java.lang).
     */
    static @Nullable Class<?> defineHelperIn(ClassLoader loader, String name, byte[] bytes) {
        if (DEFINE_CLASS == null) return null;
        try {
            return (Class<?>) DEFINE_CLASS.invokeExact(loader, name, bytes, 0, bytes.length, (ProtectionDomain) null);
        } catch (LinkageError alreadyDefined) {
            try {
                Class<?> existing = Class.forName(name, false, loader);
                return existing.getClassLoader() == loader ? existing : null;
            } catch (ClassNotFoundException e) {
                return null;
            }
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("defineClass failed for " + name, t);
        }
    }

    /** Whether {@link InstanceTransformer} may rewrite {@code defineClass} calls (it must not if they can't be carried out). */
    static boolean canDefineClasses() {
        return DEFINE_CLASS != null;
    }

    /** Replaces {@code SecureClassLoader.defineClass(String, byte[], int, int, CodeSource)} (used by Paper's plugin loaders). */
    public static @Nullable Class<?> defineClass(@NotNull Object loader, @NotNull String name, byte @NotNull [] b, int off, int len, @Nullable CodeSource cs) {
        ClassLoader cl = (ClassLoader) loader;
        // no SecurityManager, so the domain's permissions don't matter; SecureClassLoader would cache one per location
        return define(cl, name, b, off, len, cs == null ? null : new ProtectionDomain(cs, null, cl, null));
    }

    /** Replaces {@code ClassLoader.defineClass(String, byte[], int, int, ProtectionDomain)}. */
    public static @NotNull Class<?> defineClass(@NotNull Object loader, @NotNull String name, byte @NotNull [] b, int off, int len, @NotNull ProtectionDomain pd) {
        return define((ClassLoader) loader, name, b, off, len, pd);
    }

    /** Replaces {@code ClassLoader.defineClass(String, byte[], int, int)}. */
    public static @NotNull Class<?> defineClass(@NotNull Object loader, @NotNull String name, byte @NotNull [] b, int off, int len) {
        return define((ClassLoader) loader, name, b, off, len, null);
    }

    /** Replaces {@code ClassLoader.defineClass(String, ByteBuffer, ProtectionDomain)}. */
    public static @NotNull Class<?> defineClass(@NotNull Object loader, @NotNull String name, @NotNull ByteBuffer buffer, @NotNull ProtectionDomain pd) {
        byte[] b = new byte[buffer.remaining()];
        buffer.get(b);
        return define((ClassLoader) loader, name, b, 0, b.length, pd);
    }

    /** Replaces {@code SecureClassLoader.defineClass(String, ByteBuffer, CodeSource)}. */
    public static @NotNull Class<?> defineClass(@NotNull Object loader, @NotNull String name, @NotNull ByteBuffer buffer, @NotNull CodeSource cs) {
        byte[] b = new byte[buffer.remaining()];
        buffer.get(b);
        return defineClass(loader, name, b, 0, b.length, cs);
    }

    private static Class<?> define(ClassLoader loader, String name, byte[] b, int off, int len, @Nullable ProtectionDomain pd) {
        byte[] bytes = off == 0 && len == b.length ? b : Arrays.copyOfRange(b, off, off + len);
        ServerInstance instance = instanceOf(loader);
        bytes = InstanceTransformer.transform(bytes, instance == null ? List.of() : instance.getSoftware().patches());
        if (instance != null && !(loader instanceof InstanceClassLoader)) instance.trackDefiningLoader(loader);
        try {
            return (Class<?>) DEFINE_CLASS.invokeExact(loader, name, bytes, 0, bytes.length, pd);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("defineClass failed for " + name, t);
        }
    }

    private static FileLock track(FileChannel channel, FileLock lock) {
        if (lock != null) caller().trackLockedChannel(channel);
        return lock;
    }

    private static ServerInstance caller() {
        ServerInstance instance = callerOrNull();
        // only instance classes are rewritten, so this means a loader without an owning instance (e.g. in tests);
        // never fall back to the real exit, that would take every instance down
        if (instance == null) throw new IllegalStateException("No Paperhive instance owns the calling code");
        return instance;
    }

    /** The instance the calling code belongs to, or null if none does. */
    static @Nullable ServerInstance callerOrNull() {
        // first frame from an instance's class; a method reference runs through a hidden lambda class with JDK frames above it
        ServerInstance instance = WALKER.walk(frames -> frames
                .map(f -> instanceOf(f.getDeclaringClass().getClassLoader()))
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null));
        return instance != null ? instance : instanceOf(Thread.currentThread().getContextClassLoader());
    }

    /** The instance whose loader is {@code loader} or one of its parents (e.g. a plugin loader inside a server). */
    public static @Nullable ServerInstance instanceOf(@Nullable ClassLoader loader) {
        for (ClassLoader l = loader; l != null; l = l.getParent()) {
            if (l instanceof InstanceClassLoader icl) return icl.getInstance();
        }
        return null;
    }

}
