package org.abdullahcxd.paperhive.plugin;

import lombok.Getter;
import org.abdullahcxd.paperhive.api.Namespaced;
import org.abdullahcxd.paperhive.plugin.context.PluginContext;
import org.abdullahcxd.paperhive.plugin.context.ServerContext;
import org.abdullahcxd.paperhive.plugin.logger.PluginLogger;
import org.slf4j.Logger;

/**
 * A Hive plugin's host half: loaded once by Paperhive from the host's {@code plugins/} folder (not by Paper), for the
 * whole hive. It never touches Bukkit; a plugin that wants to work inside its servers like a Bukkit plugin adds a
 * server half (a {@code HiveBukkitPlugin} named in a {@code plugin.yml} in the same jar).
 * <p>
 * This plugin can be disabled by either:
 * <p>
 * - Specifying the plugin name in paperhive.yml DisabledHivePlugins on a server: no {@code onServerStart} and no
 *   server half for that server.
 * <p>
 * - Using {@link PluginManager} to disable the plugin with code after you run your plugin.
 * <p>
 * - The {@code plugin disable <name>} Hive command.
 */
@Getter
public abstract class PaperhivePlugin {

    private PaperhivePluginDescriptor pluginDescriptor;
    private ClassLoader classLoader;
    private PluginManager pluginManager;
    @Getter(lombok.AccessLevel.NONE)
    private volatile Logger logger;

    public abstract void onEnable(HiveContext<PluginContext> context);

    /** Its Hive commands are removed after this. */
    public void onDisable() {}

    /** A server it's enabled for is up ({@code RUNNING}). Runs on a Paperhive thread. */
    public void onServerStart(HiveContext<ServerContext> context) {}

    /**
     * A key in this plugin's namespace: {@code key("joins")} is {@code <plugin name>:joins}, the same key its server
     * half's {@code key("joins")} gives, so both ends name one channel, service or shared value.
     */
    public final Namespaced key(String value) {
        return Namespaced.forPlugin(pluginDescriptor.getName(), value);
    }

    /** Prefixes every message with {@code [<name>] }. */
    public Logger getLogger() {
        Logger l = logger;
        if (l == null) logger = l = new PluginLogger(this);
        return l;
    }

    /** Called by Paperhive once, right after constructing the plugin. */
    public final synchronized void initialize(PluginManager pluginManager, ClassLoader classLoader, PaperhivePluginDescriptor descriptor) {
        if (this.pluginDescriptor != null) throw new IllegalStateException("Already initialized");
        this.pluginDescriptor = descriptor;
        this.pluginManager = pluginManager;
        this.classLoader = classLoader;
    }
}
