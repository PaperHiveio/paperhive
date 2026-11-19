package org.abdullahcxd.paperhive.host.instance;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.ref.WeakReference;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.fail;

/** For the real-Paper leak checks (run with {@code -XX:SoftRefLRUPolicyMSPerMB=0}, see {@link PaperInstanceIT}). */
public final class Leaks {

    private Leaks() {}

    @SuppressWarnings("removal")
    public static void gc() throws InterruptedException {
        for (int i = 0; i < 3; i++) {
            System.gc();
            // Netty's allocator has a finalizer that holds its class (and so the loader) until it has run
            System.runFinalization();
            Thread.sleep(200);
        }
    }

    /** Fails with a heap dump (to find what still holds it) if {@code ref} isn't collected. */
    public static void assertCollected(WeakReference<?> ref, String what) throws InterruptedException {
        for (int i = 0; i < 50 && ref.get() != null; i++) gc();
        if (ref.get() == null) return;
        Path dump = Path.of("leak-" + System.currentTimeMillis() + ".hprof").toAbsolutePath();
        try {
            ManagementFactory.getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean.class).dumpHeap(dump.toString(), true);
        } catch (IOException e) {
            dump = null;
        }
        fail(what + " is still reachable after stop" + (dump != null ? "; heap dump: " + dump : ""));
    }

}
