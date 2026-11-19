package org.abdullahcxd.paperhive.api.message;

import org.abdullahcxd.paperhive.api.Hive;
import org.abdullahcxd.paperhive.api.HiveServer;
import org.abdullahcxd.paperhive.api.Namespaced;
import org.abdullahcxd.paperhive.api.Registration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * A typed message channel: values go out encoded by its {@link MessageCodec} and arrive decoded. Get one with
 * {@link Hive#channel(Namespaced, MessageCodec)}; it's a small value object, so create it once and keep it.
 * <pre>{@code
 * HiveChannel<UUID> joins = hive.channel(key("joins"), MessageCodec.UUID_CODEC);
 *
 * joins.subscribe(message -> log("{} joined {}", message.payload(), message.from()));
 * joins.publish(player.getUniqueId());               // every subscriber, on the host and in every server
 * joins.send(hive.getServer("lobby"), uuid);         // only subscribers in the lobby
 * joins.send(null, uuid);                            // only the host's (Hive plugins' host halves)
 * }</pre>
 * Listeners run on a Paperhive thread, in the order messages were sent; in a server half, hop onto the main thread
 * before using Bukkit ({@code HiveBukkitPlugin.onMessage} does that for you).
 *
 * @param <T> what the channel carries
 */
public final class HiveChannel<T> {

    private final Hive hive;
    private final Namespaced key;
    private final MessageCodec<T> codec;

    public HiveChannel(@NotNull Hive hive, @NotNull Namespaced key, @NotNull MessageCodec<T> codec) {
        this.hive = Objects.requireNonNull(hive);
        this.key = Objects.requireNonNull(key);
        this.codec = Objects.requireNonNull(codec);
    }

    public @NotNull Namespaced key() {
        return key;
    }

    public @NotNull MessageCodec<T> codec() {
        return codec;
    }

    /** To every subscriber: the host's and every server's. */
    public void publish(@NotNull T value) {
        hive.publish(key, codec.encode(value));
    }

    /** To the subscribers in {@code target} only, or the host's when it's null. */
    public void send(@Nullable HiveServer target, @NotNull T value) {
        hive.send(target, key, codec.encode(value));
    }

    /** Every message on this channel, decoded; one that doesn't decode is logged and skipped. */
    public @NotNull Registration subscribe(@NotNull Consumer<HiveMessage<T>> listener) {
        return hive.subscribe(key, (from, channel, message) -> listener.accept(new HiveMessage<>(from, key, codec.decode(message))));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof HiveChannel<?> c && c.key.equals(key);
    }

    @Override
    public int hashCode() {
        return key.hashCode();
    }

    @Override
    public String toString() {
        return "HiveChannel[" + key + "]";
    }

}
