package org.abdullahcxd.paperhive.host.command;

import org.abdullahcxd.paperhive.api.HiveServerStats;
import org.abdullahcxd.paperhive.host.ServerManager;
import org.abdullahcxd.paperhive.host.config.HiveConfig;
import org.abdullahcxd.paperhive.host.instance.ServerInstance;
import org.abdullahcxd.paperhive.plugin.command.HiveCommandSender;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.Locale;
import java.util.function.Function;
import org.jetbrains.annotations.Nullable;

final class StatusCommand extends ServerCommand {

    private final Function<String, @Nullable HiveServerStats> stats;

    StatusCommand(ServerManager servers, Function<String, @Nullable HiveServerStats> stats) {
        super(servers, "status", "one server in detail");
        this.stats = stats;
    }

    @Override
    protected void execute(@NotNull HiveCommandSender sender, @NotNull ServerInstance i) {
        HiveConfig.Server server = servers.server(i.getName());
        Duration uptime = i.uptime();
        sender.sendMessage(i.getName() + ": " + i.getState() + (uptime == null ? "" : " for " + format(uptime)));
        sender.sendMessage("  Port:      " + server.port());
        sender.sendMessage("  Root:      " + i.getRoot());
        sender.sendMessage("  Autostart: " + server.autostart());
        sender.sendMessage("  Restart:   " + server.restart().name().toLowerCase(Locale.ROOT).replace('_', '-'));
        HiveServerStats load = stats.apply(i.getName());
        if (load != null) {
            // Paper reports a hair above 20 at full speed; show it like Paper's /tps does
            sender.sendMessage(String.format("  TPS:       %.1f, %.1f, %.1f (1m, 5m, 15m), %.1f ms/tick",
                    Math.min(load.tps1m(), 20), Math.min(load.tps5m(), 20), Math.min(load.tps15m(), 20), load.msptAverage()));
            sender.sendMessage("  Players:   " + load.players() + "/" + load.maxPlayers());
        }
        var loader = i.getClassLoader();
        if (loader != null) {
            sender.sendMessage("  Threads:   " + i.threadCount() + ", classes: " + loader.definedClassCount() + " (Paper and its libraries; plugins' aren't counted)");
        }
        if (i.getExitStatus() != null && uptime == null) sender.sendMessage("  Last exit: " + i.getExitStatus());
    }

    @Contract(pure = true)
    private static @NotNull String format(@NotNull Duration d) {
        long s = d.toSeconds();
        return s >= 3600 ? (s / 3600) + "h" + (s % 3600 / 60) + "m" : s >= 60 ? (s / 60) + "m" + (s % 60) + "s" : s + "s";
    }

}
