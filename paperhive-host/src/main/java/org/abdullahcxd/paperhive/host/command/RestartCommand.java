package org.abdullahcxd.paperhive.host.command;

import org.abdullahcxd.paperhive.host.server.ServerManager;
import org.abdullahcxd.paperhive.host.instance.ServerInstance;
import org.abdullahcxd.paperhive.plugin.command.HiveCommandSender;
import org.jetbrains.annotations.NotNull;

import static org.abdullahcxd.paperhive.plugin.command.ConsoleText.*;

final class RestartCommand extends ServerCommand {

    RestartCommand(ServerManager servers) {
        super(servers, "restart", "stop a server if it runs, then start it");
    }

    @Override
    protected void execute(@NotNull HiveCommandSender sender, @NotNull ServerInstance instance) {
        servers.restart(instance);
        sender.sendMessage(info("Restarting " + name(instance.getName())));
    }

}
