package org.abdullahcxd.paperhive.bridge;

import io.papermc.paper.event.player.AsyncChatEvent;
import org.abdullahcxd.paperhive.api.HiveServerState;
import org.abdullahcxd.paperhive.api.Paperhive;
import org.abdullahcxd.paperhive.bridge.event.PlayerDamageByPlayerEvent;
import org.abdullahcxd.paperhive.bridge.event.PlayerIdleEvent;
import org.abdullahcxd.paperhive.bridge.event.PlayerJoinHiveEvent;
import org.abdullahcxd.paperhive.bridge.event.PlayerLeaveHiveEvent;
import org.abdullahcxd.paperhive.bridge.event.PlayerMoveBlockEvent;
import org.abdullahcxd.paperhive.api.HiveServer;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** Fires the bridge's own Bukkit events ({@code bridge.event}) from the ones Bukkit has. */
final class BridgeEvents implements Listener {

    private static final long IDLE_NANOS = TimeUnit.SECONDS.toNanos(PlayerIdleEvent.IDLE_AFTER_SECONDS);

    private final Plugin plugin;
    private final PlayerPresence presence;
    // written from the chat thread too
    private final Map<UUID, Long> lastActive = new ConcurrentHashMap<>();
    private final Set<UUID> idle = new HashSet<>();

    BridgeEvents(@NotNull Plugin plugin, @NotNull HiveServer self) {
        this.plugin = plugin;
        this.presence = new PlayerPresence(Paperhive.getHive().getData(), self.getName(), name -> {
            HiveServer server = Paperhive.getHive().getServer(name);
            return server != null && server.getState() == HiveServerState.RUNNING;
        }, System::currentTimeMillis);
    }

    void start() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::checkIdle, 20, 20);
    }

    /** The server is stopping: its players are about to be kicked with no quit events left to see, so mark them gone. */
    void stop() {
        plugin.getServer().getOnlinePlayers().forEach(p -> presence.quit(p.getUniqueId()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    void onJoin(@NonNull PlayerJoinEvent event) {
        Player player = event.getPlayer();
        active(player.getUniqueId());
        plugin.getServer().getPluginManager().callEvent(new PlayerJoinHiveEvent(player, presence.joined(player.getUniqueId())));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    void onQuit(@NonNull PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        lastActive.remove(id);
        idle.remove(id);
        String mark = presence.quit(id);
        if (mark == null) return;
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (presence.leftHive(id, mark)) {
                plugin.getServer().getPluginManager().callEvent(new PlayerLeaveHiveEvent(plugin.getServer().getOfflinePlayer(id)));
            }
        }, PlayerPresence.SWITCH_WINDOW_MILLIS / 50);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    void onMove(@NonNull PlayerMoveEvent event) {
        active(event.getPlayer().getUniqueId());
        if (!event.hasChangedBlock() || !listened(PlayerMoveBlockEvent.getHandlerList())) return;
        PlayerMoveBlockEvent moved = new PlayerMoveBlockEvent(event.getPlayer(), event.getFrom(), event.getTo());
        plugin.getServer().getPluginManager().callEvent(moved);
        if (moved.isCancelled()) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    void onDamage(@NonNull EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim) || !listened(PlayerDamageByPlayerEvent.getHandlerList())) return;
        Player attacker = event.getDamager() instanceof Player p ? p
                : event.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Player p ? p : null;
        if (attacker == null || attacker.equals(victim)) return;
        plugin.getServer().getPluginManager().callEvent(new PlayerDamageByPlayerEvent(victim, attacker, event));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    void onChat(@NonNull AsyncChatEvent event) {
        active(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    void onCommand(@NonNull PlayerCommandPreprocessEvent event) {
        active(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    void onInteract(@NonNull PlayerInteractEvent event) {
        active(event.getPlayer().getUniqueId());
    }

    private void active(UUID player) {
        lastActive.put(player, System.nanoTime());
    }

    private void checkIdle() {
        long now = System.nanoTime();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            Long last = lastActive.get(player.getUniqueId());
            if (last == null) continue;
            boolean isIdle = now - last >= IDLE_NANOS;
            if (isIdle == idle.contains(player.getUniqueId())) continue;
            if (isIdle) idle.add(player.getUniqueId());
            else idle.remove(player.getUniqueId());
            plugin.getServer().getPluginManager().callEvent(new PlayerIdleEvent(player, isIdle));
        }
    }

    /** Building and firing an event nobody listens to is wasted work on a hot path (moves, hits). */
    private static boolean listened(@NonNull HandlerList handlers) {
        return handlers.getRegisteredListeners().length > 0;
    }

}
