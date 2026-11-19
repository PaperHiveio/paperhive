package org.abdullahcxd.paperhive.api.message;

import org.abdullahcxd.paperhive.api.Hive;
import org.abdullahcxd.paperhive.api.HiveServer;
import org.abdullahcxd.paperhive.api.Namespaced;
import org.abdullahcxd.paperhive.api.Registration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

/**
 * Request and reply between the host and servers, typed: ask one server (or the host) a question and get its answer
 * as a {@link CompletableFuture}. Get one with {@link Hive#service(Namespaced, MessageCodec, MessageCodec)}.
 * <pre>{@code
 * HiveService<UUID, Boolean> isOnline = hive.service(key("is-online"), MessageCodec.UUID_CODEC, MessageCodec.BOOLEAN);
 *
 * // in each server (the server half): answer for this server
 * isOnline.handle(request -> getServer().getPlayer(request.payload()) != null);
 *
 * // anywhere: ask the lobby
 * isOnline.request(hive.getServer("lobby"), uuid)
 *         .thenAccept(online -> log("online in the lobby: {}", online));
 * }</pre>
 * Each server (and the host) answers with the handler it registered for the service; if there's none, or it doesn't
 * answer within the timeout (5 seconds unless given), the future fails ({@link NoResponderException},
 * {@link java.util.concurrent.TimeoutException}). The future completes on a Paperhive thread.
 *
 * @param <Q> the request type
 * @param <R> the reply type
 */
public final class HiveService<Q, R> {

    /** How long {@link #request(HiveServer, Object)} waits for an answer. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    private final Hive hive;
    private final Namespaced key;
    private final MessageCodec<Q> request;
    private final MessageCodec<R> reply;

    public HiveService(@NotNull Hive hive, @NotNull Namespaced key, @NotNull MessageCodec<Q> request, @NotNull MessageCodec<R> reply) {
        this.hive = Objects.requireNonNull(hive);
        this.key = Objects.requireNonNull(key);
        this.request = Objects.requireNonNull(request);
        this.reply = Objects.requireNonNull(reply);
    }

    public @NotNull Namespaced key() {
        return key;
    }

    /** Asks {@code target} (null: the host), waiting up to {@link #DEFAULT_TIMEOUT}. */
    public @NotNull CompletableFuture<R> request(@Nullable HiveServer target, @NotNull Q value) {
        return request(target, value, DEFAULT_TIMEOUT);
    }

    public @NotNull CompletableFuture<R> request(@Nullable HiveServer target, @NotNull Q value, @NotNull Duration timeout) {
        return hive.request(target, key, request.encode(value), timeout).thenApply(reply::decode);
    }

    /** Answers this service's requests to the calling server (or to the host, from the host), right away. */
    public @NotNull Registration handle(@NotNull Function<HiveMessage<Q>, R> handler) {
        return handleAsync(message -> CompletableFuture.completedFuture(handler.apply(message)));
    }

    /** Like {@link #handle}, for an answer that takes a while (or needs the main thread): complete the stage later. */
    public @NotNull Registration handleAsync(@NotNull Function<HiveMessage<Q>, ? extends CompletionStage<R>> handler) {
        return hive.respond(key, (from, channel, message) ->
                handler.apply(new HiveMessage<>(from, key, request.decode(message))).thenApply(reply::encode));
    }

    @Override
    public @NotNull String toString() {
        return "HiveService[" + key + "]";
    }

}
