package org.abdullahcxd.paperhive.bridge.event;

import org.bukkit.OfflinePlayer;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * A player left the whole hive, not just this server: fired in the server they left, a few seconds after their
 * {@code PlayerQuitEvent}, unless they joined another server of the hive meanwhile (the proxy moving them). They're
 * offline by then, hence an {@link OfflinePlayer}. Not fired for players kicked because this server is stopping.
 * <pre>{@code
 * on(PlayerLeaveHiveEvent.class, e -> getHive().getData().remove(key("party/" + e.getPlayer().getUniqueId())));
 * }</pre>
 */
public final class PlayerLeaveHiveEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final OfflinePlayer player;

    public PlayerLeaveHiveEvent(@NotNull OfflinePlayer player) {
        this.player = player;
    }

    public @NotNull OfflinePlayer getPlayer() {
        return player;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }

}
