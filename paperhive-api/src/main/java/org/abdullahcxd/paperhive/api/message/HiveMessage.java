package org.abdullahcxd.paperhive.api.message;

import org.abdullahcxd.paperhive.api.HiveServer;
import org.abdullahcxd.paperhive.api.Namespaced;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A message received on a {@link HiveChannel}, or a request a {@link HiveService} handles.
 *
 * @param from    the server that sent it, or null when the host (a Hive plugin's host half) did
 * @param channel the channel or service it came on
 * @param payload the decoded value
 */
public record HiveMessage<T>(@Nullable HiveServer from, @NotNull Namespaced channel, @NotNull T payload) {

    /** Whether the host sent it. */
    public boolean fromHost() {
        return from == null;
    }

}
