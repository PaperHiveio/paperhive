package org.abdullahcxd.paperhive.host;

import com.sun.management.HotSpotDiagnosticMXBean;
import org.jetbrains.annotations.NotNull;

import javax.management.Notification;
import javax.management.NotificationEmitter;
import javax.management.NotificationListener;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryNotificationInfo;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Every server shares one heap, and there's no per-server limit (see "Memory and crashes" in docs/architecture.md).
 * This warns when the heap is still nearly full after a GC, naming the servers running, so someone can act before an
 * {@code OutOfMemoryError}; and turns on a heap dump at the first {@code OutOfMemoryError} unless the launch flags
 * chose otherwise.
 */
final class MemoryWatch {

    static final double WARN_AT = 0.9;

    private MemoryWatch() {}

    /**
     * @param fraction warn when a heap pool is this full after a GC
     * @param running  the servers running, for the warning
     * @return removes the watch
     */
    static @NotNull Runnable install(double fraction, Supplier<List<String>> running, Consumer<String> warn) {
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            long max = pool.getUsage().getMax();
            if (pool.getType() == MemoryType.HEAP && pool.isCollectionUsageThresholdSupported() && max > 0) {
                pool.setCollectionUsageThreshold(Math.max(1, (long) (max * fraction)));
            }
        }
        NotificationEmitter emitter = (NotificationEmitter) ManagementFactory.getMemoryMXBean();
        NotificationListener listener = (Notification n, Object handback) -> {
            if (!n.getType().equals(MemoryNotificationInfo.MEMORY_COLLECTION_THRESHOLD_EXCEEDED)) return;
            MemoryNotificationInfo info = MemoryNotificationInfo.from((javax.management.openmbean.CompositeData) n.getUserData());
            warn.accept(String.format("The heap is %d%% full after a GC (%s: %d of %d MB) and every server shares it; running: %s. "
                            + "Stop a server or give Paperhive more memory (-Xmx)",
                    100 * info.getUsage().getUsed() / info.getUsage().getMax(), info.getPoolName(),
                    info.getUsage().getUsed() >> 20, info.getUsage().getMax() >> 20, running.get()));
        };
        emitter.addNotificationListener(listener, null, null);
        return () -> {
            try {
                emitter.removeNotificationListener(listener);
            } catch (javax.management.ListenerNotFoundException ignored) {
                // already gone
            }
        };
    }

    /** A heap dump in {@code dir} at the first OutOfMemoryError, unless {@code -XX:...HeapDumpOnOutOfMemoryError} was given. */
    static void dumpHeapOnOutOfMemory(Path dir) {
        HotSpotDiagnosticMXBean hotspot = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
        if (hotspot.getVMOption("HeapDumpOnOutOfMemoryError").getOrigin() != com.sun.management.VMOption.Origin.DEFAULT) return;
        hotspot.setVMOption("HeapDumpPath", dir.toAbsolutePath().toString());
        hotspot.setVMOption("HeapDumpOnOutOfMemoryError", "true");
    }

}
