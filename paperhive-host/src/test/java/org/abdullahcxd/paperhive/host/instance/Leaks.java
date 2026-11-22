package org.abdullahcxd.paperhive.host.instance;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.ref.WeakReference;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.fail;

/** For the real-Paper leak checks (run with {@code -XX:SoftRefLRUPolicyMSPerMB=0}, see {@link PaperInstanceIT}). */
public final class Leaks {

    private Leaks() {}

    /**
     * Interrupt a stopped server's leftover threads after 1s instead of 5s: Paper's (Craft Scheduler, Netty, ...)
     * never exit on their own, so the default grace is ~4s of waiting per stop.
     */
    public static void quickStops() {
        ServerInstance.setTimeouts(Duration.ofSeconds(30), Duration.ofSeconds(1), Duration.ofSeconds(10));
    }

    /** Back to the defaults (Host.Unsafe's), for the tests that run after in the same JVM. */
    public static void defaultStops() {
        ServerInstance.setTimeouts(Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ofSeconds(10));
    }

    @SuppressWarnings("removal")
    public static void gc() throws InterruptedException {
        for (int i = 0; i < 3; i++) {
            System.gc();
            // Netty's allocator has a finalizer that holds its class (and so the loader) until it has run
            System.runFinalization();
            Thread.sleep(200);
        }
    }

    /**
     * Fails with a heap dump (to find what still holds it) if {@code ref} isn't collected. Something only softly
     * reachable isn't a leak: the JDK keeps soft caches that can point at a stopped server's classes, e.g. the one-entry
     * {@code asTypeSoftCache} of {@code ObjectMethods.OBJECTS_EQUALS} (every record's {@code equals}) holding a
     * Minecraft record type, until the next server replaces it or the heap gets tight (see "Soft references" in
     * docs/architecture.md). So before failing, every soft reference is cleared, the one way the JVM guarantees it.
     */
    public static void assertCollected(WeakReference<?> ref, String what) throws InterruptedException {
        for (int i = 0; i < 50 && ref.get() != null; i++) gc();
        if (ref.get() != null) {
            clearSoftReferences();
            for (int i = 0; i < 5 && ref.get() != null; i++) gc();
        }
        if (ref.get() == null) return;
        Path dump = Path.of("leak-" + System.currentTimeMillis() + ".hprof").toAbsolutePath();
        try {
            ManagementFactory.getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean.class).dumpHeap(dump.toString(), true);
        } catch (IOException e) {
            dump = null;
        }
        fail(what + " is still reachable after stop" + (dump != null ? "; heap dump: " + dump : ""));
    }

    /** The JVM clears every soft reference before it throws OutOfMemoryError, so run into one on purpose. */
    @SuppressWarnings("MismatchedQueryAndUpdateOfCollection")
    public static void clearSoftReferences() {
        List<long[]> filler = new ArrayList<>();
        try {
            while (true) filler.add(new long[8 * 1024 * 1024]); // 64 MB at a time
        } catch (OutOfMemoryError expected) {
            filler.clear();
        }
    }

}
