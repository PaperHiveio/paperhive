package org.abdullahcxd.paperhive.host.software;

import org.abdullahcxd.paperhive.downloader.MCSoftware;
import org.abdullahcxd.paperhive.downloader.MCVersion;
import org.abdullahcxd.paperhive.host.config.HiveConfig;
import org.abdullahcxd.paperhive.host.instance.ServerInstance;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Everything Paperhive does differently per server software: how its jar is prepared, the arguments it starts with,
 * the {@code paperhive.yml} keys it can use, how Paperhive's own plugin gets into it, when it counts as booted, how it
 * stops, how console commands reach it, and any bytecode patches its classes need. Register a subclass with
 * {@link ServerSoftwares#register} and servers with that {@code Software:} use it.
 * <p>
 * What every server gets regardless (own class loader, folder, streams, logging, {@code System.exit}, the JDK-level
 * rewrites) isn't here: that's {@link ServerInstance}'s.
 */
public abstract class ServerSoftware {

    private final MCSoftware id;

    protected ServerSoftware(@NotNull MCSoftware id) {
        this.id = id;
    }

    /** Which software this is: the {@code Software:} value in {@code paperhive.yml}. */
    public final @NotNull MCSoftware id() {
        return id;
    }

    /** For people: "Paper", "Velocity". */
    public abstract @NotNull String displayName();

    /**
     * Turns the downloaded jar into what instances boot (Paperclip's patched output for Paper, the jar itself for
     * Velocity). The result stays open while any instance runs from it.
     *
     * @param cache this software's own folder in the host's cache, for whatever preparing needs to keep
     */
    protected abstract @NotNull PreparedServer prepare(@NotNull Path jar, @NotNull Path cache);

    /**
     * {@link #prepare}, then checks the host's Java can run it, so an old Java fails here with a clear message
     * instead of an {@code UnsupportedClassVersionError} in the middle of a boot.
     */
    public final @NotNull PreparedServer prepare(@NotNull MCVersion version, @NotNull Path jar, @NotNull Path cache) {
        PreparedServer server = prepare(jar, cache);
        int needed = javaVersionOf(server);
        int running = Runtime.version().feature();
        if (needed > running) {
            server.close();
            throw new IllegalStateException(displayName() + " " + version.getVersion() + " needs Java " + needed
                    + ", but this host runs on Java " + running + ". Start Paperhive with Java " + needed + " or newer.");
        }
        return server;
    }

    /** The arguments a server starts with (unless Paperhive itself is launched with arguments, which replace them). */
    public abstract @NotNull List<String> defaultArgs(@NotNull Path root, @NotNull ServerInstance.Options options);

    /** Arguments for the {@code paperhive.yml} settings this software takes on its command line ({@code IP}, ...). */
    public @NotNull List<String> configArgs(@NotNull HiveConfig.Server server) {
        return List.of();
    }

    /** {@code paperhive.yml} server keys this software can't honour; a server of this software setting one is an error. */
    public @NotNull Set<String> unsupportedKeys() {
        return Set.of();
    }

    /** The resource in Paperhive's jar holding its plugin for this software, or null if it has none. */
    public @Nullable String bundledPlugin() {
        return null;
    }

    /**
     * Before every start: puts Paperhive's plugin ({@link #bundledPlugin()}, already written to {@code pluginJar})
     * into the server, returning the arguments that load it (if any).
     */
    public @NotNull List<String> loadPlugin(@NotNull Path pluginJar, @NotNull Path root) throws IOException {
        return List.of();
    }

    /** Before every start, after the root exists: files the server needs that its own defaults don't give it. */
    public void prepareRoot(@NotNull Path root, @NotNull ServerInstance.Options options) throws IOException {}

    /**
     * Whether the server has finished booting ({@code STARTING -> RUNNING}). Asked right after the start and about
     * four times a second while starting; return true when there's no way to tell.
     *
     * @param loader the server's class loader
     */
    public abstract boolean isReady(@NotNull ClassLoader loader, @NotNull ServerInstance.Options options);

    /**
     * Asks the server to stop gracefully (save, close, exit). Return false when that's not possible (yet): Paperhive
     * then stops it like a JVM gets SIGTERM, running its shutdown hooks.
     */
    public boolean requestStop(@NotNull ClassLoader loader) {
        return false;
    }

    /** Runs a console command in the server; false if it can't (not far enough yet, or unsupported). */
    public boolean dispatchCommand(@NotNull ClassLoader loader, @NotNull String command) {
        return false;
    }

    /**
     * System properties this software's servers see with their own value ({@code System.getProperty} in them returns
     * it; the JVM's own properties and other servers are untouched), e.g. to switch off a feature that doesn't belong
     * in a shared JVM.
     */
    public @NotNull Map<String, String> systemProperties() {
        return Map.of();
    }

    /** Bytecode patches this software's classes need, beyond the JDK-level rewrites every server gets. */
    public @NotNull List<ClassPatch> patches() {
        return List.of();
    }

    /** Whether Hive plugins' server halves run in this software (it has Paperhive's Bukkit bridge). */
    public boolean runsServerHalves() {
        return false;
    }

    @Override
    public @NotNull String toString() {
        return displayName();
    }

    /** The Java version the server's main class was compiled for. */
    private static int javaVersionOf(@NotNull PreparedServer server) {
        String entry = server.mainClass().replace('.', '/') + ".class";
        List<Path> jars = new ArrayList<>();
        jars.add(server.serverJar());
        jars.addAll(server.libraries());
        for (Path jar : jars) {
            if (!Files.isRegularFile(jar)) continue;
            try (JarFile file = new JarFile(jar.toFile())) {
                JarEntry e = file.getJarEntry(entry);
                if (e == null) continue;
                try (InputStream in = file.getInputStream(e)) {
                    byte[] head = in.readNBytes(8);
                    return ((head[6] & 0xff) << 8 | (head[7] & 0xff)) - 44; // class file major version 65 = Java 21
                }
            } catch (IOException ex) {
                throw new UncheckedIOException("Cannot read " + jar, ex);
            }
        }
        return 0;
    }

}
