package org.abdullahcxd.paperhive.bridge;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.abdullahcxd.paperhive.api.HiveServer;
import org.abdullahcxd.paperhive.api.HiveServerStats;
import org.abdullahcxd.paperhive.api.Paperhive;
import org.abdullahcxd.paperhive.api.internal.BridgeHooks;
import org.abdullahcxd.paperhive.api.internal.BridgeHooks.ServerHalf;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;

/**
 * Paperhive's plugin inside each server (added with {@code --add-plugin}, not from {@code plugins/}): lets the host
 * run tasks on this server's main thread, and loads the Hive plugins' server halves ({@link HiveBukkitPlugin}) itself,
 * each with its own {@link ServerHalfLoader}. One that fails is logged and taken back; the others carry on.
 */
public final class PaperhiveBridge extends JavaPlugin {

    private record Running(HiveBukkitPlugin plugin, ServerHalfLoader loader) {}

    private final List<Running> halves = new ArrayList<>();

    @Override
    public void onEnable() {
        BridgeHooks hooks = (BridgeHooks) Paperhive.getHive();
        // the host tells servers apart by thread; this runs on this server's main thread
        hooks.bridgeEnabled(task -> getServer().getScheduler().runTask(this, task), this::stats);
        HiveServer server = Objects.requireNonNull(Paperhive.getHive().getCurrentServer());
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS,
                event -> halves.forEach(h -> h.plugin().registerCommands(event.registrar())));

        long start = System.nanoTime();
        List<ServerHalf> wanted = hooks.serverHalves();
        for (ServerHalf half : wanted) enable(half, server);
        if (!wanted.isEmpty()) {
            getLogger().info(String.format("Enabled %d of %d Hive server half(s) in %.1f ms", halves.size(), wanted.size(), (System.nanoTime() - start) / 1e6));
        }
    }

    /** Paper keeps TPS and tick times in volatile/rolling fields and the player list concurrent, so any thread may read. */
    private HiveServerStats stats() {
        double[] tps = getServer().getTPS();
        return new HiveServerStats(tps[0], tps[1], tps[2], getServer().getAverageTickTime(), getServer().getOnlinePlayers().size(), getServer().getMaxPlayers());
    }

    private void enable(ServerHalf half, HiveServer server) {
        ServerHalfLoader loader = null;
        HiveBukkitPlugin plugin = null;
        try {
            loader = new ServerHalfLoader(half.name(), half.jar(), getClassLoader());
            Class<?> main = Class.forName(half.main(), true, loader);
            if (!HiveBukkitPlugin.class.isAssignableFrom(main)) throw new IllegalArgumentException(half.main() + " doesn't extend HiveBukkitPlugin");
            plugin = (HiveBukkitPlugin) main.getDeclaredConstructor().newInstance();
            plugin.initialize(this, half.name(), half.version(), server);
            plugin.getLogger().info("Enabling {} v{}", half.name(), half.version());
            plugin.onEnable();
            halves.add(new Running(plugin, loader));
        } catch (Throwable e) {
            getLogger().log(Level.SEVERE, "Cannot enable the server half of Hive plugin " + half.name(), e);
            if (plugin != null) plugin.release();
            close(loader);
        }
    }

    @Override
    public void onDisable() {
        for (Running h : halves.reversed()) {
            try {
                h.plugin().getLogger().info("Disabling {}", h.plugin().getName());
                h.plugin().onDisable();
            } catch (Throwable e) {
                getLogger().log(Level.SEVERE, "The server half of Hive plugin " + h.plugin().getName() + " failed to disable", e);
            }
            h.plugin().release();
            close(h.loader());
        }
        halves.clear();
        ((BridgeHooks) Paperhive.getHive()).bridgeDisabled();
    }

    private void close(ServerHalfLoader loader) {
        if (loader == null) return;
        try {
            loader.close(); // frees the jar (Windows locks it while open)
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "Cannot close " + loader.getName(), e);
        }
    }

}
