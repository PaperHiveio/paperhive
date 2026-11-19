package org.abdullahcxd.paperhive.api;

import org.abdullahcxd.paperhive.api.data.HiveData;
import org.abdullahcxd.paperhive.api.event.EventBus;
import org.abdullahcxd.paperhive.api.message.HiveChannel;
import org.abdullahcxd.paperhive.api.message.HiveResponder;
import org.abdullahcxd.paperhive.api.message.HiveService;
import org.abdullahcxd.paperhive.api.message.MessageCodec;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Every server of this Paperhive host. Usable from the host (Hive plugins) and from inside servers (Paper plugins,
 * Hive plugins' server halves); thread-safe. Get it with {@link Paperhive#getHive()}.
 * <p>
 * Listeners and subscriptions made by code inside a server are removed when that server stops (and a Hive plugin's
 * when it's disabled), so they can't keep its classes alive; add them again when it starts.
 */
public interface Hive {

    @NotNull List<HiveServer> getServers();

    @Nullable HiveServer getServer(@NotNull String name);

    /** The server the calling thread belongs to; null on the host's own threads. */
    @Nullable HiveServer getCurrentServer();

    /**
     * Adds a server to {@code paperhive.yml}, not started.
     *
     * @throws IllegalArgumentException with every reason, if it would make the file invalid (name taken, port used, ...)
     */
    @NotNull HiveServer createServer(@NotNull String name, int port);

    /** Hive events: server state changes, starts (cancellable), crashes, and plugins' own events. */
    @NotNull EventBus getEventBus();

    /** Delivers {@code message} to every subscriber of {@code channel}, on the host and in every server, in order, on a Paperhive thread. */
    void publish(@NotNull String channel, @NotNull String message);

    @NotNull Registration subscribe(@NotNull String channel, @NotNull HiveMessageListener listener);

    /** {@link #publish(String, String)} on {@code channel.asString()}. */
    default void publish(@NotNull Namespaced channel, @NotNull String message) {
        publish(channel.asString(), message);
    }

    /** {@link #subscribe(String, HiveMessageListener)} on {@code channel.asString()}. */
    default @NotNull Registration subscribe(@NotNull Namespaced channel, @NotNull HiveMessageListener listener) {
        return subscribe(channel.asString(), listener);
    }

    /**
     * Like {@link #publish(String, String)}, but only to the subscribers in {@code target}, or to the host's (Hive
     * plugins' host halves) when it's null.
     */
    void send(@Nullable HiveServer target, @NotNull String channel, @NotNull String message);

    default void send(@Nullable HiveServer target, @NotNull Namespaced channel, @NotNull String message) {
        send(target, channel.asString(), message);
    }

    /**
     * Asks {@code target} (null: the host) on a service channel; its {@link #respond responder} answers. The future
     * completes on a Paperhive thread, or fails with {@code NoResponderException} (nobody answers there) or a
     * {@code TimeoutException}. {@link HiveService} is the typed way.
     */
    @NotNull CompletableFuture<String> request(@Nullable HiveServer target, @NotNull String channel, @NotNull String message, @NotNull Duration timeout);

    default @NotNull CompletableFuture<String> request(@Nullable HiveServer target, @NotNull Namespaced channel, @NotNull String message, @NotNull Duration timeout) {
        return request(target, channel.asString(), message, timeout);
    }

    /**
     * Answers requests on {@code channel} sent to the calling server (from the host: to the host). One responder per
     * channel and server: a second replaces the first until it's removed.
     */
    @NotNull Registration respond(@NotNull String channel, @NotNull HiveResponder responder);

    default @NotNull Registration respond(@NotNull Namespaced channel, @NotNull HiveResponder responder) {
        return respond(channel.asString(), responder);
    }

    /** A typed channel; see {@link HiveChannel}. */
    default <T> @NotNull HiveChannel<T> channel(@NotNull Namespaced key, @NotNull MessageCodec<T> codec) {
        return new HiveChannel<>(this, key, codec);
    }

    /** A typed request/reply service; see {@link HiveService}. */
    default <Q, R> @NotNull HiveService<Q, R> service(@NotNull Namespaced key, @NotNull MessageCodec<Q> request, @NotNull MessageCodec<R> reply) {
        return new HiveService<>(this, key, request, reply);
    }

    /** Data shared by the whole hive, held by the host; see {@link HiveData}. */
    @NotNull HiveData getData();

}
