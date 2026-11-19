package org.abdullahcxd.paperhive.downloader;

import org.abdullahcxd.paperhive.constants.DirectoryConstants;
import org.abdullahcxd.paperhive.constants.DownloaderConstants;
import org.abdullahcxd.paperhive.downloader.exceptions.DownloaderException;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.regex.Pattern;

public interface VersionDownloader {

    /** Follows same-protocol redirects only (never https -> http). */
    HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    Pattern SAFE_SEGMENT = Pattern.compile("[A-Za-z0-9._-]+");

    /** The mirror's base URL, ending in {@code /}. */
    default String mirror() {
        return DownloaderConstants.CDN_URI;
    }

    /** The shared cache folder the jars are kept in. */
    default Path cache() {
        return DirectoryConstants.CACHE_DIRECTORY_PATH;
    }

    /** Downloads (or reuses) the verified jar in the shared cache and returns its path there. */
    Path fetch(MCSoftware software, MCVersion version);

    /** Like {@link #fetch} but also copies the jar into {@code destinationPath}. */
    default Path download(MCSoftware software, MCVersion version, @NotNull Path destinationPath) {
        Path cached = fetch(software, version);
        Path target = destinationPath.resolve(cached.getFileName());
        try {
            Files.createDirectories(destinationPath);
            Files.copy(cached, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new DownloaderException("Could not copy " + cached + " to " + destinationPath, e);
        }
        return target;
    }

    default URI resolveURI(@NotNull MCSoftware software, @NotNull MCVersion version, @NotNull String continuePath) {
        requireSafeSegment(software.getName(), "software name");
        requireSafeSegment(version.getVersion(), "version");
        requireSafeSegment(continuePath, "file name");

        URI uri = URI.create(
                mirror() + software.getName() + "/" + version.getVersion() + "/" + continuePath
        );
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new DownloaderException("Refusing to download over a non-HTTPS URI: " + uri);
        }
        return uri;
    }

    /**
     * Downloads to a unique temp file next to the destination, then moves it into place,
     * so a failed or interrupted download never leaves a truncated file at the final path.
     */
    default void downloadFile(@NotNull URI uri, @NotNull Path destination) {
        Path part = null;
        try {
            Path parent = destination.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            part = Files.createTempFile(parent, destination.getFileName() + ".", ".part");

            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMinutes(5))
                    .GET()
                    .build();

            HttpResponse<Path> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofFile(
                    part, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING));

            if (response.statusCode() != 200) {
                throw new DownloaderException("HTTP " + response.statusCode() + " while downloading " + uri);
            }
            Files.move(part, destination, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new DownloaderException("Failed to download " + uri, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DownloaderException("Interrupted while downloading " + uri, e);
        } finally {
            if (part != null) {
                try {
                    Files.deleteIfExists(part); // no-op after a successful move
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static void requireSafeSegment(String value, String what) {
        if (value == null || !SAFE_SEGMENT.matcher(value).matches() || value.equals(".") || value.equals("..")) {
            throw new DownloaderException("Illegal " + what + ": " + value);
        }
    }
}