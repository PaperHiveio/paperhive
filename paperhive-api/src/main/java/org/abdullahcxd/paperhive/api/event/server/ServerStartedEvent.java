package org.abdullahcxd.paperhive.api.event.server;

import org.abdullahcxd.paperhive.api.HiveServer;
import org.jetbrains.annotations.NotNull;

/** A server is up (RUNNING: booted, after its first tick). Posted on a Paperhive thread, right after its {@link ServerStateChangeEvent}. */
public class ServerStartedEvent extends ServerEvent {

    public ServerStartedEvent(@NotNull HiveServer server) {
        super(server);
    }

}
