package org.abdullahcxd.paperhive.host.command;

import org.abdullahcxd.paperhive.host.ServerManager;
import org.abdullahcxd.paperhive.host.instance.ServerInstance;
import org.abdullahcxd.paperhive.plugin.command.HiveCommandSender;
import org.jetbrains.annotations.NotNull;

final class StartCommand extends ServerCommand {

    StartCommand(ServerManager servers) {
        super(servers, "start", "start a server");
    }

    @Override
    protected void execute(HiveCommandSender sender, @NotNull ServerInstance instance) {
        String name = instance.getName();
        if (instance.isActive()) sender.sendMessage(name + " is already " + instance.getState());
        else sender.sendMessage(servers.start(name) ? "Starting " + name : "Could not start " + name + ", see the log");
    }

}
