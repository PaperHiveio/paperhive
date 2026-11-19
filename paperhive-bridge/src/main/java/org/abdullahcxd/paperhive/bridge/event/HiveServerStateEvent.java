package org.abdullahcxd.paperhive.bridge.event;

import org.abdullahcxd.paperhive.api.HiveServer;
import org.abdullahcxd.paperhive.api.HiveServerState;
import org.abdullahcxd.paperhive.api.Paperhive;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * A Bukkit event, fired in every server (on its main thread) when any server of the hive changes state: the hive's
 * {@code ServerStateChangeEvent}, for plain {@code @EventHandler} listeners. Any plugin in the server can listen,
 * also a normal Paper plugin.
 * <pre>{@code
 * @EventHandler
 * public void onHiveServer(HiveServerStateEvent event) {
 *     if (event.getTo() == HiveServerState.RUNNING && !event.isThisServer()) {
 *         getServer().broadcast(Component.text(event.getServer().getName() + " is up"));
 *     }
 * }
 * }</pre>
 */
public final class HiveServerStateEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final HiveServer server;
    private final HiveServerState from, to;

    public HiveServerStateEvent(@NotNull HiveServer server, @NotNull HiveServerState from, @NotNull HiveServerState to) {
        this.server = server;
        this.from = from;
        this.to = to;
    }

    /** The server that changed (possibly this one, e.g. {@code RUNNING} after it booted). */
    public @NotNull HiveServer getServer() {
        return server;
    }

    public @NotNull HiveServerState getFrom() {
        return from;
    }

    public @NotNull HiveServerState getTo() {
        return to;
    }

    /** Whether it's about the server this event fires in. */
    public boolean isThisServer() {
        return server.equals(Paperhive.getHive().getCurrentServer());
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }

}
