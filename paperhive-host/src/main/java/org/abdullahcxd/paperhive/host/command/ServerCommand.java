package org.abdullahcxd.paperhive.host.command;

import org.abdullahcxd.paperhive.host.server.ServerManager;
import org.abdullahcxd.paperhive.host.instance.ServerInstance;
import org.abdullahcxd.paperhive.plugin.command.HiveCommand;
import org.abdullahcxd.paperhive.plugin.command.HiveCommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.List;

import static org.abdullahcxd.paperhive.plugin.command.ConsoleText.*;

/** A command taking one server name: checks it and completes it. */
abstract class ServerCommand extends HiveCommand {

    protected final ServerManager servers;

    protected ServerCommand(ServerManager servers, String name, String description, String... aliases) {
        super(name, "<name>", description, aliases);
        this.servers = servers;
    }

    protected abstract void execute(@NotNull HiveCommandSender sender, @NotNull ServerInstance instance);

    @Override
    public final void execute(@NotNull HiveCommandSender sender, @NotNull List<String> args) {
        if (args.size() != 1) {
            sendUsage(sender);
            return;
        }
        ServerInstance instance = servers.instance(args.getFirst());
        if (instance == null) sender.sendMessage(error("No server named " + value(args.getFirst()) + muted("; known: " + String.join(", ", servers.names()))));
        else execute(sender, instance);
    }

    @Override
    public @NotNull List<String> complete(@NotNull HiveCommandSender sender, @NotNull List<String> args) {
        return args.size() == 1 ? servers.names() : List.of();
    }

}
