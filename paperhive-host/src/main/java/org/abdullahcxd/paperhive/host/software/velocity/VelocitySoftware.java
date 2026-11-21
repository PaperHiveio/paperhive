package org.abdullahcxd.paperhive.host.software.velocity;

import org.abdullahcxd.paperhive.downloader.MCSoftware;
import org.abdullahcxd.paperhive.host.instance.ServerInstance;
import org.abdullahcxd.paperhive.host.software.ClassPatch;
import org.abdullahcxd.paperhive.host.software.PreparedServer;
import org.abdullahcxd.paperhive.host.software.ServerSoftware;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;

/**
 * A Velocity proxy inside the hive. Its jar runs as it is (no Paperclip); its port comes from {@code paperhive.yml}
 * ({@code --port}), the rest from its own {@code velocity.toml}. It counts as booted once its port accepts
 * connections and stops through its own shutdown hook (which shuts the proxy down properly).
 * <p>
 * Before each start, Paperhive's Velocity plugin is put into its {@code plugins/} with a config pointing at this
 * hive, so the proxy registers the hive's servers by itself; inside the hive it also takes the proxy's console
 * commands from Paperhive. A proxy has no Bukkit, so no Hive plugin server halves, and no {@code IP},
 * {@code Plugins}, {@code SharedPlugins} or {@code DisabledHivePlugins} (Velocity takes its bind address and plugins
 * folder from {@code velocity.toml} and its own folder).
 */
public final class VelocitySoftware extends ServerSoftware {

    public VelocitySoftware() {
        super(MCSoftware.VELOCITYMC);
    }

    @Override
    public @NotNull String displayName() {
        return "Velocity";
    }

    @Override
    protected @NotNull PreparedServer prepare(@NotNull Path jar, @NotNull Path cache) {
        try (JarFile file = new JarFile(jar.toFile())) {
            String main = file.getManifest() == null ? null : file.getManifest().getMainAttributes().getValue("Main-Class");
            if (main == null) throw new IllegalStateException(jar + " has no Main-Class; is it a Velocity jar?");
            return new PreparedServer(jar, List.of(), main, () -> {});
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + jar, e);
        }
    }

    @Override
    public @NotNull @Unmodifiable List<String> defaultArgs(@NotNull Path root, @NotNull ServerInstance.Options options) {
        return options.port() == null ? List.of() : List.of("--port", options.port().toString());
    }

    @Override
    public @NotNull @Unmodifiable Set<String> unsupportedKeys() {
        return Set.of("IP", "Plugins", "SharedPlugins", "DisabledHivePlugins", "EULA");
    }

    /**
     * Velocity's console (TerminalConsoleAppender) would open the machine's real terminal through JLine and block its
     * main thread reading it, competing with Paperhive's prompt and outliving a stop. Without JLine it reads the
     * server's standard input, which Paperhive owns: commands arrive through {@code <proxy> <command>} instead.
     */
    @Override
    public @NotNull @Unmodifiable Map<String, String> systemProperties() {
        return Map.of("terminal.jline", "false");
    }

    @Override
    public @NotNull String bundledPlugin() {
        return "paperhive-velocity.jar";
    }

    /**
     * Copies the plugin in (when it changed). It needs no config here: inside the hive it reads the Hive API, which
     * lives in the same JVM, instead of the host's control socket.
     */
    @Override
    public @NotNull @Unmodifiable List<String> loadPlugin(@NotNull Path pluginJar, @NotNull Path root) throws IOException {
        Path plugins = root.resolve("plugins");
        Files.createDirectories(plugins);
        Path target = plugins.resolve("paperhive-velocity.jar");
        byte[] bytes = Files.readAllBytes(pluginJar);
        if (Files.notExists(target) || !Arrays.equals(Files.readAllBytes(target), bytes)) Files.write(target, bytes);
        return List.of();
    }

    @Contract(" -> new")
    @Override
    public @NotNull @Unmodifiable List<ClassPatch> patches() {
        return List.of(new VelocityShutdownPatch());
    }

    @Override
    public boolean isReady(@NotNull ClassLoader loader, @NotNull ServerInstance.Options options) {
        if (options.port() == null) return true; // no way to tell
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", options.port()), 200);
            return true;
        } catch (IOException notYet) {
            return false;
        }
    }

}
