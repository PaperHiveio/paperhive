package org.abdullahcxd.paperhive.host.command;

import org.abdullahcxd.paperhive.host.server.ServerManager;
import org.abdullahcxd.paperhive.host.instance.ServerInstance;
import org.abdullahcxd.paperhive.plugin.command.HiveCommandSender;
import org.jetbrains.annotations.NotNull;

import static org.abdullahcxd.paperhive.plugin.command.ConsoleText.*;

final class StopCommand extends ServerCommand {

    StopCommand(ServerManager servers) {
        super(servers, "stop", "stop a server (`shutdown` stops all and Paperhive)");
    }

    @Override
    protected void execute(@NotNull HiveCommandSender sender, @NotNull ServerInstance instance) {
        if (!instance.isActive()) {
            sender.sendMessage(warning(name(instance.getName()) + " isn't running"));
            return;
        }
        servers.stop(instance);
        sender.sendMessage(info("Stopping " + name(instance.getName())));
    }

}
