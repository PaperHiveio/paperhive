package org.abdullahcxd.paperhive.host;

import org.abdullahcxd.paperhive.api.HiveServer;
import org.abdullahcxd.paperhive.api.HiveServerStats;
import org.abdullahcxd.paperhive.host.command.CommandRegistry;
import org.abdullahcxd.paperhive.host.config.HiveConfig;
import org.abdullahcxd.paperhive.host.instance.ApiGateLoader;
import org.abdullahcxd.paperhive.host.instance.Leaks;
import org.abdullahcxd.paperhive.host.instance.ServerInstance;
import org.abdullahcxd.paperhive.host.paperclip.PaperclipCache;
import org.abdullahcxd.paperhive.host.paperclip.PatchedServer;
import org.abdullahcxd.paperhive.host.plugin.HivePluginManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M9's leak check with real Paper: a server with the bridge and the example plugin's server half (which registers
 * Bukkit and Hive listeners, a Hive subscription and a Brigadier command), restarted many times; every old instance
 * loader must be collected. Opt-in like {@code PaperInstanceIT}, with the same flags.
 */
@EnabledIfSystemProperty(named = "paperhive.it.paperJar", matches = ".+")
@EnabledIfSystemProperty(named = "com.mojang.eula.agree", matches = "true")
class HivePluginLeakIT {

    private static final int ROUNDS = Integer.getInteger("paperhive.it.rounds", 10);

    @TempDir
    Path tmp;

    @Test
    void restartsWithAServerHalfWithoutLeaking() throws Exception {
        HostHive hive = TestHive.get();
        Path cache = Path.of(System.getProperty("paperhive.it.cache", tmp.resolve("cache").toString()));
        HivePluginManager plugins = new HivePluginManager(hive::deliver, hive::release);
        try (PatchedServer server = PaperclipCache.prepare(Path.of(System.getProperty("paperhive.it.paperJar")), cache)) {
            Path bridge = tmp.resolve("paperhive-bridge.jar");
            try (InputStream in = getClass().getResourceAsStream("/paperhive-bridge.jar")) {
                Files.copy(in, bridge);
            }
            Files.createDirectories(tmp.resolve("plugins"));
            Files.copy(Path.of(System.getProperty("paperhive.test.examplePlugin")), tmp.resolve("plugins/example.jar"));
            Path config = Files.writeString(tmp.resolve(HiveConfig.FILE_NAME), "ConfigurationVersion: 1\nServers:\n  - Name: leak\n    Port: "
                    + freePort() + "\n    Root: " + tmp.resolve("leak").toString().replace('\\', '/') + "\n");

            ApiGateLoader gate = new ApiGateLoader(getClass().getClassLoader());
            ServerManager servers = new ServerManager(config, HiveConfig.load(config), List.of(), s -> List.of("--add-plugin", bridge.toString()), s -> {
                ServerInstance instance = new ServerInstance(s.name(), s.root(), server, gate, s.options());
                instance.addStateListener(hive::stateChanged);
                instance.addStateListener((i, from, to) -> {
                    if (to == ServerInstance.State.RUNNING) plugins.serverStarted(hive.config(i.getName()), hive.handle(i.getName()));
                });
                return instance;
            });
            hive.init(servers, plugins::serverHalves);
            servers.setStartGuard(hive::allowStart);
            plugins.loadPlugins(tmp.resolve("plugins"));
            plugins.enableAll(CommandRegistry.withBuiltins(servers, name -> null));
            List<String> fromHalves = new CopyOnWriteArrayList<>();
            hive.subscribe("example", (from, channel, message) -> fromHalves.add(message));

            HiveServer handle = hive.getServer("leak");
            List<WeakReference<ClassLoader>> loaders = new ArrayList<>();
            for (int round = 1; round <= ROUNDS; round++) {
                assertTrue(handle.start(), "round " + round);
                ServerInstance instance = servers.instance("leak");
                loaders.add(new WeakReference<>(instance.getClassLoader()));
                HiveServerStats stats = awaitStats(handle);
                assertTrue(stats.tps1m() > 0 && stats.players() == 0 && stats.maxPlayers() == 20, stats.toString());

                assertTrue(instance.stop(java.time.Duration.ofSeconds(90)), "round " + round + " had to be forced");
                assertNull(handle.getStats(), "no stats once stopped");
                drain(hive);
                Leaks.gc();
                System.out.printf("[HivePluginLeakIT] round %d: %s, loaders alive %d%n", round, stats,
                        loaders.stream().filter(r -> r.get() != null).count());
            }
            assertEquals(ROUNDS, fromHalves.stream().filter(m -> m.startsWith("the server half is enabled")).count(), "the server half ran every round");
            for (int i = 0; i < loaders.size(); i++) Leaks.assertCollected(loaders.get(i), "instance loader of round " + (i + 1));
        } finally {
            plugins.close();
        }
    }

    /** Running and the bridge up (stats are only there once it is). */
    private static HiveServerStats awaitStats(HiveServer server) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
        while (System.nanoTime() < deadline) {
            HiveServerStats stats = server.getStats();
            if (stats != null) return stats;
            Thread.sleep(250);
        }
        return fail("no stats within 3 minutes, state " + server.getState());
    }

    private static void drain(HostHive hive) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        hive.deliver(done::countDown);
        assertTrue(done.await(30, TimeUnit.SECONDS));
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

}
