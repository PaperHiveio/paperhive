package org.abdullahcxd.paperhive.host.command;

import org.abdullahcxd.paperhive.host.server.ServerManager;
import org.abdullahcxd.paperhive.host.config.HiveConfig;
import org.abdullahcxd.paperhive.plugin.command.HiveCommand;
import org.abdullahcxd.paperhive.plugin.command.HiveCommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.List;

final class CreateCommand extends HiveCommand {

    private final ServerManager servers;

    CreateCommand(ServerManager servers) {
        super("create", "<name> [port]", "add a server to paperhive.yml (next free port if none given)");
        this.servers = servers;
    }

    @Override
    public void execute(@NotNull HiveCommandSender sender, @NotNull List<String> args) {
        if (args.isEmpty() || args.size() > 2) {
            sendUsage(sender);
            return;
        }
        String name = args.getFirst();
        int port;
        try {
            port = args.size() == 2 ? Integer.parseInt(args.get(1)) : servers.nextFreePort();
        } catch (NumberFormatException e) {
            sender.sendMessage("port: expected a number, got '" + args.get(1) + "'");
            return;
        }
        try {
            HiveConfig.Server server = servers.create(name, port);
            sender.sendMessage("Created " + name + " on port " + port + " in " + server.root() + "; start it with `start " + name + "`");
        } catch (HiveConfig.HiveConfigException e) {
            sender.sendMessage("Not created: " + e.getMessage());
        }
    }

}
