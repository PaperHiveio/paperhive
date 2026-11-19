package org.abdullahcxd.paperhive.api.message;

import org.abdullahcxd.paperhive.api.HiveServer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletionStage;

/**
 * Answers requests on a service channel ({@code Hive.respond}); {@link HiveService#handle} is the typed way.
 * Called on a Paperhive thread: return quickly, and do slow or main-thread work in the returned stage.
 */
@FunctionalInterface
public interface HiveResponder {

    /**
     * @param from the requesting server, or null for the host
     * @return the reply; completing it exceptionally fails the request with that exception
     */
    @NotNull CompletionStage<String> respond(@Nullable HiveServer from, @NotNull String channel, @NotNull String message);

}
