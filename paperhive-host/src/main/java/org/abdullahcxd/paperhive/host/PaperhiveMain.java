package org.abdullahcxd.paperhive.host;

import org.abdullahcxd.paperhive.downloader.PaperhiveDownloader;
import org.abdullahcxd.paperhive.api.HiveServer;
import org.abdullahcxd.paperhive.api.Paperhive;
import org.abdullahcxd.paperhive.host.command.CommandRegistry;
import org.abdullahcxd.paperhive.host.command.PluginCommand;
import org.abdullahcxd.paperhive.host.plugin.HivePluginManager;
import org.abdullahcxd.paperhive.host.config.HiveConfig;
import org.abdullahcxd.paperhive.host.instance.ApiGateLoader;
import org.abdullahcxd.paperhive.host.instance.ServerInstance;
import org.abdullahcxd.paperhive.host.paperclip.PaperclipCache;
import org.abdullahcxd.paperhive.host.paperclip.PatchedServer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

public final class PaperhiveMain {

    private PaperhiveMain() {}

    // lazy: `hive ...` clients must not start logging, which would roll the running host's logs/latest.log
    private static final class Log {
        static final Logger LOG = LoggerFactory.getLogger(PaperhiveMain.class);
    }

    /**
     * {@code hive <command>} sends the command to the host running in this folder. Otherwise this starts the host;
     * any arguments are passed to every server as-is instead of Paperhive's defaults (e.g. {@code --help}).
     */
    public static void main(String @NotNull [] args) throws InterruptedException {
        if (args.length > 0 && args[0].equals("hive")) {
            System.exit(ControlSocket.send(String.join(" ", Arrays.copyOfRange(args, 1, args.length)), System.out, System.err));
        }
        HiveConsole console;
        try {
            console = HiveConsole.open(); // first: logging must print above its prompt
        } catch (IOException e) {
            System.err.println("Cannot open the console: " + e);
            System.exit(1);
            return;
        }
        // the version is in the jar's manifest; running from classes (an IDE, `run`) has none
        String version = PaperhiveMain.class.getPackage().getImplementationVersion();
        console.banner(version == null ? "dev" : version);
        Logger log = Log.LOG;
        Path configFile = Path.of(HiveConfig.FILE_NAME);
        HiveConfig config;
        try {
            config = HiveConfig.load(configFile);
        } catch (HiveConfig.HiveConfigException e) {
            log.error(e.getMessage());
            System.exit(1);
            return;
        }
        HiveConfig.Host host = config.host();
        Path paperJar = PaperhiveDownloader.fetch(config.software(), config.version(), host.cache(), host.mirror());
        // not closed: the instances run from it for the rest of the JVM's life, and the OS drops the lock on exit
        PatchedServer server = PaperclipCache.prepare(paperJar, host.paperclipCache());

        HostHive hive = new HostHive();
        Paperhive.setHive(hive);
        HivePluginManager plugins = new HivePluginManager(hive::deliver, hive::release);
        plugins.loadPlugins(host.plugins());
        // jars loaded from where they are built (the Gradle plugin's runHive), whatever Host.Plugins says
        for (String jar : System.getProperty("paperhive.devPlugins", "").split(java.io.File.pathSeparator)) {
            if (jar.isBlank()) continue;
            try {
                plugins.loadPlugin(Path.of(jar));
            } catch (RuntimeException e) {
                log.error("Cannot load Hive plugin {}: {}", jar, e.getMessage());
            }
        }
        Path bridge = extractBridge(host.cache(), log);

        ApiGateLoader gate = new ApiGateLoader(PaperhiveMain.class.getClassLoader());
        // the bridge loads the Hive plugins' server halves itself (it asks the Hive which)
        List<String> bridgeArgs = bridge == null ? List.of() : List.of("--add-plugin", bridge.toString());
        ServerManager servers = new ServerManager(configFile, config, List.of(args), entry -> bridgeArgs, entry -> {
            ServerInstance instance = new ServerInstance(entry.name(), entry.root(), server, gate, entry.options(host));
            instance.addStateListener((i, from, to) -> log.info("Instance {}: {} -> {}", i.getName(), from, to));
            watch(instance, hive, plugins); // also for servers made later (create, set)
            return instance;
        });
        hive.init(servers, plugins::serverHalves);
        servers.setStartGuard(hive::allowStart);
        CommandRegistry commands = CommandRegistry.withBuiltins(servers, name -> {
            HiveServer handle = hive.getServer(name);
            return handle == null ? null : handle.getStats();
        });
        commands.register(new PluginCommand(plugins));
        plugins.enableAll(commands);
        try {
            ControlSocket.serve(commands, servers);
        } catch (IOException e) {
            log.error("Cannot open the control socket {}: {}", ControlSocket.PATH.toAbsolutePath(), e.getMessage());
            System.exit(1);
        }

        // Ctrl+C / host shutdown: stop the instances properly (saves worlds) instead of just dying, all at once
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            servers.instances().parallelStream().forEach(i -> i.stop(servers.getStopTimeout()));
            plugins.disableAll();
        }, "paperhive-shutdown"));
        // one heap for every server: warn before it runs out, and keep evidence if it does
        MemoryWatch.install(host.memoryWarning() / 100.0, () -> servers.instances().stream().filter(ServerInstance::isActive).map(ServerInstance::getName).toList(), log::warn);
        MemoryWatch.dumpHeapOnOutOfMemory(Path.of("logs"));
        log.info("Heap limit {} MB, shared by every server", Runtime.getRuntime().maxMemory() >> 20);
        servers.startAutostart();
        if (servers.instances().stream().noneMatch(ServerInstance::isActive)) {
            log.info("No server has Autostart: true; start one with `start <name>`");
        }
        log.info("Type `help` for the Paperhive commands");
        console.start(commands, servers);
        // the host lives until `shutdown` or Ctrl+C, also while no server runs
        Thread.currentThread().join();
    }

    /** Feeds an instance's state changes to the Hive API and to Hive plugins' {@code onServerStart}. */
    private static void watch(@NotNull ServerInstance instance, HostHive hive, HivePluginManager plugins) {
        instance.addStateListener((i, from, to) -> {
            hive.stateChanged(i, from, to);
            if (to == ServerInstance.State.RUNNING) plugins.serverStarted(hive.config(i.getName()), hive.handle(i.getName()));
        });
    }

    /** The bridge plugin from inside this jar, written to the cache when it changed; null if this build has none. */
    private static @Nullable Path extractBridge(Path cache, Logger log) {
        try (InputStream in = PaperhiveMain.class.getResourceAsStream("/paperhive-bridge.jar")) {
            if (in == null) {
                log.warn("This build has no paperhive-bridge.jar; servers run without Paperhive's plugin (no Hive plugin server halves)");
                return null;
            }
            byte[] bytes = in.readAllBytes();
            Path target = cache.resolve("paperhive-bridge.jar").toAbsolutePath();
            if (!Files.exists(target) || !Arrays.equals(Files.readAllBytes(target), bytes)) {
                Files.createDirectories(target.getParent());
                Files.write(target, bytes);
            }
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write the bridge plugin to " + cache, e);
        }
    }

}
