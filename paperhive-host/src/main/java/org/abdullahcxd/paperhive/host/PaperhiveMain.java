package org.abdullahcxd.paperhive.host;

import org.abdullahcxd.paperhive.host.console.ControlSocket;
import org.abdullahcxd.paperhive.host.console.HiveConsole;
import org.abdullahcxd.paperhive.host.hive.HostHive;
import org.abdullahcxd.paperhive.host.server.MemoryWatch;
import org.abdullahcxd.paperhive.host.server.ServerManager;
import org.abdullahcxd.paperhive.api.HiveServer;
import org.abdullahcxd.paperhive.api.Paperhive;
import org.abdullahcxd.paperhive.host.command.CommandRegistry;
import org.abdullahcxd.paperhive.host.command.PluginCommand;
import org.abdullahcxd.paperhive.host.plugin.HivePluginManager;
import org.abdullahcxd.paperhive.host.config.HiveConfig;
import org.abdullahcxd.paperhive.host.instance.ApiGateLoader;
import org.abdullahcxd.paperhive.host.software.SoftwareInstalls;
import org.abdullahcxd.paperhive.host.instance.ServerInstance;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.abdullahcxd.paperhive.plugin.command.ConsoleText.*;

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
        // each server's software and version, downloaded and prepared once per version
        SoftwareInstalls installs = new SoftwareInstalls(host.cache(), host.mirror());

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

        ApiGateLoader gate = new ApiGateLoader(PaperhiveMain.class.getClassLoader());
        // Paperhive's plugin goes into every server at each start (in Paper, the bridge loads the Hive plugins' server
        // halves itself: it asks the Hive which)
        ServerManager servers;
        try {
            servers = new ServerManager(configFile, config, List.of(args), installs::pluginArgs, entry -> {
                ServerInstance instance = new ServerInstance(entry.name(), entry.root(), entry.serverSoftware(),
                        installs.prepared(entry), gate, entry.options(host));
                instance.addStateListener((i, from, to) -> log.info("{} {} {} {}", name(i.getName()), state(from.name()), muted("->"), state(to.name())));
                watch(instance, hive, plugins); // also for servers made later (create, set)
                return instance;
            });
        } catch (RuntimeException e) {
            log.error("Cannot set up the servers: {}", e.getMessage(), e);
            System.exit(1);
            return;
        }
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
        log.info("Type {} for the Paperhive commands", command("help"));
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

}
