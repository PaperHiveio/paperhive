package org.abdullahcxd.paperhive.host.server;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryWatchTest {

    @Test
    void warnsWhenTheHeapIsFullAfterAGc() throws Exception {
        List<String> warnings = new CopyOnWriteArrayList<>();
        // a threshold of ~nothing: any GC'd heap pool is "full"
        Runnable remove = MemoryWatch.install(1e-9, () -> List.of("alpha", "beta"), warnings::add);
        try {
            for (int i = 0; i < 20 && warnings.isEmpty(); i++) {
                System.gc();
                Thread.sleep(100);
            }
        } finally {
            remove.run();
            MemoryWatch.install(MemoryWatch.WARN_AT, List::of, w -> {}).run(); // back to a normal threshold
        }
        assertTrue(!warnings.isEmpty() && warnings.getFirst().contains("running: [alpha, beta]"), warnings.toString());
    }

}
