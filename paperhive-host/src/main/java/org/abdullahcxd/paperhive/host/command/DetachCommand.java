package org.abdullahcxd.paperhive.host.command;

import org.abdullahcxd.paperhive.host.ServerManager;
import org.abdullahcxd.paperhive.plugin.command.HiveCommand;
import org.abdullahcxd.paperhive.plugin.command.HiveCommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.List;

final class DetachCommand extends HiveCommand {

    private final ServerManager servers;

    DetachCommand(ServerManager servers) {
        super("detach", "", "leave the attached server");
        this.servers = servers;
    }

    @Override
    public void execute(@NotNull HiveCommandSender sender, @NotNull List<String> args) {
        sender.sendMessage(sender.isConsole() && servers.detach() ? "Detached" : "not attached");
    }

}
