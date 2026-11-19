package org.abdullahcxd.paperhive.host.command;

import lombok.extern.slf4j.Slf4j;
import org.abdullahcxd.paperhive.api.HiveServerStats;
import org.abdullahcxd.paperhive.host.server.ServerManager;
import org.abdullahcxd.paperhive.host.instance.ServerInstance;
import org.abdullahcxd.paperhive.plugin.command.HiveCommand;
import org.abdullahcxd.paperhive.plugin.command.HiveCommandRegistry;
import org.abdullahcxd.paperhive.plugin.command.HiveCommandSender;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Every {@link HiveCommand}, Paperhive's own and those of Hive plugins, and how a typed line reaches one:
 * <ol>
 *   <li>a leading {@code hive} is optional ({@code hive list} = {@code list})</li>
 *   <li>a command name or alias runs that command</li>
 *   <li>{@code <server> <command>} runs a command on that server, {@code all <command>} on every server</li>
 * </ol>
 */
@Slf4j
public final class CommandRegistry implements HiveCommandRegistry {

    private final ServerManager servers;
    private final Map<String, HiveCommand> byName = new ConcurrentHashMap<>();

    public CommandRegistry(ServerManager servers) {
        this.servers = servers;
    }

    /**
     * With Paperhive's own commands.
     *
     * @param stats a running server's load (null if unknown), for {@code list} and {@code status}
     */
    public static @NotNull CommandRegistry withBuiltins(@NotNull ServerManager servers, Function<String, @Nullable HiveServerStats> stats) {
        CommandRegistry registry = new CommandRegistry(servers);
        for (HiveCommand command : List.of(new HelpCommand(registry), new ListCommand(servers, stats), new StatusCommand(servers, stats),
                new CreateCommand(servers), new SetCommand(servers), new DeleteCommand(servers), new StartCommand(servers),
                new StopCommand(servers), new RestartCommand(servers), new AttachCommand(servers), new DetachCommand(servers),
                new ShutdownCommand(servers))) {
            registry.register(command);
        }
        return registry;
    }

    @Override
    public synchronized void register(@NotNull HiveCommand command) {
        List<String> keys = keys(command);
        for (String key : keys) {
            HiveCommand taken = byName.get(key);
            if (taken != null) throw new IllegalArgumentException("'" + key + "' is already the command " + taken.getName());
        }
        for (String key : keys) byName.put(key, command);
    }

    @Override
    public synchronized void unregister(@NotNull HiveCommand command) {
        for (String key : keys(command)) byName.remove(key, command);
    }

    @Override
    public @Nullable HiveCommand find(@NotNull String nameOrAlias) {
        return byName.get(nameOrAlias.toLowerCase(Locale.ROOT));
    }

    @Override
    public @NotNull @Unmodifiable Collection<HiveCommand> commands() {
        return List.copyOf(new LinkedHashSet<>(byName.values()).stream()
                .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName())).toList());
    }

    /** Runs a typed line (see the class comment); everything it has to say goes to {@code sender}. */
    public void execute(@NotNull HiveCommandSender sender, @NotNull String line) {
        String stripped = stripHive(line);
        List<String> words = words(stripped);
        if (words.isEmpty()) return;
        String first = words.getFirst();
        HiveCommand command = find(first);
        if (command != null) {
            try {
                command.execute(sender, words.subList(1, words.size()));
            } catch (RuntimeException e) {
                log.error("Hive command '{}' failed", line, e);
                sender.sendMessage(command.getName() + " failed: " + e);
            }
            return;
        }
        String rest = stripped.substring(first.length()).strip(); // as typed: the server parses it
        ServerInstance target = servers.instance(first);
        if (rest.isEmpty() || (target == null && !first.equals("all"))) {
            sender.sendMessage("unknown command '" + first + "'; `help` lists them");
            return;
        }
        for (ServerInstance instance : target != null ? List.of(target) : servers.instances()) {
            if (!instance.dispatchCommand(rest)) sender.sendMessage(instance.getName() + " isn't running");
        }
    }

    /** Candidates for the last word of {@code line} (which may be empty, when completing a new word). */
    public @NotNull List<String> complete(@NotNull HiveCommandSender sender, @NotNull String line) {
        List<String> words = new ArrayList<>(words(line));
        if (line.isEmpty() || Character.isWhitespace(line.charAt(line.length() - 1))) words.add("");
        if (words.size() > 1 && words.getFirst().equals("hive")) words.removeFirst();
        if (words.size() == 1) {
            List<String> firsts = new ArrayList<>(byName.keySet());
            firsts.addAll(servers.names());
            firsts.add("all");
            return firsts.stream().sorted().toList();
        }
        HiveCommand command = find(words.getFirst());
        return command == null ? List.of() : command.complete(sender, words.subList(1, words.size()));
    }

    static @NotNull String stripHive(@NotNull String line) {
        String s = line.strip();
        return s.equals("hive") ? "help" : s.startsWith("hive ") ? s.substring(5).strip() : s;
    }

    private static List<String> words(@NotNull String line) {
        String s = line.strip();
        return s.isEmpty() ? List.of() : Arrays.asList(s.split("\\s+"));
    }

    private static @NotNull List<String> keys(@NotNull HiveCommand command) {
        List<String> keys = new ArrayList<>();
        keys.add(command.getName().toLowerCase(Locale.ROOT));
        for (String alias : command.getAliases()) keys.add(alias.toLowerCase(Locale.ROOT));
        return keys;
    }

}
