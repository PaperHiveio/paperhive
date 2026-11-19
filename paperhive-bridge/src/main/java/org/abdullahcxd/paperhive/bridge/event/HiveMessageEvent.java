package org.abdullahcxd.paperhive.bridge.event;

import org.abdullahcxd.paperhive.api.HiveServer;
import org.abdullahcxd.paperhive.api.Namespaced;
import org.abdullahcxd.paperhive.api.message.MessageCodec;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A Bukkit event for a hive message, fired on the main thread for channels a server half relays
 * ({@code HiveBukkitPlugin.relay(channel)}). Lets any {@code @EventHandler} react to messages from other servers:
 * <pre>{@code
 * // in the server half's onEnable
 * relay(key("announce"));
 *
 * @EventHandler
 * public void onHiveMessage(HiveMessageEvent event) {
 *     if (event.is(key("announce"))) getServer().broadcast(Component.text(event.getMessage()));
 * }
 * }</pre>
 */
public final class HiveMessageEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final @Nullable HiveServer from;
    private final Namespaced channel;
    private final String message;

    public HiveMessageEvent(@Nullable HiveServer from, @NotNull Namespaced channel, @NotNull String message) {
        this.from = from;
        this.channel = channel;
        this.message = message;
    }

    /** The server that sent it, or null for the host. */
    public @Nullable HiveServer getFrom() {
        return from;
    }

    public @NotNull Namespaced getChannel() {
        return channel;
    }

    public boolean is(@NotNull Namespaced channel) {
        return this.channel.equals(channel);
    }

    /** The message as sent (text). */
    public @NotNull String getMessage() {
        return message;
    }

    /** The message decoded, e.g. {@code event.decode(MessageCodec.UUID_CODEC)}. */
    public <T> @NotNull T decode(@NotNull MessageCodec<T> codec) {
        return codec.decode(message);
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }

}
