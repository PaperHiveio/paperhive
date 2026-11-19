package org.abdullahcxd.paperhive.bridge;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.abdullahcxd.paperhive.api.HiveServer;
import org.abdullahcxd.paperhive.api.HiveServerState;
import org.abdullahcxd.paperhive.api.HiveServerStats;
import org.abdullahcxd.paperhive.api.Paperhive;
import org.abdullahcxd.paperhive.api.event.Priority;
import org.abdullahcxd.paperhive.api.event.Subscribe;
import org.abdullahcxd.paperhive.api.event.server.ServerStateChangeEvent;
import org.abdullahcxd.paperhive.api.internal.BridgeHooks;
import org.abdullahcxd.paperhive.api.internal.BridgeHooks.ServerHalf;
import org.abdullahcxd.paperhive.bridge.event.HiveServerStateEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Paperhive's plugin inside each server (added with {@code --add-plugin}, not from {@code plugins/}): lets the host
 * run tasks on this server's main thread, loads the Hive plugins' server halves ({@link HiveBukkitPlugin}) itself,
 * each with its own {@link ServerHalfLoader} (one that fails is logged and taken back; the others carry on), and
 * fires {@link HiveServerStateEvent} in this server when any hive server changes state. It reports what passes
 * between it and the host in the console, coloured.
 */
public final class PaperhiveBridge extends JavaPlugin {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    // ASCII only: not every console or log keeps other characters intact (Windows code pages)
    private static final String PREFIX = "<gradient:#FFC94A:#F29E1F>Hive</gradient> <dark_gray>|</dark_gray> ";

    private record Running(HiveBukkitPlugin plugin, ServerHalfLoader loader) {}

    private final List<Running> halves = new ArrayList<>();

    @Override
    public void onEnable() {
        BridgeHooks hooks = (BridgeHooks) Paperhive.getHive();
        // the host tells servers apart by thread; this runs on this server's main thread
        hooks.bridgeEnabled(task -> getServer().getScheduler().runTask(this, task), this::stats);
        HiveServer server = Objects.requireNonNull(Paperhive.getHive().getCurrentServer());
        say("<gray>Connected to the host as</gray> <yellow><server></yellow> <dark_gray>(port <port>, <count> server(s) in the hive)</dark_gray>",
                Placeholder.unparsed("server", server.getName()), Placeholder.unparsed("port", String.valueOf(server.getPort())),
                Placeholder.unparsed("count", String.valueOf(Paperhive.getHive().getServers().size())));

        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            halves.forEach(h -> h.plugin().registerCommands(event.registrar()));
            if (!halves.isEmpty()) say("<gray>Registered the commands of</gray> <white><count></white> <gray>server half(s)</gray>",
                    Placeholder.unparsed("count", String.valueOf(halves.size())));
        });
        // owned by this server (this class is), so the host drops it when the server stops
        Paperhive.getHive().getEventBus().register(new StateRelay(server));

        long start = System.nanoTime();
        List<ServerHalf> wanted = hooks.serverHalves();
        if (wanted.isEmpty()) {
            say("<gray>No Hive plugin has a server half for this server</gray>");
            return;
        }
        say("<gray>The host sent</gray> <white><count></white> <gray>server half(s) to load</gray>", Placeholder.unparsed("count", String.valueOf(wanted.size())));
        for (ServerHalf half : wanted) enable(half, server);
        say("<green>Enabled <done> of <wanted></green> <gray>server half(s) in</gray> <white><ms> ms</white>",
                Placeholder.unparsed("done", String.valueOf(halves.size())), Placeholder.unparsed("wanted", String.valueOf(wanted.size())),
                Placeholder.unparsed("ms", String.format("%.1f", (System.nanoTime() - start) / 1e6)));
    }

    /** Paper keeps TPS and tick times in volatile/rolling fields and the player list concurrent, so any thread may read. */
    private HiveServerStats stats() {
        double[] tps = getServer().getTPS();
        return new HiveServerStats(tps[0], tps[1], tps[2], getServer().getAverageTickTime(), getServer().getOnlinePlayers().size(), getServer().getMaxPlayers());
    }

    private void enable(ServerHalf half, HiveServer server) {
        TagResolver names = TagResolver.resolver(Placeholder.unparsed("name", half.name()), Placeholder.unparsed("version", half.version()),
                Placeholder.unparsed("jar", String.valueOf(half.jar().getFileName())));
        say("<gray>> Loading</gray> <aqua><name></aqua> <dark_gray>v<version> from <jar></dark_gray>", names);
        long start = System.nanoTime();
        ServerHalfLoader loader = null;
        HiveBukkitPlugin plugin = null;
        try {
            loader = new ServerHalfLoader(half.name(), half.jar(), getClassLoader());
            Class<?> main = Class.forName(half.main(), true, loader);
            if (!HiveBukkitPlugin.class.isAssignableFrom(main)) throw new IllegalArgumentException(half.main() + " doesn't extend HiveBukkitPlugin");
            plugin = (HiveBukkitPlugin) main.getDeclaredConstructor().newInstance();
            plugin.initialize(this, half.name(), half.version(), server);
            plugin.onEnable();
            halves.add(new Running(plugin, loader));
            say("<green>+</green> <aqua><name></aqua> <gray>enabled</gray> <dark_gray>(<ms> ms)</dark_gray>",
                    names, Placeholder.unparsed("ms", String.format("%.1f", (System.nanoTime() - start) / 1e6)));
        } catch (Throwable e) {
            getComponentLogger().error(MM.deserialize(PREFIX + "<red>x Cannot enable the server half of</red> <aqua><name></aqua><red>; the others carry on</red>", names), e);
            if (plugin != null) plugin.release();
            close(loader);
        }
    }

    @Override
    public void onDisable() {
        if (!halves.isEmpty()) say("<gray>Disabling</gray> <white><count></white> <gray>server half(s)</gray>", Placeholder.unparsed("count", String.valueOf(halves.size())));
        for (Running h : halves.reversed()) {
            try {
                h.plugin().onDisable();
            } catch (Throwable e) {
                getComponentLogger().error(MM.deserialize(PREFIX + "<red>x The server half of</red> <aqua><name></aqua> <red>failed to disable</red>",
                        Placeholder.unparsed("name", h.plugin().getName())), e);
            }
            h.plugin().release();
            close(h.loader());
        }
        halves.clear();
        ((BridgeHooks) Paperhive.getHive()).bridgeDisabled();
        say("<gray>Disconnected from the host</gray>");
    }

    private void say(String miniMessage, TagResolver... resolvers) {
        getComponentLogger().info(MM.deserialize(PREFIX + miniMessage, resolvers));
    }

    private void close(ServerHalfLoader loader) {
        if (loader == null) return;
        try {
            loader.close(); // frees the jar (Windows locks it while open)
        } catch (IOException e) {
            getComponentLogger().warn("Cannot close {}", loader.getName(), e);
        }
    }

    /** Hive state changes into this server: a Bukkit {@link HiveServerStateEvent}, and a console line about the others. */
    private final class StateRelay {
        private final HiveServer self;

        private StateRelay(HiveServer self) {
            this.self = self;
        }

        @Subscribe(priority = Priority.MONITOR)
        private void onStateChange(ServerStateChangeEvent event) {
            HiveServer server = event.getServer();
            HiveServerState to = event.getTo();
            if (!isEnabled()) return; // this server is stopping
            getServer().getScheduler().runTask(PaperhiveBridge.this, () -> {
                if (!server.equals(self) && (to == HiveServerState.RUNNING || to == HiveServerState.STOPPED || to == HiveServerState.CRASHED)) {
                    String colour = switch (to) {
                        case RUNNING -> "green";
                        case CRASHED -> "red";
                        default -> "gray";
                    };
                    say("<yellow><server></yellow> <gray>is now</gray> <" + colour + "><state></" + colour + ">",
                            Placeholder.unparsed("server", server.getName()), Placeholder.unparsed("state", to.name()));
                }
                getServer().getPluginManager().callEvent(new HiveServerStateEvent(server, event.getFrom(), to));
            });
        }
    }

}
