package org.abdullahcxd.paperhive.bridge.event;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A player joined this server, knowing where they came from: Bukkit's {@code PlayerJoinEvent} can't tell a new
 * player from one the proxy just moved over from another server of the hive. Fired at the end of
 * {@code PlayerJoinEvent} (after its other listeners).
 * <pre>{@code
 * on(PlayerJoinHiveEvent.class, e -> {
 *     if (e.isNewToHive()) getServer().broadcast(Component.text(e.getPlayer().getName() + " joined the network"));
 *     else e.getPlayer().sendMessage("Welcome over from " + e.getPreviousServer());
 * });
 * }</pre>
 */
public final class PlayerJoinHiveEvent extends PlayerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String previousServer;

    public PlayerJoinHiveEvent(@NotNull Player player, @Nullable String previousServer) {
        super(player);
        this.previousServer = previousServer;
    }

    /** The hive server the player just left for this one, or null if they weren't on the hive a moment ago. */
    public @Nullable String getPreviousServer() {
        return previousServer;
    }

    /** Whether they came from outside the hive rather than from another of its servers. */
    public boolean isNewToHive() {
        return previousServer == null;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }

}
