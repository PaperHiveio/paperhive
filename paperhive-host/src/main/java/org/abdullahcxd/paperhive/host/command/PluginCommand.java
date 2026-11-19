package org.abdullahcxd.paperhive.host.command;

import org.abdullahcxd.paperhive.host.plugin.HivePluginManager;
import org.abdullahcxd.paperhive.plugin.PaperhivePlugin;
import org.abdullahcxd.paperhive.plugin.command.HiveCommand;
import org.abdullahcxd.paperhive.plugin.command.HiveCommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public final class PluginCommand extends HiveCommand {

    private final HivePluginManager plugins;

    public PluginCommand(HivePluginManager plugins) {
        super("plugin", "list | enable <name> | disable <name>",
                "Hive plugins (a server half follows at that server's next start)", "plugins");
        this.plugins = plugins;
    }

    @Override
    public void execute(@NotNull HiveCommandSender sender, @NotNull List<String> args) {
        if (args.isEmpty() || (args.size() == 1 && args.getFirst().equals("list"))) {
            if (plugins.plugins().isEmpty()) sender.sendMessage("no Hive plugins; put them in plugins/");
            for (PaperhivePlugin p : plugins.plugins()) {
                sender.sendMessage(String.format("%-20s %-12s %-9s%s", p.getPluginDescriptor().getName(), p.getPluginDescriptor().getVersion(),
                        plugins.isEnabled(p) ? "enabled" : "disabled", plugins.hasServerHalf(p) ? "  + server half" : ""));
            }
            return;
        }
        if (args.size() != 2 || !List.of("enable", "disable").contains(args.getFirst())) {
            sendUsage(sender);
            return;
        }
        PaperhivePlugin plugin = plugins.findPlugin(args.get(1));
        if (plugin == null) {
            sender.sendMessage("no Hive plugin named " + args.get(1));
            return;
        }
        boolean enable = args.getFirst().equals("enable");
        if (enable) plugins.enablePlugin(plugin);
        else plugins.disablePlugin(plugin);
        String name = plugin.getPluginDescriptor().getName();
        if (plugins.isEnabled(plugin) != enable) sender.sendMessage("Could not enable " + name + ", see the log");
        else sender.sendMessage((enable ? "Enabled " : "Disabled ") + name + (plugins.hasServerHalf(plugin) ? "; its server half follows at each server's next start" : ""));
    }

    @Override
    public @NotNull List<String> complete(@NotNull HiveCommandSender sender, @NotNull List<String> args) {
        if (args.size() == 1) return List.of("list", "enable", "disable");
        if (args.size() == 2 && !args.getFirst().equals("list")) {
            return plugins.plugins().stream().map(p -> p.getPluginDescriptor().getName()).toList();
        }
        return List.of();
    }

}
