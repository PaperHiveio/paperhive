package org.abdullahcxd.paperhive.host.instance;

import org.abdullahcxd.paperhive.host.paperclip.PaperclipCache;
import org.abdullahcxd.paperhive.host.paperclip.PatchedServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.ref.WeakReference;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Opt-in checks against a real Paper (Paperclip) jar: M3 (restarts don't leak) and M4 (instances keep to their own
 * root, the JVM cwd is irrelevant). Runs only when given one.
 * The test JVM gets {@code -XX:SoftRefLRUPolicyMSPerMB=0}: JDK-internal soft caches (e.g. method handle
 * {@code asType} caches) can hold an old instance's classes until memory gets tight; clearing soft references
 * on every GC makes "no strong leak" checkable without filling the heap first.
 * <pre>
 * ./gradlew :paperhive-host:test --tests '*PaperInstanceIT' \
 *     -Dpaperhive.it.paperJar=/path/to/paper.jar [-Dpaperhive.it.cache=/path/to/paperclip-cache] \
 *     -Dcom.mojang.eula.agree=true    # you accept the Minecraft EULA by passing this (restart test only)
 * </pre>
 */
@EnabledIfSystemProperty(named = "paperhive.it.paperJar", matches = ".+")
class PaperInstanceIT {

    private static final int ROUNDS = Integer.getInteger("paperhive.it.rounds", 10);

    @TempDir
    Path tmp;

    @Test
    @EnabledIfSystemProperty(named = "com.mojang.eula.agree", matches = "true")
    void restartsWithoutLeakingClassesOrThreads() throws Exception {
        try (PatchedServer server = prepare()) {
            Path root = tmp.resolve("instance");
            int port = freePort();
            Files.createDirectories(root);
            Files.writeString(root.resolve("server.properties"), "server-port=" + port + "\nonline-mode=false\n");
            ServerInstance instance = new ServerInstance("it", root, server, new ApiGateLoader(getClass().getClassLoader()));

            List<WeakReference<ClassLoader>> loaders = new ArrayList<>();
            long[] classes = new long[ROUNDS];
            int[] threads = new int[ROUNDS];
            for (int i = 0; i < ROUNDS; i++) {
                long started = System.nanoTime();
                instance.start();
                loaders.add(new WeakReference<>(instance.getClassLoader()));
                waitForPort(port, Duration.ofMinutes(3));
                long booted = System.nanoTime();

                assertTrue(instance.stop(Duration.ofSeconds(90)), "round " + i + " had to be forced");
                assertEquals(ServerInstance.State.STOPPED, instance.getState());

                gc();
                classes[i] = ManagementFactory.getClassLoadingMXBean().getLoadedClassCount();
                threads[i] = ManagementFactory.getThreadMXBean().getThreadCount();
                System.out.printf("[PaperInstanceIT] round %d: boot %.1fs, stop %.1fs, loaded classes %d, threads %d, loaders alive %d%n",
                        i + 1, (booted - started) / 1e9, (System.nanoTime() - booted) / 1e9,
                        classes[i], threads[i], loaders.stream().filter(r -> r.get() != null).count());
            }

            for (int i = 0; i < loaders.size(); i++) {
                assertCollected(loaders.get(i), "loader of round " + (i + 1));
            }
            // round 1 also loads host-side classes once, so compare against round 2
            int base = ROUNDS > 1 ? 1 : 0;
            assertTrue(classes[ROUNDS - 1] - classes[base] < 2_000,
                    "loaded classes grew from " + classes[base] + " to " + classes[ROUNDS - 1]);
            assertTrue(threads[ROUNDS - 1] - threads[base] <= 2,
                    "threads grew from " + threads[base] + " to " + threads[ROUNDS - 1]);
        }
    }

    @Test
    void eulaNotAcceptedStopsWithoutExitingTheJvm() throws Exception {
        String agreed = System.clearProperty("com.mojang.eula.agree");
        try (PatchedServer server = prepare()) {
            ServerInstance instance = new ServerInstance("eula", tmp.resolve("eula"), server,
                    new ApiGateLoader(getClass().getClassLoader()));
            instance.start();
            WeakReference<ClassLoader> loader = new WeakReference<>(instance.getClassLoader());

            assertTrue(instance.awaitTermination(Duration.ofMinutes(3)), "instance should stop on its own");
            assertEquals(ServerInstance.State.STOPPED, instance.getState());
            assertTrue(Files.exists(tmp.resolve("eula").resolve("eula.txt")), "Paper should have written eula.txt into its root");
            assertFalse(Files.exists(Path.of("eula.txt")), "nothing may be written to the JVM cwd");
            assertCollected(loader, "loader");
        } finally {
            if (agreed != null) System.setProperty("com.mojang.eula.agree", agreed);
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "com.mojang.eula.agree", matches = "true")
    void twoInstancesKeepToTheirOwnRoots() throws Exception {
        Path cwd = Path.of("").toAbsolutePath();
        // the host's own log (logs/latest.log) does belong in the cwd; start it before taking the snapshot
        org.slf4j.LoggerFactory.getLogger(getClass()).info("Checking that instances leave {} alone", cwd);
        Set<String> cwdBefore = listing(cwd);
        try (PatchedServer server = prepare()) {
            ApiGateLoader gate = new ApiGateLoader(getClass().getClassLoader());
            List<ServerInstance> instances = new ArrayList<>();
            Map<ServerInstance, Integer> ports = new HashMap<>();
            Map<ServerInstance, List<String>> consoles = new HashMap<>();
            for (String name : List.of("alpha", "beta")) {
                Path root = tmp.resolve(name);
                int port = freePort();
                Files.createDirectories(root);
                Files.writeString(root.resolve("server.properties"), "server-port=" + port + "\nonline-mode=false\nmotd=" + name + "\n");
                ServerInstance instance = new ServerInstance(name, root, server, gate);
                List<String> lines = java.util.Collections.synchronizedList(new ArrayList<>());
                instance.getConsole().addListener((line, error) -> lines.add(line));
                consoles.put(instance, lines);
                instances.add(instance);
                ports.put(instance, port);
            }

            instances.forEach(ServerInstance::start); // both at once
            for (ServerInstance instance : instances) waitForPort(ports.get(instance), Duration.ofMinutes(3));
            for (ServerInstance instance : instances) assertTrue(instance.stop(Duration.ofSeconds(90)), instance.getName() + " had to be forced");

            for (ServerInstance instance : instances) {
                Path root = instance.getRoot();
                for (String file : List.of("world/level.dat", "logs/latest.log", "bukkit.yml", "spigot.yml",
                        "config/paper-global.yml", "usercache.json", "plugins")) {
                    assertTrue(Files.exists(root.resolve(file)), instance.getName() + " is missing its own " + file);
                }
                String properties = Files.readString(root.resolve("server.properties"));
                assertTrue(properties.contains("server-port=" + ports.get(instance)), instance.getName() + " lost its port");
                assertTrue(properties.contains("motd=" + instance.getName()), instance.getName() + " got another instance's config");

                // M5: each console and log only has its own instance's output
                int own = ports.get(instance);
                int other = ports.entrySet().stream().filter(e -> e.getKey() != instance).findFirst().orElseThrow().getValue();
                String console = String.join("\n", consoles.get(instance));
                String log = Files.readString(root.resolve("logs/latest.log"));
                for (String[] output : new String[][]{{"console", console}, {"latest.log", log}}) {
                    assertTrue(output[1].contains("Starting Minecraft server on *:" + own), instance.getName() + " " + output[0] + " misses its own startup line");
                    assertFalse(output[1].contains("*:" + other), instance.getName() + " " + output[0] + " has the other instance's output");
                    // plugins log through JUL, which is JVM-wide: one spark line each, not two in one and none in the other
                    assertEquals(1, count(output[1], "[spark] Starting background profiler"), instance.getName() + " " + output[0] + ": spark lines");
                }
            }
        }
        assertEquals(cwdBefore, listing(cwd), "instances must not write into the JVM cwd");
    }

    /** M6: both accept logins at once, have their own Bukkit state, and one restarts without touching the other. */
    @Test
    @EnabledIfSystemProperty(named = "com.mojang.eula.agree", matches = "true")
    void twoInstancesAreJoinableAndRestartIndependently() throws Exception {
        try (PatchedServer server = prepare()) {
            ApiGateLoader gate = new ApiGateLoader(getClass().getClassLoader());
            Map<String, Integer> ports = new HashMap<>();
            Map<String, ServerInstance> instances = new HashMap<>();
            for (String name : List.of("alpha", "beta")) {
                Path root = tmp.resolve(name);
                Files.createDirectories(root);
                // the port comes from --port (as PaperhiveMain passes it), not server.properties
                Files.writeString(root.resolve("server.properties"), "online-mode=false\nmotd=" + name + "\n");
                int port = freePort();
                ports.put(name, port);
                instances.put(name, new ServerInstance(name, root, server, gate,
                        new ServerInstance.Options(port, null, List.of(), ServerInstance.RestartPolicy.NEVER)));
            }
            ServerInstance alpha = instances.get("alpha"), beta = instances.get("beta");
            List<String> alphaStates = java.util.Collections.synchronizedList(new ArrayList<>());
            alpha.addStateListener((i, from, to) -> alphaStates.add(from + ">" + to));
            instances.values().forEach(ServerInstance::start);
            for (ServerInstance instance : instances.values()) awaitRunning(instance);

            assertJoinable(ports, "alpha", "beta");
            Object alphaServer = bukkitServer(alpha), betaServer = bukkitServer(beta);
            assertNotSame(alphaServer, betaServer, "both instances share one Bukkit server");
            assertNotSame(alphaServer.getClass(), betaServer.getClass(), "both instances share server classes");
            assertEquals(ports.get("alpha"), alphaServer.getClass().getMethod("getPort").invoke(alphaServer));
            assertEquals(ports.get("beta"), betaServer.getClass().getMethod("getPort").invoke(betaServer));
            assertEquals("alpha", alphaServer.getClass().getMethod("getMotd").invoke(alphaServer));
            alphaServer = null; // let the first run's loader go
            WeakReference<ClassLoader> firstAlpha = new WeakReference<>(alpha.getClassLoader());

            assertTrue(alpha.stop(Duration.ofSeconds(90)), "alpha had to be forced");
            assertEquals(ServerInstance.State.RUNNING, beta.getState(), "stopping alpha stopped beta");
            assertJoinable(ports, "beta");
            assertThrows(IOException.class, () -> MinecraftClient.status(ports.get("alpha")), "alpha still listens after stop");

            alpha.start();
            awaitRunning(alpha);
            assertJoinable(ports, "alpha", "beta");
            assertSame(betaServer, bukkitServer(beta), "restarting alpha replaced beta's server");
            assertNotSame(betaServer, bukkitServer(alpha));
            betaServer = null;

            for (ServerInstance instance : instances.values()) assertTrue(instance.stop(Duration.ofSeconds(90)), instance.getName() + " had to be forced");
            assertCollected(firstAlpha, "alpha's first loader");
            // M7: RUNNING only once Paper reports it has booted, and a clean stop is STOPPED
            assertEquals(List.of("CREATED>STARTING", "STARTING>RUNNING", "RUNNING>STOPPING", "STOPPING>STOPPED",
                    "STOPPED>STARTING", "STARTING>RUNNING", "RUNNING>STOPPING", "STOPPING>STOPPED"), alphaStates);
        }
    }

    /** M7: RUNNING is set once Paper has finished booting, so the port is open by then too. */
    private static void awaitRunning(ServerInstance instance) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofMinutes(3).toNanos();
        while (instance.getState() == ServerInstance.State.STARTING && System.nanoTime() < deadline) Thread.sleep(100);
        assertEquals(ServerInstance.State.RUNNING, instance.getState(), instance.getName() + " didn't come up");
    }

    private static void assertJoinable(Map<String, Integer> ports, String... names) throws IOException {
        for (String name : names) {
            String status = MinecraftClient.status(ports.get(name));
            assertTrue(status.contains(name), name + " answered with another motd: " + status);
            assertEquals("Hive" + name, MinecraftClient.login(ports.get(name), "Hive" + name), name + " login");
        }
    }

    private static Object bukkitServer(ServerInstance instance) throws Exception {
        Object server = Class.forName("org.bukkit.Bukkit", false, instance.getClassLoader()).getMethod("getServer").invoke(null);
        assertNotNull(server, instance.getName() + " has no Bukkit server");
        return server;
    }

    private static int count(String text, String needle) {
        int n = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + 1)) n++;
        return n;
    }

    private static Set<String> listing(Path dir) throws IOException {
        try (var files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString()).collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
        }
    }

    private PatchedServer prepare() {
        Path cache = Path.of(System.getProperty("paperhive.it.cache", tmp.resolve("cache").toString()));
        return PaperclipCache.prepare(Path.of(System.getProperty("paperhive.it.paperJar")), cache);
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void waitForPort(int port, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
                return;
            } catch (IOException e) {
                Thread.sleep(250);
            }
        }
        fail("server didn't open port " + port + " within " + timeout);
    }

    private static void gc() throws InterruptedException {
        Leaks.gc();
    }

    private static void assertCollected(WeakReference<?> ref, String what) throws InterruptedException {
        Leaks.assertCollected(ref, what);
    }

}
