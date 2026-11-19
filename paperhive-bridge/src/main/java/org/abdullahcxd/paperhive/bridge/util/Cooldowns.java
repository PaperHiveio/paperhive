package org.abdullahcxd.paperhive.bridge.util;

import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Per-key cooldowns (a player's UUID, a name, anything with {@code equals}), safe from any thread.
 * <pre>{@code
 * private final Cooldowns<UUID> heal = new Cooldowns<>(Duration.ofSeconds(30));
 *
 * if (!heal.tryUse(player.getUniqueId())) {
 *     player.sendMessage(Text.mini("<red>Wait <time>s", Placeholder.unparsed("time", String.valueOf(heal.remaining(player.getUniqueId()).toSeconds() + 1))));
 *     return;
 * }
 * }</pre>
 */
public final class Cooldowns<K> {

    private final long lengthNanos;
    private final LongSupplier clock;
    private final Map<K, Long> readyAt = new ConcurrentHashMap<>();

    public Cooldowns(@NotNull Duration length) {
        this(length, System::nanoTime);
    }

    Cooldowns(@NotNull Duration length, @NotNull LongSupplier clock) {
        if (length.isNegative()) throw new IllegalArgumentException("A cooldown can't be negative: " + length);
        this.lengthNanos = length.toNanos();
        this.clock = clock;
    }

    /** Starts the cooldown and returns true if {@code key} isn't cooling down; otherwise false and nothing changes. */
    public boolean tryUse(@NotNull K key) {
        long now = clock.getAsLong();
        boolean[] used = {false};
        readyAt.compute(key, (k, ready) -> {
            if (ready != null && ready - now > 0) return ready;
            used[0] = true;
            return now + lengthNanos;
        });
        return used[0];
    }

    /** How long until {@code key} can be used again ({@link Duration#ZERO} if now). */
    public @NotNull Duration remaining(@NotNull K key) {
        Long ready = readyAt.get(key);
        long left = ready == null ? 0 : ready - clock.getAsLong();
        if (left <= 0) {
            if (ready != null) readyAt.remove(key, ready); // expired: forget it, so the map doesn't keep every key forever
            return Duration.ZERO;
        }
        return Duration.ofNanos(left);
    }

    /** Ends {@code key}'s cooldown now. */
    public void reset(@NotNull K key) {
        readyAt.remove(key);
    }

}
