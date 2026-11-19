package org.abdullahcxd.paperhive.host.hive;

import org.abdullahcxd.paperhive.host.server.ServerManager;
import org.abdullahcxd.paperhive.host.server.TestServers;
import org.abdullahcxd.paperhive.api.HiveServer;
import org.abdullahcxd.paperhive.api.HiveServerState;
import org.abdullahcxd.paperhive.api.Namespaced;
import org.abdullahcxd.paperhive.api.Registration;
import org.abdullahcxd.paperhive.api.data.HiveData;
import org.abdullahcxd.paperhive.api.message.HiveChannel;
import org.abdullahcxd.paperhive.api.message.HiveService;
import org.abdullahcxd.paperhive.api.message.MessageCodec;
import org.abdullahcxd.paperhive.api.message.NoResponderException;
import org.abdullahcxd.paperhive.host.instance.ServerInstance;
import org.abdullahcxd.paperhive.api.event.Subscribe;
import org.abdullahcxd.paperhive.api.event.server.ServerStartEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
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
    ServerManager servers;

    @BeforeEach
    void setUp() {
        hive = new HostHive();
        servers = TestServers.create(tmp, i -> i.addStateListener(hive::stateChanged));
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

    @Test
    void sendReachesOnlyTheTarget() throws Exception {
        ServerInstance main = servers.instance("main");
        List<String> got = new CopyOnWriteArrayList<>();
        hive.subscribeAs(main, "x:ping", (from, channel, message) -> got.add("main:" + message));
        hive.subscribeAs(null, "x:ping", (from, channel, message) -> got.add("host:" + message));
        hive.send(hive.getServer("main"), Namespaced.parse("x:ping"), "a");
        hive.send(null, Namespaced.parse("x:ping"), "b");
        hive.publish(Namespaced.parse("x:ping"), "c");
        drain();
        assertEquals(List.of("main:a", "host:b", "main:c", "host:c"), got);

        hive.release(main); // a stopped server's subscriptions go
        hive.publish(Namespaced.parse("x:ping"), "d");
        drain();
        assertEquals("host:d", got.getLast());
        assertEquals(5, got.size());
    }

    @Test
    void requestsAreAnsweredByTheTargetsResponder() throws Exception {
        ServerInstance main = servers.instance("main");
        hive.respondAs(main, "x:echo", (from, channel, message) -> CompletableFuture.completedFuture("main:" + message));
        hive.respondAs(null, "x:echo", (from, channel, message) -> CompletableFuture.completedFuture("host:" + message));
        Duration timeout = Duration.ofSeconds(5);

        assertEquals("main:hi", hive.request(hive.getServer("main"), "x:echo", "hi", timeout).get(5, TimeUnit.SECONDS));
        assertEquals("host:hi", hive.request(null, "x:echo", "hi", timeout).get(5, TimeUnit.SECONDS));

        // the newest responder of a server answers
        Registration newer = hive.respondAs(null, "x:echo", (from, channel, message) -> CompletableFuture.completedFuture("newer"));
        assertEquals("newer", hive.request(null, "x:echo", "hi", timeout).get(5, TimeUnit.SECONDS));
        newer.unregister();
        assertEquals("host:hi", hive.request(null, "x:echo", "hi", timeout).get(5, TimeUnit.SECONDS));

        hive.release(main);
        ExecutionException none = assertThrows(ExecutionException.class, () -> hive.request(hive.getServer("main"), "x:echo", "hi", timeout).get(5, TimeUnit.SECONDS));
        assertInstanceOf(NoResponderException.class, none.getCause());

        hive.respondAs(null, "x:slow", (from, channel, message) -> new CompletableFuture<>()); // never answers
        ExecutionException late = assertThrows(ExecutionException.class, () -> hive.request(null, "x:slow", "", Duration.ofMillis(200)).get(5, TimeUnit.SECONDS));
        assertInstanceOf(TimeoutException.class, late.getCause());

        hive.respondAs(null, "x:fails", (from, channel, message) -> { throw new IllegalStateException("nope"); });
        ExecutionException failed = assertThrows(ExecutionException.class, () -> hive.request(null, "x:fails", "", timeout).get(5, TimeUnit.SECONDS));
        assertEquals("nope", failed.getCause().getMessage());
    }

    @Test
    void typedChannelsAndServices() throws Exception {
        Namespaced key = Namespaced.forPlugin("Test", "numbers");
        HiveChannel<Integer> numbers = hive.channel(key, MessageCodec.INTEGER);
        List<Integer> got = new CopyOnWriteArrayList<>();
        numbers.subscribe(m -> got.add(m.payload() * (m.fromHost() ? 1 : -1)));
        hive.publish(key, "not a number"); // logged and skipped, the next still arrives
        numbers.publish(21);
        drain();
        assertEquals(List.of(21), got);

        HiveService<Integer, Boolean> even = hive.service(key.with("even"), MessageCodec.INTEGER, MessageCodec.BOOLEAN);
        even.handle(m -> m.payload() % 2 == 0);
        assertTrue(even.request(null, 4).get(5, TimeUnit.SECONDS));
        assertFalse(even.request(null, 7).get(5, TimeUnit.SECONDS));
    }

    @Test
    void sharedData() throws Exception {
        HiveData data = hive.getData();
        Namespaced motd = Namespaced.parse("x:motd"), joins = Namespaced.parse("x:joins"), owner = Namespaced.parse("x:owner");
        List<String> changes = new CopyOnWriteArrayList<>();
        Registration watch = data.watch(motd, (k, before, after) -> changes.add(before + "->" + after));

        assertNull(data.get(motd));
        data.set(motd, "hi");
        data.set(motd, "hi"); // no change, no event
        data.set(motd, "hello");
        assertTrue(data.remove(motd));
        assertFalse(data.remove(motd));
        drain();
        assertEquals(List.of("null->hi", "hi->hello", "hello->null"), changes);

        assertEquals(1, data.increment(joins, 1));
        assertEquals(6, data.increment(joins, 5));
        assertEquals(6, data.get(joins, MessageCodec.INTEGER));
        assertTrue(data.compareAndSet(owner, null, "lobby"));
        assertFalse(data.compareAndSet(owner, null, "survival"), "already claimed");
        assertEquals(Map.of(joins, "6", owner, "lobby"), data.entries("x"));

        watch.unregister();
        data.set(motd, "after");
        drain();
        assertEquals(3, changes.size());
    }

}
