package org.abdullahcxd.paperhive.api;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

/** One server of the hive. Stays valid while the server exists, across restarts. */
public interface HiveServer {

    @NotNull String getName();

    int getPort();

    /** What it runs, as {@code paperhive.yml} names it: {@code papermc}, {@code velocitymc}. */
    @NotNull String getSoftware();

    @NotNull HiveServerState getState();

    /** Its folder. */
    @NotNull Path getRoot();

    /** @return false if it's already running, a {@code ServerStartEvent} was cancelled, or it couldn't start (the host logs why) */
    boolean start();

    /** Asks it to stop and returns at once; the event bus reports it ({@code ServerStoppedEvent}). */
    void stop();

    /** Stops it if it runs, then starts it; returns at once. */
    void restart();

    /** TPS, tick time and players right now; null if it isn't running or not far enough yet (like {@link #runOnServer}). */
    @Nullable HiveServerStats getStats();

    /** Runs a console command in it, as if typed there. @return false if it isn't running */
    boolean dispatchCommand(@NotNull String command);

    /**
     * Runs {@code task} on this server's main thread (Bukkit's API mostly wants that thread).
     *
     * @throws IllegalStateException if it isn't running, or isn't far enough yet (Paperhive's plugin in it isn't enabled)
     */
    void runOnServer(@NotNull Runnable task);

}
