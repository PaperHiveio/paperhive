package org.abdullahcxd.paperhive.host.instance;

import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.TimeUnit;

/**
 * Ends threads an instance leaves behind after it exits. On a real JVM exit they'd just die; here pool threads
 * ignore interrupts, so the pool behind each one is found and shut down:
 * {@link ExecutorService}s ({@code shutdownNow}), Netty event loops ({@code shutdownGracefully(0, 0)}),
 * and {@link ForkJoinPool}s other than the JVM-wide common pool.
 * <p>
 * Finding the pool means reading a thread's task, which needs
 * {@code --add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.util.concurrent=ALL-UNNAMED}
 * (the host jar's manifest sets both). Without them only interrupts are tried.
 */
@Slf4j
final class LeftoverThreads {

    /** How many object links to follow from a thread's task to its pool (Netty needs 2, a JDK pool 1). */
    private static final int MAX_DEPTH = 4;

    private static volatile boolean warnedNoAccess;

    private LeftoverThreads() {}

    /** The common pool is shared with the host and every instance, so it never belongs to one. */
    static boolean isCommonPoolWorker(Thread t) {
        return t instanceof ForkJoinWorkerThread worker && worker.getPool() == ForkJoinPool.commonPool();
    }

    static void shutdownExecutors(@NotNull List<Thread> threads) {
        Set<Object> done = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Thread t : threads) {
            Object executor = t instanceof ForkJoinWorkerThread worker ? worker.getPool() : findExecutor(t);
            if (executor == null || executor == ForkJoinPool.commonPool() || !done.add(executor)) continue;
            shutdown(executor, t);
        }
    }

    private static void shutdown(Object executor, Thread thread) {
        try {
            Method graceful = executor.getClass().getMethod("shutdownGracefully", long.class, long.class, TimeUnit.class);
            if (graceful.trySetAccessible()) {
                graceful.invoke(executor, 0L, 0L, TimeUnit.MILLISECONDS);
                log.debug("Shut down event loop {} of thread {}", executor.getClass().getName(), thread.getName());
                return;
            }
        } catch (NoSuchMethodException notNetty) {
            // fall through
        } catch (ReflectiveOperationException | RuntimeException e) {
            log.debug("shutdownGracefully failed on {}", executor.getClass().getName(), e);
        }
        if (executor instanceof ExecutorService service) {
            service.shutdownNow();
            log.debug("Shut down executor {} of thread {}", executor.getClass().getName(), thread.getName());
        }
    }

    /** Breadth-first from the thread's task (and its own subclass fields) to the nearest executor. */
    private static @Nullable Object findExecutor(Thread thread) {
        List<Object> level = new ArrayList<>();
        Object task = task(thread);
        if (task != null) level.add(task);
        level.add(thread); // Thread subclasses that override run() keep their pool in their own fields
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());

        for (int depth = 0; depth <= MAX_DEPTH && !level.isEmpty(); depth++) {
            List<Object> next = new ArrayList<>();
            for (Object o : level) {
                if (!seen.add(o)) continue;
                if (isExecutor(o)) return o;
                for (Class<?> c = o.getClass(); c != null && c != Object.class && c != Thread.class; c = c.getSuperclass()) {
                    for (Field f : c.getDeclaredFields()) {
                        if (Modifier.isStatic(f.getModifiers()) || !followable(f.getType())) continue;
                        if (!f.trySetAccessible()) continue;
                        try {
                            Object value = f.get(o);
                            if (value != null) next.add(value);
                        } catch (IllegalAccessException ignored) {
                        }
                    }
                }
            }
            level = next;
        }
        return null;
    }

    private static boolean isExecutor(Object o) {
        if (o instanceof ExecutorService) return true;
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            for (Class<?> i : c.getInterfaces()) if (i.getName().equals("io.netty.util.concurrent.EventExecutorGroup")) return true;
        }
        return false;
    }

    // don't wander into unrelated object graphs
    private static boolean followable(Class<?> type) {
        return !type.isPrimitive() && !type.isArray() && type != String.class
                && !Thread.class.isAssignableFrom(type) && !ClassLoader.class.isAssignableFrom(type)
                && !Class.class.equals(type);
    }

    private static @Nullable Object task(Thread thread) {
        try {
            Field holder = Thread.class.getDeclaredField("holder"); // JDK 19+
            holder.setAccessible(true);
            Object h = holder.get(thread);
            if (h == null) return null;
            Field task = h.getClass().getDeclaredField("task");
            task.setAccessible(true);
            return task.get(h);
        } catch (NoSuchFieldException e) {
            return legacyTarget(thread);
        } catch (ReflectiveOperationException | RuntimeException e) {
            warnNoAccess(e);
            return null;
        }
    }

    private static @Nullable Object legacyTarget(Thread thread) {
        try {
            Field target = Thread.class.getDeclaredField("target");
            target.setAccessible(true);
            return target.get(thread);
        } catch (ReflectiveOperationException | RuntimeException e) {
            warnNoAccess(e);
            return null;
        }
    }

    private static void warnNoAccess(Exception e) {
        if (warnedNoAccess) return;
        warnedNoAccess = true;
        log.warn("Can't inspect leftover instance threads ({}); start the JVM with --add-opens java.base/java.lang=ALL-UNNAMED "
                + "--add-opens java.base/java.util.concurrent=ALL-UNNAMED so their pools can be shut down", e.toString());
    }

}
