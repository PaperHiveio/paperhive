package org.abdullahcxd.paperhive.host.paperclip;

import lombok.extern.slf4j.Slf4j;
import org.abdullahcxd.paperhive.downloader.FileSHA256;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;

/**
 * Runs Paperclip once per distinct paper.jar and reuses the result on later launches.
 * <p>
 * Layout: {@code <cacheRoot>/.lock} plus one entry per jar,
 * {@code <cacheRoot>/<jar sha256 prefix>/{versions,libraries,cache,paperclip.log,.complete,.in-use}}.
 * <ul>
 *   <li>{@code .lock}: held exclusively while a host patches or prunes, so two hosts never patch at once.</li>
 *   <li>{@code .complete}: the entry was patched and read successfully; later launches skip patching.</li>
 *   <li>{@code .in-use}: shared-locked by every host running from the entry; entries nobody holds are pruned.</li>
 * </ul>
 */
@Slf4j
public final class PaperclipCache {

    private static final String CACHE_LOCK = ".lock";
    private static final String COMPLETE_MARKER = ".complete";
    private static final String IN_USE = ".in-use";
    private static final String MAIN_CLASS_ENTRY = "META-INF/main-class";

    // a JVM can't hold two locks on one file, so in-process users of an entry share one lock, refcounted
    private static final Map<Path, InUse> inUse = new HashMap<>();

    private static final class InUse {
        private final FileChannel channel;
        private int refs;

        private InUse(FileChannel channel) {
            this.channel = channel;
        }
    }

    private PaperclipCache() {}

    /**
     * Patches {@code paperJar} into {@code cacheRoot} if needed, prunes entries no host uses, and returns the result.
     * Close the returned {@link PatchedServer} once nothing runs from it any more.
     */
    public static synchronized @NotNull PatchedServer prepare(Path paperJar, Path cacheRoot) {
        if (!Files.isRegularFile(paperJar)) {
            throw new PaperclipException("Paper jar not found: " + paperJar.toAbsolutePath());
        }

        Path root = cacheRoot.toAbsolutePath().normalize();
        Path entry;
        try {
            Files.createDirectories(root);
            // 16 hex chars is plenty to tell jars apart and keeps Windows paths under MAX_PATH
            entry = root.resolve(FileSHA256.sha256(paperJar).substring(0, 16));
        } catch (IOException e) {
            throw new PaperclipException("Could not prepare cache directory " + root, e);
        }

        try (FileChannel lockChannel = FileChannel.open(root.resolve(CACHE_LOCK), CREATE, WRITE);
             FileLock ignored = lockCache(lockChannel, root)) {
            Files.createDirectories(entry);

            Path marker = entry.resolve(COMPLETE_MARKER);
            if (Files.exists(marker)) {
                log.info("Using cached Paperclip output in {}", entry);
            } else {
                log.info("Patching {} into {} (first launch for this jar)", paperJar.getFileName(), entry);
                runPaperclip(paperJar, entry);
            }

            Path serverJar = findServerJar(entry.resolve("versions"));
            List<Path> libraries = findLibraries(entry.resolve("libraries"));
            String mainClass = readMainClass(paperJar);
            // written only after a full successful read, so a broken patch is retried next launch
            if (!Files.exists(marker)) Files.createFile(marker);

            acquire(entry);
            prune(root, entry);
            return new PatchedServer(serverJar, libraries, mainClass, () -> release(entry));
        } catch (IOException e) {
            throw new PaperclipException("Could not use cache " + root, e);
        }
    }

    private static @NotNull FileLock lockCache(@NotNull FileChannel channel, Path root) throws IOException {
        FileLock lock = channel.tryLock();
        if (lock != null) return lock;
        log.info("Another Paperhive host is preparing {}, waiting for it", root);
        return channel.lock();
    }

    private static void acquire(@NotNull Path entry) throws IOException {
        InUse use = inUse.get(entry);
        if (use == null) {
            FileChannel channel = FileChannel.open(entry.resolve(IN_USE), CREATE, READ, WRITE);
            try {
                channel.lock(0, Long.MAX_VALUE, true);
            } catch (IOException | RuntimeException e) {
                channel.close();
                throw e;
            }
            use = new InUse(channel);
            inUse.put(entry, use);
        }
        use.refs++;
    }

    private static synchronized void release(@NotNull Path entry) {
        InUse use = inUse.get(entry);
        if (use == null || --use.refs > 0) return;
        inUse.remove(entry);
        try {
            use.channel.close(); // also releases the lock
        } catch (IOException e) {
            log.warn("Could not release {}", entry.resolve(IN_USE), e);
        }
    }

    // caller holds the cache lock, so no other host can start using an entry while we check and delete it
    private static void prune(Path root, Path keep) throws IOException {
        List<Path> entries;
        try (Stream<Path> list = Files.list(root)) {
            entries = list.filter(Files::isDirectory).filter(p -> !p.equals(keep)).toList();
        }
        for (Path entry : entries) {
            if (inUse.containsKey(entry) || usedByAnotherHost(entry)) {
                log.debug("Keeping {}, still in use", entry);
                continue;
            }
            log.info("Removing unused Paperclip cache {}", entry);
            try {
                deleteRecursively(entry);
            } catch (IOException e) {
                log.warn("Could not fully remove {}, will retry next launch", entry, e);
            }
        }
    }

    private static boolean usedByAnotherHost(@NotNull Path entry) {
        Path file = entry.resolve(IN_USE);
        if (!Files.exists(file)) return false;
        try (FileChannel channel = FileChannel.open(file, WRITE); FileLock lock = channel.tryLock()) {
            return lock == null;
        } catch (IOException e) {
            return true; // can't tell, so keep it
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path p : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
        }
    }

    private static void runPaperclip(@NotNull Path paperJar, @NotNull Path dir) {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Path logFile = dir.resolve("paperclip.log");
        ProcessBuilder pb = new ProcessBuilder(
                java.toString(), "-Dpaperclip.patchonly=true", "-jar", paperJar.toAbsolutePath().toString())
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(logFile.toFile());
        try {
            int exit = pb.start().waitFor();
            if (exit != 0) {
                throw new PaperclipException("Paperclip exited with code " + exit + ", see " + logFile);
            }
        } catch (IOException e) {
            throw new PaperclipException("Could not start Paperclip", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PaperclipException("Interrupted while waiting for Paperclip", e);
        }
    }

    private static Path findServerJar(Path versions) {
        List<Path> jars = jarsUnder(versions);
        if (jars.size() != 1) {
            throw new PaperclipException("Expected exactly one patched jar under " + versions + ", found " + jars);
        }
        return jars.getFirst();
    }

    private static @NotNull List<Path> findLibraries(Path libraries) {
        List<Path> jars = jarsUnder(libraries);
        if (jars.isEmpty()) throw new PaperclipException("No libraries found under " + libraries);
        return jars;
    }

    private static @NotNull @Unmodifiable List<Path> jarsUnder(Path root) {
        if (!Files.isDirectory(root)) throw new PaperclipException("Missing Paperclip output directory " + root);
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".jar")).sorted().toList();
        } catch (IOException e) {
            throw new PaperclipException("Could not list " + root, e);
        }
    }

    // main-class lives in the Paperclip jar (it's what Paperclip itself launches), not in the patched jar
    private static @NotNull String readMainClass(Path paperJar) {
        try (JarFile jar = new JarFile(paperJar.toFile())) {
            JarEntry entry = jar.getJarEntry(MAIN_CLASS_ENTRY);
            if (entry == null) throw new PaperclipException(paperJar + " has no " + MAIN_CLASS_ENTRY + ", is it a Paperclip jar?");
            try (InputStream in = jar.getInputStream(entry)) {
                String main = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
                if (main.isEmpty()) throw new PaperclipException(MAIN_CLASS_ENTRY + " in " + paperJar + " is empty");
                return main;
            }
        } catch (IOException e) {
            throw new PaperclipException("Could not read " + paperJar, e);
        }
    }

}
