package org.abdullahcxd.paperhive.api.event.server;

import org.abdullahcxd.paperhive.api.HiveServer;
import org.jetbrains.annotations.NotNull;

/** A server crashed (CRASHED: non-zero exit status, or its main method threw). Posted on a Paperhive thread, right after its {@link ServerStateChangeEvent}. */
public class ServerCrashEvent extends ServerEvent {

    public ServerCrashEvent(@NotNull HiveServer server) {
        super(server);
    }

}
