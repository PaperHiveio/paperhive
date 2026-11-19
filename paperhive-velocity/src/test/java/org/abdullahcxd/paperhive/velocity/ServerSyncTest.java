package org.abdullahcxd.paperhive.velocity;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ServerSyncTest {

    final Map<String, Integer> proxy = new LinkedHashMap<>(Map.of("lobby", 30000)); // from velocity.toml
    final ServerSync sync = new ServerSync(new ServerSync.Proxy() {
        @Override public boolean has(String name) { return proxy.containsKey(name); }
        @Override public void register(String name, InetSocketAddress address) { proxy.put(name, address.getPort()); }
        @Override public void unregister(String name) { proxy.remove(name); }
    }, "127.0.0.1");

    @Test
    void followsRunningServersOnly() {
        assertEquals(List.of("Registered alpha at 127.0.0.1:25601"),
                sync.sync(List.of("alpha\tRUNNING\t25601", "beta\tSTARTING\t25602", "noport\tRUNNING\tnull", "junk")));
        assertEquals(Map.of("lobby", 30000, "alpha", 25601), proxy);

        assertEquals(List.of(), sync.sync(List.of("alpha\tRUNNING\t25601", "beta\tSTARTING\t25602")), "nothing changed");
        assertEquals(List.of("Unregistered alpha", "Registered beta at 127.0.0.1:25602"),
                sync.sync(List.of("alpha\tSTOPPING\t25601", "beta\tRUNNING\t25602")));
        assertEquals(List.of("Unregistered beta", "Registered beta at 127.0.0.1:25610"),
                sync.sync(List.of("beta\tRUNNING\t25610")), "a new port: registered again");
    }

    @Test
    void leavesConfiguredServersAlone() {
        assertEquals(List.of("lobby is already configured in Velocity; leaving it as it is"), sync.sync(List.of("lobby\tRUNNING\t25601")));
        assertEquals(List.of(), sync.sync(List.of("lobby\tRUNNING\t25601")), "warned once");
        sync.sync(List.of());
        assertEquals(Map.of("lobby", 30000), proxy, "never unregistered: not ours");
    }

    @Test
    void hostGoneUnregistersEverythingOfOurs() {
        sync.sync(List.of("alpha\tRUNNING\t25601", "beta\tRUNNING\t25602"));
        assertEquals(List.of("Unregistered alpha", "Unregistered beta"), sync.clear());
        assertEquals(Map.of("lobby", 30000), proxy);
    }

    @Test
    void leavesOutProxiesAndItself() {
        sync.setSelf("proxy");
        assertEquals(List.of("Registered survival at 127.0.0.1:25602"), sync.sync(List.of(
                "proxy\tRUNNING\t25577\tvelocitymc", "other-proxy\tRUNNING\t25578\tvelocitymc", "survival\tRUNNING\t25602\tpapermc")));
        assertEquals(List.of(), sync.sync(List.of("survival\tRUNNING\t25602")), "the older three-column format still works");
    }

}
