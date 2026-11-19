package org.abdullahcxd.paperhive.api;

import org.abdullahcxd.paperhive.api.event.EventBus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Every server of this Paperhive host. Usable from the host (Hive plugins) and from inside servers (Paper plugins,
 * Hive plugins' server halves); thread-safe. Get it with {@link Paperhive#getHive()}.
 * <p>
 * Listeners and subscriptions made by code inside a server are removed when that server stops (and a Hive plugin's
 * when it's disabled), so they can't keep its classes alive; add them again when it starts.
 */
public interface Hive {

    @NotNull List<HiveServer> getServers();

    @Nullable HiveServer getServer(@NotNull String name);

    /** The server the calling thread belongs to; null on the host's own threads. */
    @Nullable HiveServer getCurrentServer();

    /**
     * Adds a server to {@code paperhive.yml}, not started.
     *
     * @throws IllegalArgumentException with every reason, if it would make the file invalid (name taken, port used, ...)
     */
    @NotNull HiveServer createServer(@NotNull String name, int port);

    /** Hive events: server state changes, starts (cancellable), crashes, and plugins' own events. */
    @NotNull EventBus getEventBus();

    /** Delivers {@code message} to every subscriber of {@code channel}, on the host and in every server, in order, on a Paperhive thread. */
    void publish(@NotNull String channel, @NotNull String message);

    @NotNull Registration subscribe(@NotNull String channel, @NotNull HiveMessageListener listener);

}
