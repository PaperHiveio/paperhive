package org.abdullahcxd.paperhive.host.hive;

import org.abdullahcxd.paperhive.host.server.ServerManager;
import lombok.extern.slf4j.Slf4j;
import org.abdullahcxd.paperhive.api.Hive;
import org.abdullahcxd.paperhive.api.HiveMessageListener;
import org.abdullahcxd.paperhive.api.HiveServer;
import org.abdullahcxd.paperhive.api.HiveServerState;
import org.abdullahcxd.paperhive.api.HiveServerStats;
import org.abdullahcxd.paperhive.api.Registration;
import org.abdullahcxd.paperhive.api.data.HiveData;
import org.abdullahcxd.paperhive.api.message.HiveResponder;
import org.abdullahcxd.paperhive.api.message.NoResponderException;
import org.abdullahcxd.paperhive.api.event.EventBus;
import org.abdullahcxd.paperhive.api.event.server.ServerCrashEvent;
import org.abdullahcxd.paperhive.api.event.server.ServerStartEvent;
import org.abdullahcxd.paperhive.api.event.server.ServerStartedEvent;
import org.abdullahcxd.paperhive.api.event.server.ServerStateChangeEvent;
import org.abdullahcxd.paperhive.api.event.server.ServerStoppedEvent;
import org.abdullahcxd.paperhive.api.internal.BridgeHooks;
import org.abdullahcxd.paperhive.host.config.HiveConfig;
import org.abdullahcxd.paperhive.host.instance.InstanceHooks;
import org.abdullahcxd.paperhive.host.instance.ServerInstance;
import org.abdullahcxd.paperhive.host.plugin.HivePluginManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The {@link Hive} API on top of {@link ServerManager}. Event listeners and message subscriptions remember who made
 * them, from their class loader: a server (any loader inside it) or a Hive plugin. They're dropped when that server
 * stops or that plugin is disabled, so they can't keep its loader alive.
 */
@Slf4j
public final class HostHive implements Hive, BridgeHooks {

    private record Owned<T>(@Nullable Object owner, T value) {}

    private volatile ServerManager servers;
    private volatile Function<HiveConfig.Server, List<ServerHalf>> serverHalves;
    private final Map<String, Handle> handles = new ConcurrentHashMap<>();
    /** What each running server's bridge handed over. */
    private record Bridge(Executor mainThread, Supplier<HiveServerStats> stats) {}

    private final Map<String, Bridge> bridges = new ConcurrentHashMap<>();
    private final HiveEventBus bus = new HiveEventBus(HostHive::ownerOf);
    private final Map<String, List<Owned<HiveMessageListener>>> channels = new ConcurrentHashMap<>();
    private final Map<String, List<Owned<HiveResponder>>> responders = new ConcurrentHashMap<>();
    private final HiveDataStore data = new HiveDataStore(HostHive::ownerOf, this::deliver);
    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    /** One thread, so events and messages arrive in order; made now, on the host's thread (not lazily from a server's). */
    private final ThreadPoolExecutor events = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), r -> {
        Thread t = new Thread(r, "paperhive-events");
        t.setDaemon(true);
        return t;
    });

    public HostHive() {
        events.prestartAllCoreThreads();
    }

    /**
     * Before anything starts.
     *
     * @param serverHalves the Hive plugin server halves a server loads (its bridge asks)
     */
    public void init(@NotNull ServerManager servers, @NotNull Function<HiveConfig.Server, List<ServerHalf>> serverHalves) {
        this.servers = servers;
        this.serverHalves = serverHalves;
    }

    @Override
    public @NotNull @Unmodifiable List<HiveServer> getServers() {
        return servers.names().stream().map(this::handle).toList();
    }

    @Override
    public @Nullable HiveServer getServer(@NotNull String name) {
        return servers.instance(name) == null ? null : handle(name);
    }

    @Override
    public @Nullable HiveServer getCurrentServer() {
        ServerInstance current = ServerInstance.ofThread(Thread.currentThread());
        return current == null ? null : handle(current.getName());
    }

    @Override
    public @NotNull HiveServer createServer(@NotNull String name, int port) {
        try {
            return handle(servers.create(name, port).name());
        } catch (HiveConfig.HiveConfigException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    @Override
    public @NotNull EventBus getEventBus() {
        return bus;
    }

    @Override
    public void publish(@NotNull String channel, @NotNull String message) {
        HiveServer from = getCurrentServer();
        for (Owned<HiveMessageListener> sub : channels.getOrDefault(channel, List.of())) {
            deliver(() -> sub.value().onMessage(from, channel, message));
        }
    }

    @Override
    public void send(@Nullable HiveServer target, @NotNull String channel, @NotNull String message) {
        HiveServer from = getCurrentServer();
        for (Owned<HiveMessageListener> sub : channels.getOrDefault(channel, List.of())) {
            if (ownedBy(sub.owner(), target)) deliver(() -> sub.value().onMessage(from, channel, message));
        }
    }

    @Override
    public @NotNull Registration subscribe(@NotNull String channel, @NotNull HiveMessageListener listener) {
        return subscribeAs(ownerOf(listener), channel, listener);
    }

    /** {@link #subscribe} for a known owner (a server, a Hive plugin's loader, or null for the host). */
    @NotNull Registration subscribeAs(@Nullable Object owner, String channel, HiveMessageListener listener) {
        Owned<HiveMessageListener> owned = new Owned<>(owner, listener);
        channels.computeIfAbsent(channel, c -> new CopyOnWriteArrayList<>()).add(owned);
        return () -> channels.getOrDefault(channel, List.of()).remove(owned);
    }

    @Override
    public @NotNull CompletableFuture<String> request(@Nullable HiveServer target, @NotNull String channel, @NotNull String message, @NotNull Duration timeout) {
        HiveServer from = getCurrentServer();
        CompletableFuture<String> answer = new CompletableFuture<>();
        deliver(() -> {
            Owned<HiveResponder> responder = responderFor(channel, target);
            if (responder == null) {
                answer.completeExceptionally(new NoResponderException("Nobody answers " + channel + " on " + (target == null ? "the host" : target.getName())));
                return;
            }
            CompletionStage<String> stage;
            try {
                stage = Objects.requireNonNull(responder.value().respond(from, channel, message), "a responder returned null");
            } catch (Throwable e) {
                answer.completeExceptionally(e);
                return;
            }
            stage.whenComplete((reply, error) -> {
                if (error != null) answer.completeExceptionally(error instanceof CompletionException c && c.getCause() != null ? c.getCause() : error);
                else answer.complete(reply);
            });
        });
        // the caller's callbacks run on the event thread, never on the responding server's main thread
        CompletableFuture<String> result = new CompletableFuture<>();
        answer.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS).whenComplete((reply, error) -> deliver(() -> {
            if (error != null) result.completeExceptionally(error);
            else result.complete(reply);
        }));
        return result;
    }

    @Override
    public @NotNull Registration respond(@NotNull String channel, @NotNull HiveResponder responder) {
        return respondAs(ownerOf(responder), channel, responder);
    }

    /** {@link #respond} for a known owner. */
    @NotNull Registration respondAs(@Nullable Object owner, String channel, HiveResponder responder) {
        Owned<HiveResponder> owned = new Owned<>(owner, responder);
        responders.computeIfAbsent(channel, c -> new CopyOnWriteArrayList<>()).add(owned);
        return () -> responders.getOrDefault(channel, List.of()).remove(owned);
    }

    /** The newest responder on {@code channel} owned by {@code target} (null: the host's). */
    private @Nullable Owned<HiveResponder> responderFor(String channel, @Nullable HiveServer target) {
        List<Owned<HiveResponder>> list = responders.getOrDefault(channel, List.of());
        for (int i = list.size() - 1; i >= 0; i--) {
            Owned<HiveResponder> r = list.get(i);
            if (ownedBy(r.owner(), target)) return r;
        }
        return null;
    }

    /** Whether something with {@code owner} belongs to {@code target}: that server, or the host side when it's null. */
    private static boolean ownedBy(@Nullable Object owner, @Nullable HiveServer target) {
        return target == null ? !(owner instanceof ServerInstance) : owner instanceof ServerInstance i && i.getName().equals(target.getName());
    }

    @Override
    public @NotNull HiveData getData() {
        return data;
    }

    @Override
    public void bridgeEnabled(@NotNull Executor mainThread, @NotNull Supplier<HiveServerStats> stats) {
        ServerInstance caller = caller();
        if (caller == null) throw new IllegalStateException("The bridge must be enabled from inside a server");
        bridges.put(caller.getName(), new Bridge(mainThread, stats));
    }

    @Override
    public void consoleEnabled(@NotNull Predicate<String> dispatcher) {
        ServerInstance caller = caller();
        if (caller == null) throw new IllegalStateException("Only a server's own plugin can take its console commands");
        caller.setConsoleDispatcher(dispatcher);
    }

    @Override
    public void bridgeDisabled() {
        ServerInstance caller = caller();
        if (caller != null) bridges.remove(caller.getName());
    }

    @Override
    public @NotNull List<ServerHalf> serverHalves() {
        ServerInstance caller = caller();
        if (caller == null) throw new IllegalStateException("Only a server has server halves");
        return serverHalves.apply(servers.server(caller.getName()));
    }

    /** Every instance's state listener: posts the state events on the event thread. */
    public void stateChanged(@NotNull ServerInstance instance, ServerInstance.@NotNull State from, ServerInstance.@NotNull State to) {
        if (to == ServerInstance.State.STOPPED || to == ServerInstance.State.CRASHED) {
            bridges.remove(instance.getName());
            release(instance);
        }
        HiveServer server = handle(instance.getName());
        HiveServerState f = HiveServerState.valueOf(from.name()), t = HiveServerState.valueOf(to.name());
        deliver(() -> {
            bus.post(new ServerStateChangeEvent(server, f, t));
            switch (to) {
                case RUNNING -> bus.post(new ServerStartedEvent(server));
                case STOPPED -> bus.post(new ServerStoppedEvent(server));
                case CRASHED -> bus.post(new ServerCrashEvent(server));
                default -> { }
            }
        });
    }

    /** Asked before a start through Paperhive (not a crash restart); false if a subscriber cancelled it. */
    public boolean allowStart(@NotNull String name) {
        return !bus.post(new ServerStartEvent(handle(name))).isCancelled();
    }

    /** Drops the event listeners and message subscriptions {@code owner} made (a stopped server, a disabled Hive plugin's loader). */
    public void release(@NotNull Object owner) {
        bus.unregisterOwnedBy(owner);
        channels.values().forEach(subs -> subs.removeIf(o -> o.owner() == owner));
        responders.values().forEach(list -> list.removeIf(o -> o.owner() == owner));
        data.release(owner);
    }

    /**
     * The server whose loader made {@code listener}'s class, else its Hive plugin's loader. A class shared by everyone
     * (Paperhive's API, e.g. the wrappers {@code HiveChannel} makes, or the JDK's) says nothing, so then the nearest
     * caller on the stack that belongs to a server or a Hive plugin decides; null means the host.
     */
    static @Nullable Object ownerOf(@NotNull Object listener) {
        Object owner = ownerOfLoader(listener.getClass().getClassLoader());
        if (owner != null) return owner;
        return WALKER.walk(frames -> frames
                .map(f -> ownerOfLoader(f.getDeclaringClass().getClassLoader()))
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null));
    }

    private static @Nullable Object ownerOfLoader(@Nullable ClassLoader loader) {
        ServerInstance instance = InstanceHooks.instanceOf(loader);
        if (instance != null) return instance;
        for (ClassLoader l = loader; l != null; l = l.getParent()) {
            if (HivePluginManager.isPluginLoader(l)) return l;
        }
        return null;
    }

    /** Runs on the events thread (in order with events and messages). */
    public void deliver(@NotNull Runnable task) {
        events.execute(() -> {
            try {
                task.run();
            } catch (Throwable e) { // a broken listener mustn't stop the others, nor the thread
                log.warn("A Hive listener failed", e);
            }
        });
    }

    public HiveConfig.@NotNull Server config(@NotNull String name) {
        return servers.server(name);
    }

    public @NotNull HiveServer handle(@NotNull String name) {
        return handles.computeIfAbsent(name, Handle::new);
    }

    private static @Nullable ServerInstance caller() {
        return ServerInstance.ofThread(Thread.currentThread());
    }

    /** By name, so it outlives restarts and instances rebuilt by {@code set}. */
    private final class Handle implements HiveServer {
        private final String name;

        private Handle(String name) {
            this.name = name;
        }

        private @NotNull ServerInstance instance() {
            ServerInstance instance = servers.instance(name);
            if (instance == null) throw new IllegalStateException("The server " + name + " was deleted");
            return instance;
        }

        @Override public @NotNull String getName() { return name; }
        @Override public int getPort() { instance(); return servers.server(name).port(); }
        @Override public @NotNull String getSoftware() { instance(); return servers.server(name).software().getName(); }
        @Override public @NotNull HiveServerState getState() { return HiveServerState.valueOf(instance().getState().name()); }
        @Override public @NotNull Path getRoot() { return instance().getRoot(); }
        @Override public boolean start() { return !instance().isActive() && servers.start(name); }
        @Override public void stop() { servers.stop(instance()); }
        @Override public void restart() { servers.restart(instance()); }
        @Override public boolean dispatchCommand(@NotNull String command) { return instance().dispatchCommand(command); }

        @Override
        public void runOnServer(@NotNull Runnable task) {
            Bridge bridge = bridges.get(name);
            if (bridge == null || !instance().isActive()) throw new IllegalStateException(name + " isn't running, or not far enough yet");
            bridge.mainThread().execute(task);
        }

        @Override
        public @Nullable HiveServerStats getStats() {
            Bridge bridge = bridges.get(name);
            if (bridge == null || !instance().isActive()) return null;
            try {
                return bridge.stats().get();
            } catch (RuntimeException e) { // e.g. the server is just going down
                return null;
            }
        }

        @Override public boolean equals(@Nullable Object o) { return o instanceof Handle h && h.name.equals(name); }
        @Override public int hashCode() { return name.hashCode(); }
        @Override public @NotNull String toString() { return name; }
    }

}
