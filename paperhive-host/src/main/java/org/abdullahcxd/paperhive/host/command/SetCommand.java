package org.abdullahcxd.paperhive.host.command;

import org.abdullahcxd.paperhive.downloader.MCSoftware;
import org.abdullahcxd.paperhive.host.server.ServerManager;
import org.abdullahcxd.paperhive.host.config.HiveConfig;
import org.abdullahcxd.paperhive.host.instance.ServerInstance;
import org.abdullahcxd.paperhive.plugin.command.HiveCommand;
import org.abdullahcxd.paperhive.plugin.command.HiveCommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.List;

import static org.abdullahcxd.paperhive.plugin.command.ConsoleText.*;

final class SetCommand extends HiveCommand {

    private static final List<String> KEYS = List.of("Software", "Version", "Port", "IP", "Root", "Plugins", "Args", "Autostart", "Restart", "DisabledHivePlugins", "SharedPlugins");

    private final ServerManager servers;

    SetCommand(ServerManager servers) {
        super("set", "<name> <Key> [value]", "change a setting in paperhive.yml (no value: the default); applies at the next start");
        this.servers = servers;
    }

    @Override
    public void execute(@NotNull HiveCommandSender sender, @NotNull List<String> args) {
        if (args.size() < 2) {
            sendUsage(sender);
            return;
        }
        String name = args.get(0), key = args.get(1);
        // the value is YAML and may hold spaces, e.g. [--a, --b]
        String value = args.size() > 2 ? String.join(" ", args.subList(2, args.size())) : null;
        ServerInstance instance = servers.instance(name);
        if (instance == null) {
            sender.sendMessage(error("No server named " + value(name) + muted("; known: " + String.join(", ", servers.names()))));
            return;
        }
        try {
            servers.set(name, key, value);
        } catch (HiveConfig.HiveConfigException e) {
            sender.sendMessage(error("Not changed: " + e.getMessage()));
            return;
        }
        sender.sendMessage((value == null ? "Reset " + key + " of " + name : "Set " + key + " of " + name + " to " + value)
                + (instance.isActive() ? "; it applies at the next `restart " + name + "`" : ""));
    }

    @Override
    public @NotNull List<String> complete(@NotNull HiveCommandSender sender, @NotNull List<String> args) {
        return switch (args.size()) {
            case 1 -> servers.names();
            case 2 -> KEYS;
            case 3 -> switch (args.get(1)) {
                case "Autostart" -> List.of("true", "false");
                case "Restart" -> List.of("never", "on-crash");
                case "Software" -> Arrays.stream(MCSoftware.values()).map(MCSoftware::getName).toList();
                default -> List.of();
            };
            default -> List.of();
        };
    }

}
