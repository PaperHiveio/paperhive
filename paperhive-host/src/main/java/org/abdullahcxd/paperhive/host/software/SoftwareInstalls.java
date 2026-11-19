package org.abdullahcxd.paperhive.host.software;

import lombok.extern.slf4j.Slf4j;
import org.abdullahcxd.paperhive.downloader.MCSoftware;
import org.abdullahcxd.paperhive.downloader.MCVersion;
import org.abdullahcxd.paperhive.downloader.PaperhiveDownloader;
import org.abdullahcxd.paperhive.host.config.HiveConfig;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The prepared jars and Paperhive plugins of this host's servers: each software version is downloaded and prepared
 * once and shared by every server that runs it; each software's bundled plugin is written to the cache once.
 * Prepared servers stay open for the JVM's life (the OS drops their cache locks on exit).
 */
@Slf4j
public final class SoftwareInstalls {

    private final Path cache;
    private final String mirror;
    private final Map<MCVersion, PreparedServer> prepared = new ConcurrentHashMap<>();
    private final Map<MCSoftware, Optional<Path>> plugins = new ConcurrentHashMap<>();

    /** @param cache the host's cache folder ({@code Host.Cache}); @param mirror where jars come from ({@code Host.Mirror}) */
    public SoftwareInstalls(@NotNull Path cache, @NotNull String mirror) {
        this.cache = cache;
        this.mirror = mirror;
    }

    /**
     * What {@code server}'s instance boots: its software and version, downloaded and prepared the first time.
     *
     * @throws RuntimeException if it can't be downloaded or prepared, or needs a newer Java (with a message saying so)
     */
    public @NotNull PreparedServer prepared(@NotNull HiveConfig.Server server) {
        return prepared.computeIfAbsent(server.version(), version -> {
            Path jar = PaperhiveDownloader.fetch(version.getSoftware(), version, cache, mirror);
            return ServerSoftwares.of(version.getSoftware()).prepare(version, jar, cache);
        });
    }

    /** The arguments that put Paperhive's plugin into {@code server} at its next start (and puts any files in place). */
    public @NotNull List<String> pluginArgs(@NotNull HiveConfig.Server server) {
        ServerSoftware software = server.serverSoftware();
        Path plugin = bundledPlugin(software);
        if (plugin == null) return List.of();
        try {
            return software.loadPlugin(plugin, server.root().toAbsolutePath().normalize());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot put Paperhive's plugin into " + server.name(), e);
        }
    }

    /** {@code software}'s plugin from inside Paperhive's jar, written to the cache when it changed; null if none. */
    private @Nullable Path bundledPlugin(@NotNull ServerSoftware software) {
        return plugins.computeIfAbsent(software.id(), id -> {
            String resource = software.bundledPlugin();
            if (resource == null) return Optional.empty();
            try (InputStream in = SoftwareInstalls.class.getResourceAsStream("/" + resource)) {
                if (in == null) {
                    log.warn("This build has no {}; {} servers run without Paperhive's plugin", resource, software.displayName());
                    return Optional.empty();
                }
                byte[] bytes = in.readAllBytes();
                Path target = cache.resolve(resource).toAbsolutePath();
                if (!Files.exists(target) || !Arrays.equals(Files.readAllBytes(target), bytes)) {
                    Files.createDirectories(target.getParent());
                    Files.write(target, bytes);
                }
                return Optional.of(target);
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot write " + resource + " to " + cache, e);
            }
        }).orElse(null);
    }

}
