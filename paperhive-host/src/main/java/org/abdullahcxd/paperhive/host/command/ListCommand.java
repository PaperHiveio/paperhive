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

final class ListCommand extends HiveCommand {

    private final ServerManager servers;
    private final Function<String, @Nullable HiveServerStats> stats;

    ListCommand(ServerManager servers, Function<String, @Nullable HiveServerStats> stats) {
        super("list", "[--tsv]", "every server with its state, port, players and TPS (--tsv: name, state, port per line, for tools)", "ls");
        this.servers = servers;
        this.stats = stats;
    }

    @Override
    public void execute(@NotNull HiveCommandSender sender, @NotNull List<String> args) {
        if (args.equals(List.of("--tsv"))) { // stable format: the Velocity plugin reads it
            for (ServerInstance i : servers.instances()) sender.sendMessage(i.getName() + "\t" + i.getState() + "\t" + i.getOptions().port());
            return;
        }
        if (!args.isEmpty()) {
            sendUsage(sender);
            return;
        }
        for (ServerInstance i : servers.instances()) {
            HiveServerStats s = stats.apply(i.getName());
            String load = s == null ? "" : String.format("  %d/%d players  %.1f TPS", s.players(), s.maxPlayers(), Math.min(s.tps1m(), 20));
            sender.sendMessage(String.format("%-16s %-8s port %s%s", i.getName(), i.getState(), i.getOptions().port(), load));
        }
    }

}
