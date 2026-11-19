package org.abdullahcxd.paperhive.api.message;

import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;

/**
 * Turns values into the text that travels between the host and servers, and back. Messages cross class loaders (each
 * server has its own copy of your classes), so only text is shared; a codec puts your types on either end.
 * <pre>{@code
 * MessageCodec<Integer> count = MessageCodec.INTEGER;
 * MessageCodec<GameMode> mode = MessageCodec.enumOf(GameMode.class);
 * // anything else, e.g. JSON with the Gson Paper ships:
 * MessageCodec<Invite> invite = MessageCodec.of(gson::toJson, json -> gson.fromJson(json, Invite.class));
 * }</pre>
 *
 * @param <T> the value type
 */
public interface MessageCodec<T> {

    @NotNull String encode(@NotNull T value);

    /** @throws RuntimeException if {@code text} isn't a valid encoding (the receiving listener then doesn't run, and it's logged) */
    @NotNull T decode(@NotNull String text);

    MessageCodec<String> STRING = of(s -> s, s -> s);
    MessageCodec<Integer> INTEGER = of(String::valueOf, Integer::valueOf);
    MessageCodec<Long> LONG = of(String::valueOf, Long::valueOf);
    MessageCodec<Double> DOUBLE = of(String::valueOf, Double::valueOf);
    MessageCodec<Boolean> BOOLEAN = of(String::valueOf, s -> switch (s) {
        case "true" -> true;
        case "false" -> false;
        default -> throw new IllegalArgumentException("Not a boolean: " + s);
    });
    MessageCodec<UUID> UUID_CODEC = of(UUID::toString, UUID::fromString);

    /** From two functions. */
    static <T> @NotNull MessageCodec<T> of(@NotNull Function<T, String> encode, @NotNull Function<String, T> decode) {
        return new MessageCodec<>() {
            @Override public @NotNull String encode(@NotNull T value) { return encode.apply(value); }
            @Override public @NotNull T decode(@NotNull String text) { return decode.apply(text); }
        };
    }

    /** An enum by its constant's name. */
    static <E extends Enum<E>> @NotNull MessageCodec<E> enumOf(@NotNull Class<E> type) {
        return of(Enum::name, s -> Enum.valueOf(type, s.toUpperCase(Locale.ROOT)));
    }

    /** This codec for another type: {@code MessageCodec.STRING.map(Name::new, Name::value)}. */
    default <R> @NotNull MessageCodec<R> map(@NotNull Function<T, R> to, @NotNull Function<R, T> from) {
        MessageCodec<T> self = this;
        return of(r -> self.encode(from.apply(r)), s -> to.apply(self.decode(s)));
    }

}
