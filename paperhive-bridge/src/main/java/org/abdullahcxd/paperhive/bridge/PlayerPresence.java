package org.abdullahcxd.paperhive.bridge;

import org.abdullahcxd.paperhive.api.Namespaced;
import org.abdullahcxd.paperhive.api.data.HiveData;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NonNull;

import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * Where each player is in the hive, in the hive's shared data ({@code paperhive:player/<uuid>} = {@code
 * <server>\tonline} or {@code <server>\tleft\t<millis>}), so servers can tell a player arriving from another server
 * from one new to the hive. A proxy may connect a player to the new server before the old one sees them quit, which
 * is why both orders are handled: whoever writes second sees the other's mark.
 */
final class PlayerPresence {

    /** A player who left a server this recently and joins another was moved there, not a new arrival. */
    static final long SWITCH_WINDOW_MILLIS = 10_000;

    private final HiveData data;
    private final String self;
    private final Predicate<String> running;
    private final LongSupplier clock;

    /** @param running whether a hive server is running now (an "online" mark from a crashed one doesn't count) */
    PlayerPresence(@NotNull HiveData data, @NotNull String self, @NotNull Predicate<String> running, @NotNull LongSupplier clock) {
        this.data = data;
        this.self = self;
        this.running = running;
        this.clock = clock;
    }

    /** The player joined this server; returns the hive server they came from, or null if they're new to the hive. */
    @Nullable String joined(@NotNull UUID player) {
        Namespaced key = key(player);
        String before = data.get(key);
        data.set(key, self + "\tonline");
        if (before == null) return null;
        String[] mark = before.split("\t");
        if (mark[0].equals(self)) return null;
        if (mark[1].equals("online")) return running.test(mark[0]) ? mark[0] : null;
        return clock.getAsLong() - Long.parseLong(mark[2]) <= SWITCH_WINDOW_MILLIS ? mark[0] : null;
    }

    /**
     * The player quit this server. Returns the mark to hand to {@link #leftHive} after {@link #SWITCH_WINDOW_MILLIS},
     * or null if another server already has them (moved by the proxy).
     */
    @Nullable String quit(@NotNull UUID player) {
        String left = self + "\tleft\t" + clock.getAsLong();
        return data.compareAndSet(key(player), self + "\tonline", left) ? left : null;
    }

    /** Whether the player is still gone from the whole hive since {@code mark}; clears their entry if so. */
    boolean leftHive(@NotNull UUID player, @NotNull String mark) {
        return data.compareAndSet(key(player), mark, null);
    }

    @Contract("_ -> new")
    private static @NonNull Namespaced key(UUID player) {
        return Namespaced.paperhive("player/" + player);
    }

}
