package org.abdullahcxd.paperhive.bridge.event;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jetbrains.annotations.NotNull;

/**
 * A player went idle (nothing for {@link #IDLE_AFTER_SECONDS}: no moving, looking around, chatting, commands or
 * clicks), or came back ({@link #isIdle()} false). Fired on the main thread, within a second of the change.
 * <pre>{@code
 * on(PlayerIdleEvent.class, e -> e.getPlayer().playerListName(
 *         e.isIdle() ? Component.text(e.getPlayer().getName() + " (AFK)", NamedTextColor.GRAY) : null));
 * }</pre>
 */
public final class PlayerIdleEvent extends PlayerEvent {

    /** How long without activity counts as idle. */
    public static final int IDLE_AFTER_SECONDS = 300;

    private static final HandlerList HANDLERS = new HandlerList();

    private final boolean idle;

    public PlayerIdleEvent(@NotNull Player player, boolean idle) {
        super(player);
        this.idle = idle;
    }

    /** True: the player just went idle. False: they were idle and did something. */
    public boolean isIdle() {
        return idle;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }

}
