package org.abdullahcxd.paperhive.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.options.Option;
import org.gradle.work.DisableCachingByDefault;
import org.jspecify.annotations.NonNull;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * {@code ./gradlew createHivePlugin [--name=MyPlugin] [--package=com.example.myplugin]}: a host half, a server half
 * and the {@code paperhive.yml} naming them, ready to build and {@code runHive}. Files that exist are left alone.
 */
@DisableCachingByDefault(because = "writes into the project's sources")
public abstract class CreateHivePlugin extends DefaultTask {

    private static final Pattern PACKAGE = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z_][a-zA-Z0-9_]*)*");

    @Input
    @Option(option = "name", description = "The plugin's name (default: the project's name)")
    public abstract Property<String> getPluginName();

    @Input
    @Option(option = "package", description = "The Java package (default: <group>.<name>)")
    public abstract Property<String> getPackageName();

    @Internal
    public abstract DirectoryProperty getJavaDirectory();

    @Internal
    public abstract DirectoryProperty getResourcesDirectory();

    @TaskAction
    public void create() {
        String name = getPluginName().get();
        String pkg = getPackageName().get();
        if (!PACKAGE.matcher(pkg).matches()) throw new GradleException("Not a Java package name: " + pkg);
        String base = className(name);
        Path sources = getJavaDirectory().get().getAsFile().toPath().resolve(pkg.replace('.', '/'));

        write(sources.resolve(base + "HivePlugin.java"), hostHalf(pkg, base));
        write(sources.resolve(base + "ServerPlugin.java"), serverHalf(pkg, base));
        write(getResourcesDirectory().get().getAsFile().toPath().resolve("paperhive.yml"), """
                name: %s
                version: 1.0.0
                main: %s.%sHivePlugin
                # the server half: every server the plugin is enabled for loads it (no plugin.yml needed)
                server-main: %s.%sServerPlugin
                """.formatted(name, pkg, base, pkg, base));
        getLogger().lifecycle("Next: ./gradlew runHive (builds the plugin and starts a Paperhive host with it in run/)");
    }

    private void write(Path file, String content) {
        if (Files.exists(file)) {
            getLogger().lifecycle("Kept {} (it exists)", file);
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content);
            getLogger().lifecycle("Wrote {}", file);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + file, e);
        }
    }

    /** {@code my-cool_plugin} -> {@code MyCoolPlugin}; a name without letters becomes {@code Hive}. */
    static @NonNull String className(@NonNull String name) {
        StringBuilder out = new StringBuilder();
        for (String part : name.split("[^A-Za-z0-9]+")) {
            if (part.isEmpty()) continue;
            out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        if (out.isEmpty() || !Character.isJavaIdentifierStart(out.charAt(0))) out.insert(0, "Hive");
        return out.toString();
    }

    /** {@code My-Plugin} -> {@code myplugin}. */
    static @NonNull String packageSegment(@NonNull String name) {
        String segment = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return segment.isEmpty() || !Character.isLetter(segment.charAt(0)) ? "hive" + segment : segment;
    }

    private static @NonNull String hostHalf(String pkg, String base) {
        return """
                package %1$s;

                import org.abdullahcxd.paperhive.api.Paperhive;
                import org.abdullahcxd.paperhive.api.event.Subscribe;
                import org.abdullahcxd.paperhive.api.event.server.ServerStartedEvent;
                import org.abdullahcxd.paperhive.plugin.HiveContext;
                import org.abdullahcxd.paperhive.plugin.PaperhivePlugin;
                import org.abdullahcxd.paperhive.plugin.context.PluginContext;
                import org.jetbrains.annotations.NotNull;

                /** The host half: loaded once, for the whole hive. Never touches Bukkit. */
                public final class %2$sHivePlugin extends PaperhivePlugin {

                    @Override
                    public void onEnable(@NotNull HiveContext<PluginContext> context) {
                        Paperhive.getHive().getEventBus().register(this);
                        Paperhive.getHive().subscribe("%3$s", (from, channel, message) ->
                                getLogger().info("{} says: {}", from == null ? "the host" : from.getName(), message));
                    }

                    @Subscribe
                    private void onStarted(@NotNull ServerStartedEvent event) {
                        getLogger().info("{} is up", event.getServer().getName());
                    }

                    @Override
                    public void onDisable() {
                    }

                }
                """.formatted(pkg, base, packageSegment(base));
    }

    private static @NonNull String serverHalf(String pkg, String base) {
        return """
                package %1$s;

                import org.abdullahcxd.paperhive.bridge.HiveBukkitPlugin;
                import org.bukkit.event.EventHandler;
                import org.bukkit.event.Listener;
                import org.bukkit.event.player.PlayerJoinEvent;
                import org.jetbrains.annotations.NotNull;

                /** The server half: loaded in each server, with Bukkit and the hive at hand. */
                public final class %2$sServerPlugin extends HiveBukkitPlugin implements Listener {

                    @Override
                    public void onEnable() {
                        registerListener(this);
                    }

                    @EventHandler
                    public void onJoin(@NotNull PlayerJoinEvent event) {
                        // the host half hears this on its "%3$s" channel
                        getHive().publish("%3$s", event.getPlayer().getName() + " joined " + getHiveServer().getName());
                    }

                }
                """.formatted(pkg, base, packageSegment(base));
    }

}
