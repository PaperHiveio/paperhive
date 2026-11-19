package org.abdullahcxd.paperhive.host.command;

import org.abdullahcxd.paperhive.plugin.command.HiveCommand;
import org.abdullahcxd.paperhive.plugin.command.HiveCommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.regex.Pattern;

import static org.abdullahcxd.paperhive.plugin.command.ConsoleText.*;

final class HelpCommand extends HiveCommand {

    // `shutdown` in a description is something to type
    private static final Pattern BACKTICKED = Pattern.compile("`([^`]+)`");

    private final CommandRegistry registry;

    HelpCommand(CommandRegistry registry) {
        super("help", "", "this list", "?");
        this.registry = registry;
    }

    @Override
    public void execute(@NotNull HiveCommandSender sender, @NotNull List<String> args) {
        sender.sendMessage(name("Paperhive") + muted(" commands"));
        for (HiveCommand command : registry.commands()) {
            String usage = command(command.getName()) + (command.getUsage().isEmpty() ? "" : " " + muted(command.getUsage()));
            String aliases = command.getAliases().isEmpty() ? "" : muted("  (also " + String.join(", ", command.getAliases()) + ")");
            sender.sendMessage(line(usage, BACKTICKED.matcher(command.getDescription()).replaceAll(m -> command(m.group(1))) + aliases));
        }
        sender.sendMessage("");
        sender.sendMessage(line(command("<name>") + " " + muted("<command>"), "run one command on that server"));
        sender.sendMessage(line(command("all") + " " + muted("<command>"), "run one command on every server"));
    }

    private static String line(String usage, String description) {
        return "  " + pad(usage + "  ", 40) + description;
    }

}
