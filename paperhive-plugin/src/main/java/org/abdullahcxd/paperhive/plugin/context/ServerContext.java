package org.abdullahcxd.paperhive.plugin.context;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.abdullahcxd.paperhive.api.HiveServer;

/** What {@code onServerStart} gets: the server that's now up. */
@AllArgsConstructor
@Getter
public class ServerContext {

    private String serverName, serverIp;
    private int serverPort;
    /** For anything else: state, commands, {@link HiveServer#runOnServer} to reach its Bukkit API. */
    private HiveServer server;

}
