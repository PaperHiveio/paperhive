package org.abdullahcxd.paperhive.host.command;

import org.abdullahcxd.paperhive.api.HiveServerStats;
import org.abdullahcxd.paperhive.host.server.ServerManager;
import org.abdullahcxd.paperhive.host.config.HiveConfig;
import org.abdullahcxd.paperhive.host.instance.ServerInstance;
import org.abdullahcxd.paperhive.plugin.command.HiveCommandSender;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.Locale;
import java.util.function.Function;
import org.jetbrains.annotations.Nullable;

import static org.abdullahcxd.paperhive.plugin.command.ConsoleText.*;

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
        sender.sendMessage(name(i.getName()) + "  " + state(i.getState().name()) + (uptime == null ? "" : muted(" for ") + value(format(uptime))));
        sender.sendMessage(row("Software", i.getSoftware().displayName() + " " + server.version().getVersion()));
        sender.sendMessage(row("Port", String.valueOf(server.port())));
        sender.sendMessage(row("Root", i.getRoot().toString()));
        sender.sendMessage(row("Autostart", String.valueOf(server.autostart())));
        sender.sendMessage(row("Restart", server.restart().name().toLowerCase(Locale.ROOT).replace('_', '-')));
        HiveServerStats load = stats.apply(i.getName());
        if (load != null) {
            // Paper reports a hair above 20 at full speed; show it like Paper's /tps does
            sender.sendMessage(rowStyled("TPS", tps(load.tps1m()) + muted(", ") + tps(load.tps5m()) + muted(", ") + tps(load.tps15m())
                    + muted(" (1m, 5m, 15m)  ") + value(String.format(Locale.ROOT, "%.1f", load.msptAverage())) + muted(" ms/tick")));
            sender.sendMessage(rowStyled("Players", value(String.valueOf(load.players())) + muted("/" + load.maxPlayers())));
        }
        var loader = i.getClassLoader();
        if (loader != null) {
            sender.sendMessage(rowStyled("Threads", value(String.valueOf(i.threadCount())) + muted(", classes ") + value(String.valueOf(loader.definedClassCount()))
                    + muted("  (the server and its libraries; plugins' aren't counted)")));
        }
        if (i.getExitStatus() != null && uptime == null) sender.sendMessage(row("Last exit", String.valueOf(i.getExitStatus())));
    }

    private static String row(String label, String value) {
        return rowStyled(label, value(value));
    }

    private static String rowStyled(String label, String styled) {
        return "  " + pad(muted(label), 12) + styled;
    }

    @Contract(pure = true)
    private static @NotNull String format(@NotNull Duration d) {
        long s = d.toSeconds();
        return s >= 3600 ? (s / 3600) + "h" + (s % 3600 / 60) + "m" : s >= 60 ? (s / 60) + "m" + (s % 60) + "s" : s + "s";
    }

}
