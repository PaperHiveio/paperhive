package org.abdullahcxd.paperhive.host;

import org.abdullahcxd.paperhive.api.HiveServer;
import org.abdullahcxd.paperhive.api.HiveServerState;
import org.abdullahcxd.paperhive.api.Registration;
import org.abdullahcxd.paperhive.api.event.Subscribe;
import org.abdullahcxd.paperhive.api.event.server.ServerStartEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** The Hive API from the host; from inside servers it's covered by the end-to-end run (see tasks.md). */
class HostHiveTest {

    @TempDir
    Path tmp;
    HostHive hive;

    @BeforeEach
    void setUp() {
        hive = new HostHive();
        ServerManager servers = TestServers.create(tmp, i -> i.addStateListener(hive::stateChanged));
        hive.init(servers, s -> List.of());
        servers.setStartGuard(hive::allowStart);
    }

    /** Waits until everything queued on the event thread so far has run. */
    private void drain() throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        hive.deliver(done::countDown);
        assertTrue(done.await(5, TimeUnit.SECONDS));
    }

    @Test
    void servers() {
        HiveServer main = hive.getServer("main");
        assertNotNull(main);
        assertEquals(List.of(main), hive.getServers());
        assertSame(main, hive.getServer("main"), "one handle per server");
        assertEquals(25565, main.getPort());
        assertEquals(HiveServerState.CREATED, main.getState());
        assertNull(hive.getServer("nope"));
        assertNull(hive.getCurrentServer(), "the host's own thread");
        assertFalse(main.dispatchCommand("list"));
        assertThrows(IllegalStateException.class, () -> main.runOnServer(() -> {}), "not running");

        HiveServer lobby = hive.createServer("lobby", 25570);
        assertEquals(25570, lobby.getPort());
        assertEquals(2, hive.getServers().size());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> hive.createServer("lobby2", 25570));
        assertTrue(e.getMessage().contains("both use Port 25570"), e.getMessage());
    }

    @Test
    void aCancelledStartEventKeepsTheServerStopped() {
        List<String> asked = new ArrayList<>();
        hive.getEventBus().register(new Object() {
            @Subscribe
            void onStart(ServerStartEvent e) {
                asked.add(e.getServer().getName());
                e.setCancelled(true);
            }
        });
        HiveServer main = hive.getServer("main");
        assertFalse(main.start());
        assertEquals(List.of("main"), asked);
        assertEquals(HiveServerState.CREATED, main.getState());
    }

    @Test
    void messagesArriveInOrderAndSurviveABrokenListener() throws Exception {
        List<String> got = new CopyOnWriteArrayList<>();
        hive.subscribe("chat", (from, channel, message) -> {
            throw new IllegalStateException("broken");
        });
        Registration sub = hive.subscribe("chat", (from, channel, message) -> got.add((from == null ? "host" : from.getName()) + ":" + message));
        hive.subscribe("other", (from, channel, message) -> got.add("wrong channel"));
        for (int i = 0; i < 100; i++) hive.publish("chat", "m" + i);
        drain();
        assertEquals(100, got.size());
        assertEquals("host:m0", got.getFirst());
        assertEquals("host:m99", got.getLast());

        sub.unregister();
        sub.unregister();
        hive.publish("chat", "after");
        drain();
        assertEquals(100, got.size());
    }

}
