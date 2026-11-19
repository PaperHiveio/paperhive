package org.abdullahcxd.paperhive.api.event;

import org.abdullahcxd.paperhive.api.Registration;
import org.jetbrains.annotations.NotNull;

/**
 * Hive events, in the style of Guava's EventBus: any object with {@link Subscribe} methods is a listener (no
 * interface needed). Thread-safe. Listeners registered from inside a server are dropped when that server stops, and
 * a Hive plugin's when it's disabled.
 */
public interface EventBus {

    /** @throws IllegalArgumentException if a {@link Subscribe} method doesn't take exactly one {@link HiveEvent} */
    @NotNull Registration register(@NotNull Object listener);

    void unregister(@NotNull Object listener);

    /**
     * Runs every subscriber of {@code event} on the calling thread, by {@link Priority}; one that throws is logged
     * and the rest still run. Paperhive's own events are posted on a Paperhive thread (see each event).
     *
     * @return {@code event}, to check {@link CancellableEvent#isCancelled()}
     */
    <T extends HiveEvent> @NotNull T post(@NotNull T event);

}
