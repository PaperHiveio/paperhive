package org.abdullahcxd.paperhive.plugin.command;

import lombok.Getter;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * A command of the Paperhive host: typed at the {@code hive>} prompt (with or without a leading {@code hive}) or sent
 * with {@code java -jar paperhive.jar hive <command>}. Paperhive's own commands ({@code list}, {@code start}, ...) are
 * these too; a Hive plugin adds its own through {@link HiveCommandRegistry#register}.
 * <p>
 * Not to be confused with Bukkit commands, which run inside one server.
 */
@Getter
public abstract class HiveCommand {

    private final String name;
    /** The arguments after the name, for help and errors, e.g. {@code <name> [port]}; empty if none. */
    private final String usage;
    private final String description;
    private final List<String> aliases;

    protected HiveCommand(@NotNull String name, @NotNull String usage, @NotNull String description, String @NotNull ... aliases) {
        this.name = name;
        this.usage = usage;
        this.description = description;
        this.aliases = List.of(aliases);
    }

    /** @param args the words after the command name */
    public abstract void execute(@NotNull HiveCommandSender sender, @NotNull List<String> args);

    /**
     * Tab completion at the prompt.
     *
     * @param args the words after the command name; the last one is the word being typed (maybe empty)
     * @return candidates for that last word
     */
    public @NotNull List<String> complete(@NotNull HiveCommandSender sender, @NotNull List<String> args) {
        return List.of();
    }

    /** {@code usage: <name> <usage>}, for a wrong number of arguments. */
    protected void sendUsage(@NotNull HiveCommandSender sender) {
        sender.sendMessage("usage: " + name + (usage.isEmpty() ? "" : " " + usage));
    }

}
