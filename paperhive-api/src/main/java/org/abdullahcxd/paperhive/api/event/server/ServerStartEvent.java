package org.abdullahcxd.paperhive.api.event.server;

import org.abdullahcxd.paperhive.api.HiveServer;
import org.abdullahcxd.paperhive.api.event.CancellableEvent;
import org.jetbrains.annotations.NotNull;

/**
 * A server is about to start (a command, the API, autostart); cancel it to keep it stopped. Posted on the thread
 * asking for the start. A restart after a crash ({@code Restart: on-crash}) doesn't post this.
 */
public class ServerStartEvent extends CancellableEvent {

    private final HiveServer server;

    public ServerStartEvent(@NotNull HiveServer server) {
        this.server = server;
    }

    public @NotNull HiveServer getServer() {
        return server;
    }

}
