package org.abdullahcxd.paperhive.host.command;

import org.abdullahcxd.paperhive.plugin.command.HiveCommand;
import org.abdullahcxd.paperhive.plugin.command.HiveCommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.List;

final class HelpCommand extends HiveCommand {

    private final CommandRegistry registry;

    HelpCommand(CommandRegistry registry) {
        super("help", "", "this list", "?");
        this.registry = registry;
    }

    @Override
    public void execute(@NotNull HiveCommandSender sender, @NotNull List<String> args) {
        for (HiveCommand command : registry.commands()) {
            String usage = command.getName() + (command.getUsage().isEmpty() ? "" : " " + command.getUsage());
            String aliases = command.getAliases().isEmpty() ? "" : " (also: " + String.join(", ", command.getAliases()) + ")";
            sender.sendMessage(String.format("%-32s %s", usage, command.getDescription() + aliases));
        }
        sender.sendMessage(String.format("%-32s %s", "<name> <command>", "run one command on that server"));
        sender.sendMessage(String.format("%-32s %s", "all <command>", "run one command on every server"));
    }

}
