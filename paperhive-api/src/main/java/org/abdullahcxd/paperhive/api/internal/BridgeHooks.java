package org.abdullahcxd.paperhive.api.internal;

import org.abdullahcxd.paperhive.api.HiveServerStats;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/** Between Paperhive's bridge plugin (inside each server) and the host. Not for plugins. */
public interface BridgeHooks {

    /** A Hive plugin's server half to load: its {@code server-main} class from its jar. */
    record ServerHalf(@NotNull String name, @NotNull String version, @NotNull Path jar, @NotNull String main) {}

    /**
     * The bridge in the calling server is enabled.
     *
     * @param mainThread runs tasks on that server's main thread
     * @param stats      reads its load; safe to call from any thread
     */
    void bridgeEnabled(@NotNull Executor mainThread, @NotNull Supplier<HiveServerStats> stats);

    /** The bridge in the calling server is being disabled (the server is stopping). */
    void bridgeDisabled();

    /** The server halves the calling server loads: enabled Hive plugins with a {@code server-main} it doesn't disable. */
    @NotNull List<ServerHalf> serverHalves();

}
