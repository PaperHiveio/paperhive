package org.abdullahcxd.paperhive.host.server;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RamReservationTest {

    @Test
    void startsOnlyWhileItsRamIsFree() {
        assertNull(ServerManager.ramShortfall("a", 4096, Map.of(), 4096, "heap"), "exactly the whole heap fits");
        assertNull(ServerManager.ramShortfall("b", 1024, Map.of("a", 3072), 4096, "heap"));
        String shortfall = ServerManager.ramShortfall("c", 2048, Map.of("a", 3072), 4096, "heap");
        assertEquals("Not starting c: its Ram is 2048 MB but only 1024 MB of the 4096 MB heap is free (reserved: {a=3072})", shortfall);
        assertTrue(ServerManager.ramShortfall("d", 8192, Map.of(), 4096, "heap").contains("only 4096 MB"));
    }

}
