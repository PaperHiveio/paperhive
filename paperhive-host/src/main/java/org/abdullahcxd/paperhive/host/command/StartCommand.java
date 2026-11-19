package org.abdullahcxd.paperhive.host.command;

import org.abdullahcxd.paperhive.host.server.ServerManager;
import org.abdullahcxd.paperhive.host.instance.ServerInstance;
import org.abdullahcxd.paperhive.plugin.command.HiveCommandSender;
import org.jetbrains.annotations.NotNull;

import static org.abdullahcxd.paperhive.plugin.command.ConsoleText.*;

final class StartCommand extends ServerCommand {

    StartCommand(ServerManager servers) {
        super(servers, "start", "start a server");
    }

    @Override
    protected void execute(@NotNull HiveCommandSender sender, @NotNull ServerInstance instance) {
        String name = instance.getName();
        if (instance.isActive()) sender.sendMessage(warning(name(name) + " is already " + state(instance.getState().name())));
        else sender.sendMessage(servers.start(name) ? info("Starting " + name(name)) : error("Could not start " + name(name) + muted(", see the log")));
    }

}
