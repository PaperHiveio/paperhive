package org.abdullahcxd.paperhive.host.instance;

import org.jetbrains.annotations.NotNull;

/**
 * Parent of every {@link InstanceClassLoader}. Instances see the JDK (platform loader) plus every
 * Paperhive class ({@code org.abdullahcxd.paperhive.*}: api, host, plugin, ...) from the host, so plugins
 * inside an instance can use Paper and Paperhive together. The host's third-party libraries
 * (Log4j, SLF4J, Jackson, ASM, ...) stay hidden, so they never clash with Paper's own copies.
 */
public final class ApiGateLoader extends ClassLoader {

    static final String SHARED_PACKAGE = "org.abdullahcxd.paperhive.";
    // Paperhive's Paper plugin: extends JavaPlugin, so each server loads its own copy (see docs/architecture.md)
    static final String BRIDGE_PACKAGE = "org.abdullahcxd.paperhive.bridge.";

    static boolean isShared(@NotNull String className) {
        return className.startsWith(SHARED_PACKAGE) && !className.startsWith(BRIDGE_PACKAGE);
    }

    static {
        registerAsParallelCapable();
    }

    private final ClassLoader hostLoader;

    public ApiGateLoader(ClassLoader hostLoader) {
        super("paperhive-gate", ClassLoader.getPlatformClassLoader());
        this.hostLoader = hostLoader;
    }

    @Override
    protected Class<?> findClass(@NotNull String name) throws ClassNotFoundException {
        // only reached when the platform loader doesn't have it
        if (isShared(name)) return hostLoader.loadClass(name);
        throw new ClassNotFoundException(name);
    }

}
