package org.abdullahcxd.paperhive.api.event.server;

import org.abdullahcxd.paperhive.api.HiveServer;
import org.abdullahcxd.paperhive.api.HiveServerState;
import org.jetbrains.annotations.NotNull;

/** Every state change, in order, on a Paperhive thread. The specific events below follow it. */
public class ServerStateChangeEvent extends ServerEvent {

    private final HiveServerState from, to;

    public ServerStateChangeEvent(@NotNull HiveServer server, @NotNull HiveServerState from, @NotNull HiveServerState to) {
        super(server);
        this.from = from;
        this.to = to;
    }

    public @NotNull HiveServerState getFrom() {
        return from;
    }

    public @NotNull HiveServerState getTo() {
        return to;
    }

}
