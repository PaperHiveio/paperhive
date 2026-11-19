package org.abdullahcxd.paperhive.host.plugin;

import lombok.extern.slf4j.Slf4j;
import org.abdullahcxd.paperhive.api.HiveServer;
import org.abdullahcxd.paperhive.api.internal.BridgeHooks.ServerHalf;
import org.abdullahcxd.paperhive.host.config.HiveConfig;
import org.abdullahcxd.paperhive.plugin.HiveContext;
import org.abdullahcxd.paperhive.plugin.PaperhivePlugin;
import org.abdullahcxd.paperhive.plugin.PaperhivePluginDescriptor;
import org.abdullahcxd.paperhive.plugin.PluginManager;
import org.abdullahcxd.paperhive.plugin.command.HiveCommand;
import org.abdullahcxd.paperhive.plugin.command.HiveCommandRegistry;
import org.abdullahcxd.paperhive.plugin.context.PluginContext;
import org.abdullahcxd.paperhive.plugin.context.ServerContext;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Loads Hive plugins (jars with a {@code paperhive.yml}) on the host, each with its own class loader, and
 * decides per server which get {@code onServerStart} and whose server half ({@code server-main}) that server's bridge
 * loads. A plugin's Hive commands, event listeners and subscriptions are removed when it's disabled.
 */
@Slf4j
public final class HivePluginManager implements PluginManager {

    public static final String DESCRIPTOR = "paperhive.yml";

    private static final YAMLMapper YAML = YAMLMapper.builder().build();

    private volatile HiveCommandRegistry commands; // from enableAll
    /** Where {@code onServerStart} runs (the Hive's event thread, in order with its events). */
    private final Consumer<Runnable> events;
    /** Drops what a disabled plugin left registered with the Hive (by its loader). */
    private final Consumer<ClassLoader> release;
    private final Map<String, Loaded> plugins = new LinkedHashMap<>(); // by lower-case name

    private static final class Loaded {
        final PaperhivePlugin plugin;
        final Path jar;
        final List<HiveCommand> commands = new CopyOnWriteArrayList<>();
        volatile boolean enabled;

        Loaded(PaperhivePlugin plugin, Path jar) {
            this.plugin = plugin;
            this.jar = jar;
        }

        boolean serverHalf() {
            return plugin.getPluginDescriptor().getServerMain() != null;
        }
    }

    public HivePluginManager(Consumer<Runnable> events, Consumer<ClassLoader> release) {
        this.events = events;
        this.release = release;
    }

    /** One per Hive plugin, so the Hive can tell what a plugin made. */
    private static final class PluginLoader extends URLClassLoader {
        static {
            registerAsParallelCapable();
        }

        PluginLoader(String name, URL jar) {
            super("hive-plugin-" + name, new URL[]{jar}, PaperhivePlugin.class.getClassLoader());
        }
    }

    public static boolean isPluginLoader(@NotNull ClassLoader loader) {
        return loader instanceof PluginLoader;
    }

    /** Every jar in {@code directory} (created if missing); one that fails is logged and skipped. */
    @Override
    public @NotNull List<PaperhivePlugin> loadPlugins(@NotNull Path directory) {
        List<PaperhivePlugin> loaded = new ArrayList<>();
        try {
            Files.createDirectories(directory);
            List<Path> jars;
            try (Stream<Path> list = Files.list(directory)) {
                jars = list.filter(p -> p.getFileName().toString().endsWith(".jar")).sorted().toList();
            }
            for (Path jar : jars) {
                try {
                    loaded.add(loadPlugin(jar));
                } catch (RuntimeException e) {
                    log.error("Cannot load Hive plugin {}: {}", jar, e.getMessage(), e.getCause());
                }
            }
        } catch (IOException e) {
            log.error("Cannot read the Hive plugins folder {}", directory, e);
        }
        return loaded;
    }

    /** Loads it, not yet enabled. @throws IllegalArgumentException if it isn't a valid Hive plugin or the name is taken */
    @Override
    public synchronized @NotNull PaperhivePlugin loadPlugin(@NotNull Path jarFile) {
        Path jar = jarFile.toAbsolutePath().normalize();
        PaperhivePluginDescriptor descriptor;
        try (JarFile file = new JarFile(jar.toFile())) {
            JsonNode yml = read(file, DESCRIPTOR);
            if (yml == null) throw new IllegalArgumentException("no " + DESCRIPTOR + " in it");
            descriptor = new PaperhivePluginDescriptor(required(yml, "name"), optional(yml, "description"),
                    required(yml, "version"), required(yml, "main"), optional(yml, "server-main"));
            String serverMain = descriptor.getServerMain();
            if (serverMain != null && file.getJarEntry(serverMain.replace('.', '/') + ".class") == null) {
                throw new IllegalArgumentException(DESCRIPTOR + ": server-main " + serverMain + " isn't in the jar");
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + jar, e);
        }
        if (findPlugin(descriptor.getName()) != null) throw new IllegalArgumentException("a Hive plugin named " + descriptor.getName() + " is already loaded");

        URLClassLoader loader = new PluginLoader(descriptor.getName(), url(jar));
        PaperhivePlugin plugin;
        try {
            Class<?> main = Class.forName(descriptor.getMain(), true, loader);
            if (!PaperhivePlugin.class.isAssignableFrom(main)) throw new IllegalArgumentException(descriptor.getMain() + " doesn't extend PaperhivePlugin");
            plugin = (PaperhivePlugin) main.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            close(loader);
            throw e instanceof IllegalArgumentException iae ? iae : new IllegalArgumentException("cannot create " + descriptor.getMain() + ": " + e, e);
        }
        plugin.initialize(this, loader, descriptor);
        plugins.put(key(descriptor.getName()), new Loaded(plugin, jar));
        return plugin;
    }

    /** @param commands where their Hive commands go (from now on, also for plugins enabled later) */
    public synchronized void enableAll(@NotNull HiveCommandRegistry commands) {
        this.commands = commands;
        for (Loaded l : List.copyOf(plugins.values())) enablePlugin(l.plugin);
    }

    public synchronized void disableAll() {
        for (Loaded l : List.copyOf(plugins.values())) disablePlugin(l.plugin);
    }

    /** Disables and unloads every plugin, closing their jars. */
    public synchronized void close() {
        disableAll();
        for (Loaded l : plugins.values()) close((URLClassLoader) l.plugin.getClassLoader());
        plugins.clear();
    }

    @Override
    public void enablePlugin(@NotNull String name) {
        enablePlugin(require(name));
    }

    @Override
    public synchronized void enablePlugin(@NotNull PaperhivePlugin plugin) {
        Loaded l = loaded(plugin);
        if (l.enabled) return;
        try {
            plugin.onEnable(new HiveContext<>(new PluginContext(new OwnedRegistry(l))));
            l.enabled = true;
            log.info("Enabled Hive plugin {} {}{}", name(plugin), plugin.getPluginDescriptor().getVersion(), l.serverHalf() ? " (with a server half)" : "");
        } catch (Throwable e) {
            log.error("Cannot enable Hive plugin {}", name(plugin), e);
            unregisterCommands(l);
        }
    }

    @Override
    public void disablePlugin(@NotNull String name) {
        disablePlugin(require(name));
    }

    @Override
    public synchronized void disablePlugin(@NotNull PaperhivePlugin plugin) {
        Loaded l = loaded(plugin);
        if (!l.enabled) return;
        l.enabled = false;
        try {
            plugin.onDisable();
        } catch (Throwable e) {
            log.error("Hive plugin {} failed to disable", name(plugin), e);
        }
        unregisterCommands(l);
        release.accept(plugin.getClassLoader());
        log.info("Disabled Hive plugin {}", name(plugin));
    }

    @Override
    public synchronized @Nullable PaperhivePlugin findPlugin(@NotNull String name) {
        Loaded l = plugins.get(key(name));
        return l == null ? null : l.plugin;
    }

    public synchronized @NotNull @Unmodifiable List<PaperhivePlugin> plugins() {
        return plugins.values().stream().map(l -> l.plugin).toList();
    }

    public synchronized boolean isEnabled(@NotNull PaperhivePlugin plugin) {
        return loaded(plugin).enabled;
    }

    public synchronized boolean hasServerHalf(@NotNull PaperhivePlugin plugin) {
        return loaded(plugin).serverHalf();
    }

    /** The server halves {@code server} loads: enabled plugins with a {@code server-main} that it doesn't disable. */
    public synchronized @NotNull List<ServerHalf> serverHalves(HiveConfig.@NotNull Server server) {
        List<ServerHalf> halves = new ArrayList<>();
        for (Loaded l : plugins.values()) {
            PaperhivePluginDescriptor d = l.plugin.getPluginDescriptor();
            if (l.enabled && l.serverHalf() && enabledFor(server, l)) halves.add(new ServerHalf(d.getName(), d.getVersion(), l.jar, d.getServerMain()));
        }
        return halves;
    }

    /** {@code onServerStart} of every enabled plugin that {@code server} doesn't disable, on the event thread. */
    public synchronized void serverStarted(HiveConfig.@NotNull Server config, @NotNull HiveServer server) {
        ServerContext context = new ServerContext(config.name(), serverIp(server.getRoot()), config.port(), server);
        for (Loaded l : plugins.values()) {
            if (!l.enabled || !enabledFor(config, l)) continue;
            events.accept(() -> l.plugin.onServerStart(new HiveContext<>(context)));
        }
    }

    private static boolean enabledFor(HiveConfig.@NotNull Server server, @NotNull Loaded l) {
        String name = name(l.plugin);
        return server.disabledHivePlugins().stream().noneMatch(name::equalsIgnoreCase);
    }

    /** {@code server-ip} from its {@code server.properties}; empty (all addresses) if unset. */
    private static String serverIp(@NotNull Path root) {
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(root.resolve("server.properties"))) {
            properties.load(in);
        } catch (IOException e) {
            return "";
        }
        return properties.getProperty("server-ip", "");
    }

    private void unregisterCommands(@NotNull Loaded l) {
        for (HiveCommand c : l.commands) commands.unregister(c);
        l.commands.clear();
    }

    private @NotNull Loaded loaded(PaperhivePlugin plugin) {
        Loaded l = plugins.get(key(name(plugin)));
        if (l == null || l.plugin != plugin) throw new IllegalArgumentException(plugin + " isn't loaded here");
        return l;
    }

    private @NotNull PaperhivePlugin require(String name) {
        PaperhivePlugin plugin = findPlugin(name);
        if (plugin == null) throw new IllegalArgumentException("no Hive plugin named " + name);
        return plugin;
    }

    private static String name(@NotNull PaperhivePlugin plugin) {
        return plugin.getPluginDescriptor().getName();
    }

    @Contract(pure = true)
    private static @NotNull String key(@NotNull String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static @Nullable JsonNode read(@NotNull JarFile file, String entry) throws IOException {
        JarEntry e = file.getJarEntry(entry);
        if (e == null) return null;
        try (InputStream in = file.getInputStream(e)) {
            return YAML.readTree(in);
        }
    }

    private static @NotNull String required(JsonNode yml, String key) {
        String value = optional(yml, key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(DESCRIPTOR + ": " + key + " is missing");
        return value;
    }

    private static @Nullable String optional(@NotNull JsonNode yml, String key) {
        JsonNode value = yml.get(key);
        return value == null || !value.isValueNode() ? null : value.asString();
    }

    private static @NotNull URL url(@NotNull Path jar) {
        try {
            return jar.toUri().toURL();
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static void close(@NotNull URLClassLoader loader) {
        try {
            loader.close();
        } catch (IOException ignored) {
            // nothing else holds it
        }
    }

    /** The registry a plugin gets: remembers its commands, so disabling it removes them. */
    private final class OwnedRegistry implements HiveCommandRegistry {
        private final Loaded owner;

        private OwnedRegistry(Loaded owner) {
            this.owner = owner;
        }

        @Override
        public void register(@NotNull HiveCommand command) {
            commands.register(command);
            owner.commands.add(command);
        }

        @Override
        public void unregister(@NotNull HiveCommand command) {
            commands.unregister(command);
            owner.commands.remove(command);
        }

        @Override
        public @Nullable HiveCommand find(@NotNull String nameOrAlias) {
            return commands.find(nameOrAlias);
        }

        @Override
        public @NotNull Collection<HiveCommand> commands() {
            return commands.commands();
        }
    }

}
