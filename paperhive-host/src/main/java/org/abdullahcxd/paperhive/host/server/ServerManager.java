package org.abdullahcxd.paperhive.host.server;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.abdullahcxd.paperhive.host.software.paper.PaperSoftware;
import org.abdullahcxd.paperhive.host.config.HiveConfig;
import org.abdullahcxd.paperhive.host.eula.EULA;
import org.abdullahcxd.paperhive.host.instance.ServerInstance;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The host's servers: their {@code paperhive.yml} entries, their instances, and which one the console is attached to.
 * Changes to the file go through here, so the two stay in step.
 */
@Slf4j
public final class ServerManager {

    /** How long a stop waits for the server before giving up on it ({@code Host.StopTimeout}). */
    @Getter
    private final Duration stopTimeout;
    private final @Nullable Path velocitySecret;
    private final HiveConfig.@NotNull EulaMode eula;

    @Getter
    private final Path configFile;
    private final Function<HiveConfig.Server, ServerInstance> factory;
    private final List<String> userArgs;
    private final Function<HiveConfig.Server, List<String>> extraArgs;
    private final Map<String, HiveConfig.Server> servers = new LinkedHashMap<>();
    private final Map<String, ServerInstance> instances = new LinkedHashMap<>();
    // the settings each instance was made with; a `set` since then makes a new instance at the next start
    private final Map<String, HiveConfig.Server> builtFrom = new LinkedHashMap<>();
    private volatile @Nullable ServerInstance attached;
    /**
     * -- SETTER --
     * Asked before every start through here (not a crash restart); false keeps the server stopped.
     */
    @Setter
    private volatile Predicate<String> startGuard = name -> true;

    /**
     * @param userArgs  replace each server's default arguments when not empty
     * @param extraArgs added to a server's arguments at each start, either way (Paperhive's own plugins)
     * @param factory   makes the (not yet started) instance for a server
     */
    public ServerManager(Path configFile, @NotNull HiveConfig config, List<String> userArgs,
                         Function<HiveConfig.Server, List<String>> extraArgs, Function<HiveConfig.Server, ServerInstance> factory) {
        this.configFile = configFile;
        this.factory = factory;
        this.userArgs = List.copyOf(userArgs);
        this.extraArgs = extraArgs;
        this.stopTimeout = config.host().stopTimeout();
        this.velocitySecret = config.host().velocitySecret();
        this.eula = config.host().eula();
        for (HiveConfig.Server server : config.servers()) add(server);
    }

    public synchronized @NotNull @Unmodifiable List<ServerInstance> instances() {
        return List.copyOf(instances.values());
    }

    public synchronized @NotNull @Unmodifiable List<String> names() {
        return List.copyOf(instances.keySet());
    }

    public synchronized @Nullable ServerInstance instance(@NotNull String name) {
        return instances.get(name);
    }

    public synchronized HiveConfig.@NotNull Server server(@NotNull String name) {
        return servers.get(name);
    }

    public @Nullable ServerInstance attached() {
        return attached;
    }

    public void startAutostart() {
        for (HiveConfig.Server server : List.copyOf(servers.values())) {
            if (server.autostart()) start(server.name());
        }
    }

    public synchronized int nextFreePort() {
        return servers.values().stream().mapToInt(HiveConfig.Server::port).max().orElse(25564) + 1;
    }

    /** @throws HiveConfig.HiveConfigException if the file would become invalid (then nothing changed) */
    public synchronized HiveConfig.@NotNull Server create(@NotNull String name, int port) {
        // the file may also hold edits made since the host started; only the new server is taken from it
        HiveConfig.Server server = HiveConfig.addServer(configFile, name, port).servers().getLast();
        add(server);
        return server;
    }

    /** Applies at the server's next start. @throws HiveConfig.HiveConfigException as {@link #create} */
    public synchronized void set(@NotNull String name, @NotNull String key, @Nullable String value) {
        HiveConfig config = HiveConfig.setServerKey(configFile, name, key, value);
        servers.put(name, config.servers().stream().filter(s -> s.name().equals(name)).findFirst().orElseThrow());
    }

    /** For a stopped server only; its folder stays. @throws HiveConfig.HiveConfigException as {@link #create} */
    public synchronized void delete(@NotNull ServerInstance instance) {
        String name = instance.getName();
        if (instance.isActive()) throw new IllegalStateException(name + " is " + instance.getState());
        HiveConfig.removeServer(configFile, name);
        if (attached == instance) detach();
        servers.remove(name);
        instances.remove(name);
        builtFrom.remove(name);
    }

    /** @return false if it couldn't start (logged) or the start guard said no */
    public synchronized boolean start(@NotNull String name) {
        ServerInstance instance = instances.get(name);
        if (instance == null) return false; // deleted meanwhile
        if (!startGuard.test(name)) {
            log.info("Not starting {}: a ServerStartEvent was cancelled", name);
            return false;
        }
        HiveConfig.Server server = servers.get(name);
        if (!instance.isActive() && !server.equals(builtFrom.get(name))) {
            boolean wasAttached = attached == instance;
            instance = build(server);
            if (wasAttached) attached = instance;
            instance.getConsole().setEcho(attached == null || attached == instance);
        }
        try {
            for (Path dir : server.sharedPlugins()) java.nio.file.Files.createDirectories(dir); // Paper skips a missing one
            // forwarding is set up on the servers behind a proxy, Paper ones
            if (velocitySecret != null && instance.getSoftware() instanceof PaperSoftware) VelocityForwarding.apply(instance.getRoot(), velocitySecret);
            // Velocity has no EULA
            if (instance.getSoftware() instanceof PaperSoftware && server.agreesToEula(eula) && EULA.agree(instance.getRoot())) {
                log.info("Agreed to the Minecraft EULA for {}", name);
            }
            List<String> args = new ArrayList<>(userArgs.isEmpty() ? instance.serverArgs() : userArgs);
            args.addAll(extraArgs.apply(server));
            instance.start(args);
            return true;
        } catch (RuntimeException | java.io.IOException e) {
            log.error("Cannot start instance {}", name, e);
            return false;
        }
    }

    /** Stops it in the background; the state log reports the progress. */
    public void stop(@NotNull ServerInstance instance) {
        background("stop-" + instance.getName(), () -> instance.stop(stopTimeout));
    }

    public void restart(@NotNull ServerInstance instance) {
        background("restart-" + instance.getName(), () -> {
            instance.stop(stopTimeout);
            start(instance.getName());
        });
    }

    /** Every server stops (the shutdown hook) and Paperhive ends; in the background so a reply still gets out. */
    public void shutdown() {
        background("shutdown", () -> System.exit(0));
    }

    /** The console shows only this server's output and sends it what's typed. */
    public synchronized void attach(@NotNull ServerInstance target) {
        attached = target;
        for (ServerInstance i : instances.values()) i.getConsole().setEcho(i == target);
    }

    /** @return false if not attached */
    public synchronized boolean detach() {
        if (attached == null) return false;
        attached = null;
        for (ServerInstance i : instances.values()) i.getConsole().setEcho(true);
        return true;
    }

    private void add(HiveConfig.Server server) {
        servers.put(server.name(), server);
        build(server);
    }

    private ServerInstance build(HiveConfig.Server server) {
        ServerInstance instance = factory.apply(server);
        instances.put(server.name(), instance);
        builtFrom.put(server.name(), server);
        // a server created while attached elsewhere stays quiet too
        if (attached != null) instance.getConsole().setEcho(attached == instance);
        return instance;
    }

    // captured on the host's thread: calls also come from inside servers (Hive API), and these threads must be the host's
    private static final ThreadGroup HOST_GROUP = Thread.currentThread().getThreadGroup();

    private static void background(String name, Runnable task) {
        Thread thread = new Thread(HOST_GROUP, task, "paperhive-" + name);
        thread.setContextClassLoader(ServerManager.class.getClassLoader());
        thread.setDaemon(true);
        thread.start();
    }

}
