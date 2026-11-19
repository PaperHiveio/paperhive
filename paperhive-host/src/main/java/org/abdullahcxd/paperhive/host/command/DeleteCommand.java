package org.abdullahcxd.paperhive.host.command;

import org.abdullahcxd.paperhive.host.server.ServerManager;
import org.abdullahcxd.paperhive.host.config.HiveConfig;
import org.abdullahcxd.paperhive.host.instance.ServerInstance;
import org.abdullahcxd.paperhive.plugin.command.HiveCommandSender;
import org.jetbrains.annotations.NotNull;

import static org.abdullahcxd.paperhive.plugin.command.ConsoleText.*;

final class DeleteCommand extends ServerCommand {

    DeleteCommand(ServerManager servers) {
        super(servers, "delete", "remove a stopped server from paperhive.yml (its folder stays)");
    }

    @Override
    protected void execute(@NotNull HiveCommandSender sender, @NotNull ServerInstance instance) {
        String name = instance.getName();
        if (instance.isActive()) {
            sender.sendMessage(error(name(name) + " is " + state(instance.getState().name()) + muted("; stop it first")));
            return;
        }
        try {
            servers.delete(instance);
            sender.sendMessage(success("Deleted " + name(name) + muted(" from " + servers.getConfigFile() + "; its folder " + instance.getRoot() + " is kept")));
        } catch (HiveConfig.HiveConfigException e) {
            sender.sendMessage(error("Not deleted: " + e.getMessage()));
        }
    }

}
