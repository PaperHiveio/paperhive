package org.abdullahcxd.paperhive.api.data;

import org.abdullahcxd.paperhive.api.Namespaced;
import org.abdullahcxd.paperhive.api.Registration;
import org.abdullahcxd.paperhive.api.message.MessageCodec;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * Data shared by the whole hive: a key-value store held by the host, which the host and every server read and write
 * the same. Values are text (use a {@link MessageCodec} for other types) under {@link Namespaced} keys. Get it with
 * {@code Hive.getData()}.
 * <pre>{@code
 * HiveData data = hive.getData();
 * data.set(key("motd"), "Summer event!");
 * long joins = data.increment(key("joins-today"), 1);
 * data.watch(key("motd"), (key, old, now) -> refreshScoreboards(now));
 * Integer limit = data.get(key("party-limit"), MessageCodec.INTEGER);
 * }</pre>
 * It's in memory: it lasts while the host runs, across server restarts, and starts empty with the host. Every method
 * is thread-safe and atomic for its key.
 */
public interface HiveData {

    /** The value, or null if none is set. */
    @Nullable String get(@NotNull Namespaced key);

    /** The value decoded, or null if none is set. */
    default <T> @Nullable T get(@NotNull Namespaced key, @NotNull MessageCodec<T> codec) {
        String value = get(key);
        return value == null ? null : codec.decode(value);
    }

    /** Sets the value (watchers hear about it if it changed). */
    void set(@NotNull Namespaced key, @NotNull String value);

    default <T> void set(@NotNull Namespaced key, @NotNull MessageCodec<T> codec, @NotNull T value) {
        set(key, codec.encode(value));
    }

    /** @return whether there was a value */
    boolean remove(@NotNull Namespaced key);

    /**
     * Sets {@code update} only if the value is {@code expected} now (null for "not set"); a null {@code update}
     * removes it. For read-modify-write without races, e.g. claiming a name: {@code compareAndSet(key, null, me)}.
     *
     * @return whether it was set
     */
    boolean compareAndSet(@NotNull Namespaced key, @Nullable String expected, @Nullable String update);

    /**
     * Adds {@code delta} to a number (0 if not set) and returns the new value.
     *
     * @throws NumberFormatException if the value isn't a whole number
     */
    long increment(@NotNull Namespaced key, long delta);

    /** Every key in {@code namespace} with its value, as it is now. */
    @NotNull Map<Namespaced, String> entries(@NotNull String namespace);

    /**
     * Hears every change to {@code key}, on a Paperhive thread, in order. Dropped automatically when the server (or
     * the Hive plugin) that made it stops.
     */
    @NotNull Registration watch(@NotNull Namespaced key, @NotNull DataListener listener);

    /** A change to a watched key. */
    @FunctionalInterface
    interface DataListener {
        /** @param before the old value, or null if it wasn't set; @param after the new one, or null if it was removed */
        void changed(@NotNull Namespaced key, @Nullable String before, @Nullable String after);
    }

}
