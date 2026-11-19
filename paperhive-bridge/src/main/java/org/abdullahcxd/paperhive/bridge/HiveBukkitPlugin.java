package org.abdullahcxd.paperhive.bridge;

import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import org.abdullahcxd.paperhive.api.Hive;
import org.abdullahcxd.paperhive.api.HiveServer;
import org.abdullahcxd.paperhive.api.Paperhive;
import org.bukkit.Server;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * A Hive plugin's server half: named by {@code server-main} in its {@code paperhive.yml}, loaded by every server the
 * Hive plugin is enabled for. Not a {@code JavaPlugin}: Paperhive's bridge loads it directly (no {@code plugin.yml},
 * no remapping, no Bukkit plugin lifecycle to get wrong) and gives it what a Bukkit plugin needs:
 * {@link #getServer()}, {@link #registerListener}, {@link #registerCommand} (Paper's Brigadier commands), the
 * scheduler helpers, {@link #getDataFolder()} and a logger. What it registers is removed if it fails to enable or is
 * disabled. Bukkit APIs that want a {@link Plugin} take {@link #getBukkitPlugin()}.
 * <p>
 * It's a different class (own loader) in every server and on the host, so the halves talk through the {@link Hive}
 * (events, messages, {@link HiveServer}), not through the plugin's own types.
 */
public abstract class HiveBukkitPlugin {

    private String name;
    private String version;
    private Plugin owner;
    private HiveServer hiveServer;
    private ComponentLogger logger;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<Commands>> commands = new CopyOnWriteArrayList<>();

    /** Set up by the bridge before {@link #onEnable()}. */
    final void initialize(Plugin owner, String name, String version, HiveServer hiveServer) {
        this.owner = owner;
        this.name = name;
        this.version = version;
        this.hiveServer = hiveServer;
        this.logger = ComponentLogger.logger(name);
    }

    /** When the server enables its plugins (after its worlds load). Register commands here. */
    public void onEnable() {}

    /** When the server stops. Listeners and commands go by themselves. */
    public void onDisable() {}

    public final @NotNull String getName() {
        return name;
    }

    public final @NotNull String getVersion() {
        return version;
    }

    public final @NotNull Server getServer() {
        return owner.getServer();
    }

    /** For Bukkit APIs that want a {@link Plugin}: Paperhive's bridge, shared by the server halves in this server. */
    public final @NotNull Plugin getBukkitPlugin() {
        return owner;
    }

    public final @NotNull Hive getHive() {
        return Paperhive.getHive();
    }

    /** The server this runs in. */
    public final @NotNull HiveServer getHiveServer() {
        return hiveServer;
    }

    /** Prints as {@code [<name>] ...}, with Adventure components too. */
    public final @NotNull ComponentLogger getLogger() {
        return logger;
    }

    /** {@code plugins/<name>/} of this server, like a Bukkit plugin's (not created until you do). */
    public final @NotNull Path getDataFolder() {
        return getServer().getPluginsFolder().toPath().resolve(name);
    }

    public final void registerListener(@NotNull Listener listener) {
        getServer().getPluginManager().registerEvents(listener, owner);
        listeners.add(listener);
    }

    public final void unregisterListener(@NotNull Listener listener) {
        HandlerList.unregisterAll(listener);
        listeners.remove(listener);
    }

    /** A simple command ({@code /label args...}); call from {@link #onEnable()}. Also reachable as {@code /paperhive:label}. */
    public final void registerCommand(@NotNull String label, @NotNull BasicCommand command) {
        registerCommand(label, null, List.of(), command);
    }

    public final void registerCommand(@NotNull String label, @Nullable String description, @NotNull Collection<String> aliases, @NotNull BasicCommand command) {
        commands.add(c -> c.register(label, description, aliases, command));
    }

    /** A Brigadier command tree (built with {@link Commands#literal}); call from {@link #onEnable()}. */
    public final void registerCommand(@NotNull LiteralCommandNode<CommandSourceStack> node, @Nullable String description, @NotNull Collection<String> aliases) {
        commands.add(c -> c.register(node, description, aliases));
    }

    public final @NotNull BukkitTask runTask(@NotNull Runnable task) {
        return getServer().getScheduler().runTask(owner, task);
    }

    public final @NotNull BukkitTask runTaskLater(@NotNull Runnable task, long delayTicks) {
        return getServer().getScheduler().runTaskLater(owner, task, delayTicks);
    }

    public final @NotNull BukkitTask runTaskTimer(@NotNull Runnable task, long delayTicks, long periodTicks) {
        return getServer().getScheduler().runTaskTimer(owner, task, delayTicks, periodTicks);
    }

    public final @NotNull BukkitTask runTaskAsync(@NotNull Runnable task) {
        return getServer().getScheduler().runTaskAsynchronously(owner, task);
    }

    /** Into the server's command tree (Paper asks on startup and on every reload). */
    final void registerCommands(Commands registrar) {
        for (Consumer<Commands> command : commands) command.accept(registrar);
    }

    /** Takes back what it registered (after a failed enable, or on disable). */
    final void release() {
        listeners.forEach(HandlerList::unregisterAll);
        listeners.clear();
        commands.clear();
    }

}
