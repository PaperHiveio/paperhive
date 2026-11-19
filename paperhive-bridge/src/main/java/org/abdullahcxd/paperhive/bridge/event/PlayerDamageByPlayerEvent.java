package org.abdullahcxd.paperhive.bridge.event;

import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A player hurt another player, hand to hand or with a projectile they shot (arrow, trident, snowball...). In Bukkit
 * that's an {@code EntityDamageByEntityEvent} whose damager may be the player or their projectile; this event does
 * that unwrapping. {@link #getPlayer()} is the victim. Damage and cancelling go through to the Bukkit event.
 * <pre>{@code
 * on(PlayerDamageByPlayerEvent.class, e -> {
 *     if (sameTeam(e.getAttacker(), e.getPlayer())) e.setCancelled(true);
 * });
 * }</pre>
 */
public final class PlayerDamageByPlayerEvent extends PlayerEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player attacker;
    private final EntityDamageByEntityEvent cause;

    public PlayerDamageByPlayerEvent(@NotNull Player victim, @NotNull Player attacker, @NotNull EntityDamageByEntityEvent cause) {
        super(victim);
        this.attacker = attacker;
        this.cause = cause;
    }

    /** The player who dealt the damage (the shooter, for a projectile). */
    public @NotNull Player getAttacker() {
        return attacker;
    }

    /** The projectile that hit, or null for a melee hit. */
    public @Nullable Projectile getProjectile() {
        return cause.getDamager() instanceof Projectile p ? p : null;
    }

    public double getDamage() {
        return cause.getDamage();
    }

    public void setDamage(double damage) {
        cause.setDamage(damage);
    }

    /** Whether the damage would kill the victim (before armour and effects are applied, as Bukkit reports it). */
    public boolean isLethal() {
        return getPlayer().getHealth() - cause.getFinalDamage() <= 0;
    }

    /** The Bukkit event underneath, for anything not covered here. */
    public @NotNull EntityDamageByEntityEvent getBukkitEvent() {
        return cause;
    }

    @Override
    public boolean isCancelled() {
        return cause.isCancelled();
    }

    @Override
    public void setCancelled(boolean cancel) {
        cause.setCancelled(cancel);
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }

}
