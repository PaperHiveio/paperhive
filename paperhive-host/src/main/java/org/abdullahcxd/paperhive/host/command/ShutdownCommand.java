package org.abdullahcxd.paperhive.host.command;

import org.abdullahcxd.paperhive.host.server.ServerManager;
import org.abdullahcxd.paperhive.plugin.command.HiveCommand;
import org.abdullahcxd.paperhive.plugin.command.HiveCommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.List;

final class ShutdownCommand extends HiveCommand {

    private final ServerManager servers;

    ShutdownCommand(ServerManager servers) {
        super("shutdown", "", "stop every server and end Paperhive", "exit");
        this.servers = servers;
    }

    @Override
    public void execute(@NotNull HiveCommandSender sender, @NotNull List<String> args) {
        sender.sendMessage("Stopping every server and Paperhive");
        servers.shutdown();
    }

}
