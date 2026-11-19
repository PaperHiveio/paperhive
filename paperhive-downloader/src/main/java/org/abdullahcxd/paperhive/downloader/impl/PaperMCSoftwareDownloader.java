package org.abdullahcxd.paperhive.downloader.impl;

import org.jetbrains.annotations.NotNull;
import lombok.extern.slf4j.Slf4j;
import org.abdullahcxd.paperhive.constants.DirectoryConstants;
import org.abdullahcxd.paperhive.constants.DownloaderConstants;
import org.abdullahcxd.paperhive.downloader.FileSHA256;
import org.abdullahcxd.paperhive.downloader.MCSoftware;
import org.abdullahcxd.paperhive.downloader.MCVersion;
import org.abdullahcxd.paperhive.downloader.VersionDownloader;
import org.abdullahcxd.paperhive.downloader.exceptions.DownloaderException;
import org.abdullahcxd.paperhive.downloader.index.IndexExtractor;
import org.abdullahcxd.paperhive.downloader.index.IndexSpec;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
public class PaperMCSoftwareDownloader implements VersionDownloader {

    private final Path cache;
    private final String mirror;

    public PaperMCSoftwareDownloader() {
        this(DirectoryConstants.CACHE_DIRECTORY_PATH, DownloaderConstants.CDN_URI);
    }

    /** @param mirror base URL of a Paperhive mirror; a missing trailing {@code /} is added */
    public PaperMCSoftwareDownloader(Path cache, String mirror) {
        this.cache = cache;
        this.mirror = mirror.endsWith("/") ? mirror : mirror + "/";
    }

    @Override
    public @NotNull Path cache() {
        return cache;
    }

    @Override
    public @NotNull String mirror() {
        return mirror;
    }

    @Override
    public @NotNull Path fetch(@NotNull MCSoftware software, @NotNull MCVersion version) {
        // resolveURI validates software/version names, so do it before they touch any Path
        URI indexUri = resolveURI(software, version, "index.json");
        Path cacheVersionDirectory = cache
                .resolve(software.getName())
                .resolve(version.getVersion());

        try {
            Files.createDirectories(cacheVersionDirectory);
        } catch (IOException e) {
            throw new DownloaderException("Could not create cache directory " + cacheVersionDirectory, e);
        }

        // 1. Always refresh the index (small), it tells us the jar name and expected hash
        Path indexPath = cacheVersionDirectory.resolve("index.json");
        try {
            downloadFile(indexUri, indexPath);
        } catch (DownloaderException e) {
            // offline or mirror down: a previously fetched index still lets the cached jar be verified and used
            if (!Files.isRegularFile(indexPath)) throw e;
            log.warn("Could not refresh {}, using the cached index: {}", indexUri, e.getMessage());
        }

        // 2. Extract and validate index data
        IndexSpec index = IndexExtractor.extract(indexPath);
        String file = index.file();
        String expectedSha = index.sha256();
        if (file == null || file.isBlank() || expectedSha == null || expectedSha.isBlank()) {
            throw new DownloaderException("Index file at " + indexPath + " is missing 'file' or 'sha256'.");
        }

        if (!expectedSha.matches("[0-9a-fA-F]{64}")) throw new DownloaderException("Index file at " + indexPath + " has an invalid sha256");
        // the hash in the query makes new content a new URL: the CDN caches jars for a day, so a jar replaced under the
        // same name would otherwise be served stale (and fail the hash check) until then
        URI jarUri = URI.create(resolveURI(software, version, file) + "?sha256=" + expectedSha.toLowerCase(java.util.Locale.ROOT));
        Path jarFileCache = cacheVersionDirectory.resolve(file);

        // 3. Download the jar only if there is no valid-cached copy
        if (!matches(jarFileCache, expectedSha)) {
            downloadFile(jarUri, jarFileCache);

            if (!matches(jarFileCache, expectedSha)) {
                try {
                    Files.deleteIfExists(jarFileCache);
                } catch (IOException ignored) {
                }
                throw new DownloaderException("The SHA256 of " + jarFileCache
                        + " does not match the index. Please make sure that the mirror you are using is the paperhive mirror.");
            }
        }

        return jarFileCache;
    }

    private static boolean matches(Path jar, String expectedSha) {
        if (!Files.isRegularFile(jar)) return false;
        try {
            return expectedSha.equalsIgnoreCase(FileSHA256.sha256(jar));
        } catch (IOException e) {
            throw new DownloaderException("Could not hash " + jar, e);
        }
    }
}