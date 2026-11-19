package org.abdullahcxd.paperhive.bridge.event;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jetbrains.annotations.NotNull;

/**
 * A player moved into another block (or world). Bukkit's {@code PlayerMoveEvent} fires for every head turn and
 * sub-block step, about 20 times a second per player; this one fires only when the block position changes, which is
 * what region, portal and trigger code wants. Cancelling it cancels the move.
 * <pre>{@code
 * on(PlayerMoveBlockEvent.class, e -> {
 *     if (e.getTo().getBlock().getType() == Material.GOLD_BLOCK) e.getPlayer().sendMessage("Bonus!");
 * });
 * }</pre>
 */
public final class PlayerMoveBlockEvent extends PlayerEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Location from, to;
    private boolean cancelled;

    public PlayerMoveBlockEvent(@NotNull Player player, @NotNull Location from, @NotNull Location to) {
        super(player);
        this.from = from;
        this.to = to;
    }

    public @NotNull Location getFrom() {
        return from.clone();
    }

    public @NotNull Location getTo() {
        return to.clone();
    }

    /** Whether the move also crossed into another chunk (or world). */
    public boolean isNewChunk() {
        return from.getWorld() != to.getWorld() || from.getBlockX() >> 4 != to.getBlockX() >> 4 || from.getBlockZ() >> 4 != to.getBlockZ() >> 4;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        cancelled = cancel;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }

}
