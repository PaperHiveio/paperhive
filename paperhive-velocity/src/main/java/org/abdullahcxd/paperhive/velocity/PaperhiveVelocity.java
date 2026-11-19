package org.abdullahcxd.paperhive.velocity;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import com.google.inject.Inject;
import org.abdullahcxd.paperhive.api.HiveServer;
import org.abdullahcxd.paperhive.api.Paperhive;
import org.abdullahcxd.paperhive.api.internal.BridgeHooks;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Registers the servers of a Paperhive host on the same machine with this proxy while they're {@code RUNNING}, and
 * unregisters them when they stop. Asks the host through its control socket ({@code paperhive.sock}) every few
 * seconds. Players still need a way in: a {@code try} entry or forced host in {@code velocity.toml} naming the server.
 */
@Plugin(id = "paperhive", name = "Paperhive", version = BuildInfo.VERSION,
        description = "Registers a Paperhive host's servers with this proxy", authors = {"Paperhive"})
public final class PaperhiveVelocity {

    private static final String DEFAULT_CONFIG = """
            # The Paperhive host's control socket: paperhive.sock in the folder the host runs in
            socket=../paperhive/paperhive.sock
            # Where this proxy reaches the hive's servers (they run on the host's machine)
            host=127.0.0.1
            # How often to ask the host, in seconds
            interval=2
            """;

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private Path socket;
    private ServerSync sync;
    private boolean reachable = true;
    // inside a hive: the servers straight from the Hive API (same JVM), instead of the host's socket
    private @Nullable Supplier<List<String>> hiveListing;

    @Inject
    public PaperhiveVelocity(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(@NotNull ProxyInitializeEvent event) throws IOException {
        Path file = dataDirectory.resolve("config.properties");
        if (Files.notExists(file)) {
            Files.createDirectories(dataDirectory);
            Files.writeString(file, DEFAULT_CONFIG);
        }
        Properties config = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            config.load(in);
        }
        socket = Path.of(config.getProperty("socket", "paperhive.sock")).toAbsolutePath().normalize();
        sync = new ServerSync(new ServerSync.Proxy() {
            @Override
            public boolean has(@NotNull String name) {
                return proxy.getServer(name).isPresent();
            }

            @Override
            public void register(@NotNull String name, @NotNull InetSocketAddress address) {
                proxy.registerServer(new ServerInfo(name, new InetSocketAddress(address.getHostString(), address.getPort())));
            }

            @Override
            public void unregister(@NotNull String name) {
                proxy.getServer(name).ifPresent(s -> proxy.unregisterServer(s.getServerInfo()));
            }
        }, config.getProperty("host", "127.0.0.1"));
        joinHive();
        long interval = Long.parseLong(config.getProperty("interval", "2"));
        proxy.getScheduler().buildTask(this, this::poll).repeat(interval, TimeUnit.SECONDS).schedule();
        if (hiveListing == null) logger.info("Following the Paperhive host at {}", socket);
    }

    /**
     * Inside a Paperhive hive (the proxy is one of its servers): never register this proxy with itself, and run the
     * console commands Paperhive sends it ({@code <proxy> <command>}, {@code attach}). Outside one there's no Paperhive
     * API to find, and nothing changes.
     */
    private void joinHive() {
        try {
            HiveServer self = Paperhive.getHive().getCurrentServer();
            if (self == null) return;
            sync.setSelf(self.getName());
            hiveListing = () -> Paperhive.getHive().getServers().stream()
                    .map(s -> s.getName() + "\t" + s.getState() + "\t" + s.getPort() + "\t" + s.getSoftware())
                    .toList();
            ((BridgeHooks) Paperhive.getHive()).consoleEnabled(command -> {
                proxy.getCommandManager().executeAsync(proxy.getConsoleCommandSource(), command);
                return true;
            });
            logger.info("Running inside the hive as {}; its console commands reach this proxy", self.getName());
        } catch (LinkageError | IllegalStateException standalone) {
            // not in a hive: the API isn't there (NoClassDefFoundError) or not set up
        }
    }

    private void poll() {
        List<String> changes;
        try {
            Supplier<List<String>> listing = hiveListing;
            changes = sync.sync(listing != null ? listing.get() : query(socket, "list --tsv"));
            if (!reachable) logger.info("Reached the Paperhive host at {} again", socket);
            reachable = true;
        } catch (IOException e) {
            if (reachable) logger.warn("Cannot reach the Paperhive host at {} ({}); its servers are unregistered until it's back", socket, e.getMessage());
            reachable = false;
            changes = sync.clear();
        }
        changes.forEach(logger::info);
    }

    /** One command through the host's control socket; its reply lines. */
    static @NonNull List<String> query(Path socket, String command) throws IOException {
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.connect(UnixDomainSocketAddress.of(socket));
            Writer out = Channels.newWriter(channel, StandardCharsets.UTF_8);
            out.write(command + "\n");
            out.flush();
            channel.shutdownOutput();
            BufferedReader in = new BufferedReader(new InputStreamReader(Channels.newInputStream(channel), StandardCharsets.UTF_8));
            List<String> lines = new ArrayList<>();
            for (String line; (line = in.readLine()) != null; ) lines.add(line);
            return lines;
        }
    }

}
