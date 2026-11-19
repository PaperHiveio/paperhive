package org.abdullahcxd.paperhive.host.command;

import org.abdullahcxd.paperhive.api.HiveServerStats;
import org.abdullahcxd.paperhive.host.server.ServerManager;
import org.abdullahcxd.paperhive.host.instance.ServerInstance;
import org.abdullahcxd.paperhive.plugin.command.HiveCommand;
import org.abdullahcxd.paperhive.plugin.command.HiveCommandSender;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Function;

import static org.abdullahcxd.paperhive.plugin.command.ConsoleText.*;

final class ListCommand extends HiveCommand {

    private final ServerManager servers;
    private final Function<String, @Nullable HiveServerStats> stats;

    ListCommand(ServerManager servers, Function<String, @Nullable HiveServerStats> stats) {
        super("list", "[--tsv]", "every server with its state, port, players and TPS (--tsv: name, state, port, software per line, for tools)", "ls");
        this.servers = servers;
        this.stats = stats;
    }

    @Override
    public void execute(@NotNull HiveCommandSender sender, @NotNull List<String> args) {
        if (args.equals(List.of("--tsv"))) { // stable format: the Velocity plugin reads it (columns are only ever added)
            for (ServerInstance i : servers.instances()) {
                sender.sendMessage(i.getName() + "\t" + i.getState() + "\t" + i.getOptions().port() + "\t" + i.getSoftware().id().getName());
            }
            return;
        }
        if (!args.isEmpty()) {
            sendUsage(sender);
            return;
        }
        if (servers.instances().isEmpty()) {
            sender.sendMessage(warning("No servers yet; add one with " + command("create <name> <port>")));
            return;
        }
        sender.sendMessage(muted("  " + pad("SERVER", 18) + pad("STATE", 10) + pad("PORT", 7) + pad("SOFTWARE", 12) + "PLAYERS   TPS"));
        for (ServerInstance i : servers.instances()) {
            HiveServerStats s = stats.apply(i.getName());
            String load = s == null ? "" : pad(value(s.players() + "") + muted("/" + s.maxPlayers()), 10) + tps(s.tps1m());
            sender.sendMessage("  " + pad(name(i.getName()), 18) + pad(state(i.getState().name()), 10)
                    + pad(String.valueOf(i.getOptions().port()), 7) + pad(muted(i.getSoftware().id().getName()), 12) + load);
        }
    }

}
