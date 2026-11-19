package org.abdullahcxd.paperhive.api.event.server;

import org.abdullahcxd.paperhive.api.HiveServer;
import org.jetbrains.annotations.NotNull;

/** A server stopped normally (STOPPED). Posted on a Paperhive thread, right after its {@link ServerStateChangeEvent}. */
public class ServerStoppedEvent extends ServerEvent {

    public ServerStoppedEvent(@NotNull HiveServer server) {
        super(server);
    }

}
