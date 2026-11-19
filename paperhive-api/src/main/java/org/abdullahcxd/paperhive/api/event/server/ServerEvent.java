package org.abdullahcxd.paperhive.api.event.server;

import org.abdullahcxd.paperhive.api.HiveServer;
import org.abdullahcxd.paperhive.api.event.HiveEvent;
import org.jetbrains.annotations.NotNull;

/** Something happened to one server. */
public abstract class ServerEvent extends HiveEvent {

    private final HiveServer server;

    protected ServerEvent(@NotNull HiveServer server) {
        this.server = server;
    }

    public @NotNull HiveServer getServer() {
        return server;
    }

}
