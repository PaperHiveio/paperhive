package org.abdullahcxd.paperhive.velocity;

import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NonNull;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Keeps the proxy's servers in step with the hive's {@code RUNNING} ones. Only touches servers it registered itself:
 * one the proxy already has under that name (from {@code velocity.toml}) is left alone.
 */
final class ServerSync {

    /** The few proxy calls this needs (a seam for tests). */
    interface Proxy {
        boolean has(String name);

        void register(String name, InetSocketAddress address);

        void unregister(String name);
    }

    private final Proxy proxy;
    private final String host;
    private volatile @Nullable String self;
    private final Map<String, InetSocketAddress> ours = new LinkedHashMap<>();
    private final Set<String> warned = new HashSet<>();

    /** @param host the address the proxy reaches the hive's servers at (the host machine, from the proxy) */
    ServerSync(Proxy proxy, String host) {
        this.proxy = proxy;
        this.host = host;
    }

    /** The hive's name for this proxy, when it runs inside the hive: never registered with itself. */
    void setSelf(@Nullable String name) {
        self = name;
    }

    /**
     * @param listing {@code list --tsv} from the host: {@code name<TAB>state<TAB>port[<TAB>software]} per line; other
     *                proxies ({@code velocitymc}) and this proxy itself are left out
     * @return what changed, to log
     */
    synchronized @NonNull List<String> sync(@NonNull List<String> listing) {
        Map<String, InetSocketAddress> wanted = new LinkedHashMap<>();
        for (String line : listing) {
            String[] f = line.split("\t");
            if (f.length < 3 || !f[1].equals("RUNNING") || f[0].equals(self)) continue;
            if (f.length >= 4 && f[3].equals("velocitymc")) continue; // a proxy, not a server to send players to
            try {
                wanted.put(f[0], InetSocketAddress.createUnresolved(host, Integer.parseInt(f[2])));
            } catch (NumberFormatException noPort) {
                // a server without a Port setting: nothing to connect to
            }
        }
        List<String> changes = new ArrayList<>();
        for (Map.Entry<String, InetSocketAddress> e : List.copyOf(ours.entrySet())) {
            if (e.getValue().equals(wanted.get(e.getKey()))) continue;
            proxy.unregister(e.getKey());
            ours.remove(e.getKey());
            changes.add("Unregistered " + e.getKey());
        }
        for (Map.Entry<String, InetSocketAddress> e : wanted.entrySet()) {
            if (ours.containsKey(e.getKey())) continue;
            if (proxy.has(e.getKey())) {
                if (warned.add(e.getKey())) changes.add(e.getKey() + " is already configured in Velocity; leaving it as it is");
                continue;
            }
            proxy.register(e.getKey(), e.getValue());
            ours.put(e.getKey(), e.getValue());
            changes.add("Registered " + e.getKey() + " at " + host + ":" + e.getValue().getPort());
        }
        return changes;
    }

    /** The host is gone: its servers are too. */
    synchronized @NonNull List<String> clear() {
        return sync(List.of());
    }

}
