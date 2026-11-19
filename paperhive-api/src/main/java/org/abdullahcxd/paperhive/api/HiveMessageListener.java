package org.abdullahcxd.paperhive.api;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Receives {@link Hive#publish} messages. Runs on a Paperhive thread: hop to a server's main thread before using Bukkit. */
@FunctionalInterface
public interface HiveMessageListener {
    /** @param from the server that published it, or null for the host */
    void onMessage(@Nullable HiveServer from, @NotNull String channel, @NotNull String message);
}
