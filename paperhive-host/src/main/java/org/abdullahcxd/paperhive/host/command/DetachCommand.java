package org.abdullahcxd.paperhive.host.command;

import org.abdullahcxd.paperhive.host.server.ServerManager;
import org.abdullahcxd.paperhive.plugin.command.HiveCommand;
import org.abdullahcxd.paperhive.plugin.command.HiveCommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.List;

import static org.abdullahcxd.paperhive.plugin.command.ConsoleText.*;

final class DetachCommand extends HiveCommand {

    private final ServerManager servers;

    DetachCommand(ServerManager servers) {
        super("detach", "", "leave the attached server");
        this.servers = servers;
    }

    @Override
    public void execute(@NotNull HiveCommandSender sender, @NotNull List<String> args) {
        sender.sendMessage(sender.isConsole() && servers.detach() ? success("Detached") : warning("Not attached"));
    }

}
