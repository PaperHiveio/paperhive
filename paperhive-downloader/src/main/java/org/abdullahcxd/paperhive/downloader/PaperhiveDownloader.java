package org.abdullahcxd.paperhive.downloader;

import org.abdullahcxd.paperhive.constants.DirectoryConstants;
import org.abdullahcxd.paperhive.constants.DownloaderConstants;
import org.abdullahcxd.paperhive.downloader.impl.PaperMCSoftwareDownloader;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class PaperhiveDownloader {

    private PaperhiveDownloader() {}

    /** Downloads into {@code destination} and returns the copied jar. */
    public static Path download(@NotNull MCSoftware software, MCVersion version, Path destination) {
        return downloaderFor(software, DirectoryConstants.CACHE_DIRECTORY_PATH, DownloaderConstants.CDN_URI).download(software, version, destination);
    }

    /** Returns the verified jar inside the shared cache, downloading it first if needed. No copy is made. */
    public static Path fetch(@NotNull MCSoftware software, MCVersion version) {
        return fetch(software, version, DirectoryConstants.CACHE_DIRECTORY_PATH, DownloaderConstants.CDN_URI);
    }

    /** Like {@link #fetch(MCSoftware, MCVersion)}, with another cache folder and mirror. */
    public static Path fetch(@NotNull MCSoftware software, MCVersion version, Path cache, String mirror) {
        return downloaderFor(software, cache, mirror).fetch(software, version);
    }

    private static @NotNull VersionDownloader downloaderFor(@NotNull MCSoftware software, Path cache, String mirror) {
        try {
            Files.createDirectories(cache);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        return switch (software) {
            case PAPERMC, VELOCITYMC -> new PaperMCSoftwareDownloader(cache, mirror); // same mirror layout
        };
    }

}
