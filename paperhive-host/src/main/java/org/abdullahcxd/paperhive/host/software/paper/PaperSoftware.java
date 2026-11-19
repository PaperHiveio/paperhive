package org.abdullahcxd.paperhive.host.software.paper;

import lombok.extern.slf4j.Slf4j;
import org.abdullahcxd.paperhive.downloader.MCSoftware;
import org.abdullahcxd.paperhive.host.config.HiveConfig;
import org.abdullahcxd.paperhive.host.instance.ServerInstance;
import org.abdullahcxd.paperhive.host.software.PreparedServer;
import org.abdullahcxd.paperhive.host.software.ServerSoftware;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Paper: patched once with Paperclip, started with its flags pointed at the server's folder, booted when its
 * {@code MinecraftServer} says it's ready, stopped and given commands through Bukkit. Paperhive's Bukkit bridge
 * ({@code --add-plugin}) runs Hive plugins' server halves in it.
 * <p>
 * Every Bukkit server is reached by reflection (each has its own copy of Bukkit). A server that isn't Bukkit at all
 * (the test fakes) counts as booted right away and is stopped through its shutdown hooks.
 */
@Slf4j
public final class PaperSoftware extends ServerSoftware {

    private static final String BUKKIT = "org.bukkit.Bukkit";

    public PaperSoftware() {
        super(MCSoftware.PAPERMC);
    }

    @Override
    public @NotNull String displayName() {
        return "Paper";
    }

    @Override
    protected @NotNull PreparedServer prepare(@NotNull Path jar, @NotNull Path cache) {
        // own subfolder: PaperclipCache prunes every other entry of the folder it's given
        return PaperclipCache.prepare(jar, cache.resolve("paperclip"));
    }

    @Override
    public @NotNull List<String> defaultArgs(@NotNull Path root, @NotNull ServerInstance.Options options) {
        Path plugins = options.plugins() == null ? root.resolve("plugins") : options.plugins().toAbsolutePath().normalize();
        List<String> args = new ArrayList<>(List.of(
                "--nogui",
                // the host owns stdin and forwards commands (dispatchCommand); Paper's own console reader would
                // block on System.in forever and keep the instance alive after stop
                "--noconsole",
                "--universe", root.toString(),
                "--plugins", plugins.toString(),
                "--config", root.resolve("server.properties").toString(),
                "--bukkit-settings", root.resolve("bukkit.yml").toString(),
                "--spigot-settings", root.resolve("spigot.yml").toString(),
                "--commands-settings", root.resolve("commands.yml").toString(),
                "--paper-settings-directory", root.resolve("config").toString()));
        if (options.port() != null) args.addAll(List.of("--port", options.port().toString()));
        return args;
    }

    @Override
    public @NotNull List<String> configArgs(@NotNull HiveConfig.Server server) {
        List<String> args = new ArrayList<>();
        if (server.ip() != null) args.addAll(List.of("--host", server.ip()));
        // Paper's own option: the jars are shared, each plugin's data stays in this server's plugins folder
        for (Path dir : server.sharedPlugins()) args.addAll(List.of("--add-plugin-dir", dir.toAbsolutePath().normalize().toString()));
        return args;
    }

    @Override
    public @NotNull String bundledPlugin() {
        return "paperhive-bridge.jar";
    }

    @Contract("_, _ -> new")
    @Override
    public @NotNull @Unmodifiable List<String> loadPlugin(@NotNull Path pluginJar, @NotNull Path root) {
        return List.of("--add-plugin", pluginJar.toString()); // loaded from Paperhive's cache, nothing copied in
    }

    @Override
    public void prepareRoot(@NotNull Path root, @NotNull ServerInstance.Options options) throws IOException {
        Path properties = root.resolve("server.properties");
        if (options.port() != null && Files.notExists(properties)) Files.writeString(properties, "server-port=" + options.port() + "\n");
    }

    @Override
    public boolean isReady(@NotNull ClassLoader loader, @NotNull ServerInstance.Options options) {
        if (!isBukkit(loader)) return true; // no way to tell
        try {
            Object minecraft = minecraftServer(loader);
            return minecraft != null && Boolean.TRUE.equals(minecraft.getClass().getMethod("isReady").invoke(minecraft));
        } catch (ReflectiveOperationException | LinkageError e) {
            log.debug("Cannot tell whether a Paper server has booted", e);
            return false;
        }
    }

    @Override
    public boolean requestStop(@NotNull ClassLoader loader) {
        try {
            Class<?> bukkit = Class.forName(BUKKIT, true, loader);
            if (bukkit.getMethod("getServer").invoke(null) == null) return false;
            bukkit.getMethod("shutdown").invoke(null); // Paper then stops and calls System.exit itself
            return true;
        } catch (ReflectiveOperationException | LinkageError e) {
            log.debug("No Bukkit server to shut down", e);
            return false;
        }
    }

    @Override
    public boolean dispatchCommand(@NotNull ClassLoader loader, @NotNull String command) {
        try {
            Object minecraft = minecraftServer(loader);
            if (minecraft == null) return false; // still booting
            Object source = minecraft.getClass().getMethod("createCommandSourceStack").invoke(minecraft);
            for (Method m : minecraft.getClass().getMethods()) {
                // thread-safe: queued and run on the server thread, same path as Paper's own console
                if (m.getName().equals("handleConsoleInput") && m.getParameterCount() == 2) {
                    m.invoke(minecraft, command, source);
                    return true;
                }
            }
            log.warn("This Paper has no handleConsoleInput, cannot run '{}'", command);
        } catch (ReflectiveOperationException | LinkageError e) {
            log.warn("Could not run '{}'", command, e);
        }
        return false;
    }

    @Override
    public boolean runsServerHalves() {
        return true;
    }

    /** {@code Bukkit.getServer().getServer()}: the {@code MinecraftServer}, or null before Bukkit is up. */
    private static @Nullable Object minecraftServer(@NotNull ClassLoader loader) throws ReflectiveOperationException {
        Object craftServer = Class.forName(BUKKIT, true, loader).getMethod("getServer").invoke(null);
        return craftServer == null ? null : craftServer.getClass().getMethod("getServer").invoke(craftServer);
    }

    private static boolean isBukkit(@NotNull ClassLoader loader) {
        try {
            Class.forName(BUKKIT, false, loader);
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

}
