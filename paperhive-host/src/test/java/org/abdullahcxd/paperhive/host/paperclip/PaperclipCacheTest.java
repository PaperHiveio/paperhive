package org.abdullahcxd.paperhive.host.paperclip;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class PaperclipCacheTest {

    private static final String SERVER_MAIN = "org.bukkit.craftbukkit.Main";

    @TempDir
    Path tmp;

    @Test
    void patchesOnceThenReusesCache() throws IOException {
        Path jar = fakePaperJar("1.0", 0, 0, true);
        Path cache = tmp.resolve("cache");

        try (PatchedServer first = PaperclipCache.prepare(jar, cache)) {
            assertTrue(first.serverJar().endsWith(Path.of("versions", "1.0", "paper-1.0.jar")));
            assertEquals(SERVER_MAIN, first.mainClass());
            assertEquals(2, first.libraries().size());
            assertTrue(first.libraries().stream().allMatch(Files::isRegularFile));
        }
        try (PatchedServer second = PaperclipCache.prepare(jar, cache)) {
            assertEquals(SERVER_MAIN, second.mainClass());
        }

        assertEquals(1, patchRuns(cache));
    }

    @Test
    void changedJarIsPatchedAndOldEntryPruned() throws IOException {
        Path cache = tmp.resolve("cache");
        try (PatchedServer ignored = PaperclipCache.prepare(fakePaperJar("1.0", 0, 0, true), cache)) {
            assertEquals(1, entries(cache).size());
        }

        try (PatchedServer server = PaperclipCache.prepare(fakePaperJar("2.0", 0, 0, true), cache)) {
            assertTrue(server.serverJar().endsWith(Path.of("versions", "2.0", "paper-2.0.jar")));
            List<Path> entries = entries(cache);
            assertEquals(1, entries.size(), "old entry should be pruned: " + entries);
            assertTrue(server.serverJar().startsWith(entries.getFirst()));
        }
    }

    @Test
    void entryInUseIsNotPruned() throws IOException {
        Path cache = tmp.resolve("cache");
        try (PatchedServer old = PaperclipCache.prepare(fakePaperJar("1.0", 0, 0, true), cache);
             PatchedServer ignored = PaperclipCache.prepare(fakePaperJar("2.0", 0, 0, true), cache)) {
            assertEquals(2, entries(cache).size());
            assertTrue(Files.isRegularFile(old.serverJar()));
        }
        // nothing holds 1.0 any more, so the next prepare cleans it up
        try (PatchedServer ignored = PaperclipCache.prepare(fakePaperJar("2.0", 0, 0, true), cache)) {
            assertEquals(1, entries(cache).size());
        }
    }

    @Test
    void sameEntryCanBeHeldTwiceInOneJvm() throws IOException {
        Path jar = fakePaperJar("1.0", 0, 0, true);
        Path cache = tmp.resolve("cache");
        PatchedServer a = PaperclipCache.prepare(jar, cache);
        PatchedServer b = PaperclipCache.prepare(jar, cache);
        a.close();
        a.close(); // idempotent: must not drop b's hold
        try (PatchedServer ignored = PaperclipCache.prepare(fakePaperJar("2.0", 0, 0, true), cache)) {
            assertEquals(2, entries(cache).size(), "1.0 is still held by b");
        }
        b.close();
    }

    @Test
    void failedPatchIsRetriedNextLaunch() throws IOException {
        Path cache = tmp.resolve("cache");
        Path failing = fakePaperJar("1.0", 0, 3, true);

        PaperclipException e = assertThrows(PaperclipException.class, () -> PaperclipCache.prepare(failing, cache));
        assertTrue(e.getMessage().contains("exited with code 3"), e.getMessage());
        assertThrows(PaperclipException.class, () -> PaperclipCache.prepare(failing, cache));

        assertEquals(2, patchRuns(cache), "no .complete marker, so it must patch again");
    }

    @Test
    void rejectsJarWithoutMainClass() throws IOException {
        Path jar = fakePaperJar("1.0", 0, 0, false);
        PaperclipException e = assertThrows(PaperclipException.class,
                () -> PaperclipCache.prepare(jar, tmp.resolve("cache")));
        assertTrue(e.getMessage().contains("META-INF/main-class"), e.getMessage());
    }

    @Test
    void rejectsMissingJar() {
        assertThrows(PaperclipException.class,
                () -> PaperclipCache.prepare(tmp.resolve("nope.jar"), tmp.resolve("cache")));
    }

    @Test
    void twoHostsDoNotPatchAtOnce() throws Exception {
        // real separate JVMs, since the cache lock is a file lock between processes
        Path jar = fakePaperJar("1.0", 1500, 0, true);
        Path cache = tmp.resolve("cache");

        List<Process> hosts = new ArrayList<>();
        for (int i = 0; i < 2; i++) hosts.add(startHost(jar, cache, tmp.resolve("host" + i)));

        StringBuilder all = new StringBuilder();
        for (Process host : hosts) {
            // read before waitFor so a full pipe can't block the child
            String out = new String(host.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(host.waitFor(60, TimeUnit.SECONDS), "host timed out");
            assertEquals(0, host.exitValue(), out);
            assertTrue(out.contains("Main class: " + SERVER_MAIN), out);
            all.append(out);
        }
        assertEquals(1, patchRuns(cache));
        // the patch sleeps 1.5s, so the second host must have hit the lock rather than just started late
        assertTrue(all.toString().contains("waiting for it"), all.toString());
    }

    private Process startHost(Path jar, Path cache, Path workDir) throws IOException {
        Files.createDirectories(workDir);
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        return new ProcessBuilder(java.toString(), "-cp", System.getProperty("java.class.path"),
                PrepareMain.class.getName(), jar.toString(), cache.toString())
                .directory(workDir.toFile())
                .redirectErrorStream(true)
                .start();
    }

    private Path fakePaperJar(String version, long sleepMs, int exitCode, boolean withMainClass) throws IOException {
        Path jar = tmp.resolve("paper-" + version + "-" + sleepMs + "-" + exitCode + "-" + withMainClass + ".jar");
        if (Files.exists(jar)) return jar;

        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, FakePaperclip.class.getName());

        String classFile = FakePaperclip.class.getName().replace('.', '/') + ".class";
        try (OutputStream file = Files.newOutputStream(jar);
             JarOutputStream out = new JarOutputStream(file, manifest);
             InputStream classBytes = FakePaperclip.class.getClassLoader().getResourceAsStream(classFile)) {
            assertNotNull(classBytes, classFile);
            put(out, classFile, classBytes.readAllBytes());
            put(out, "fake.properties",
                    ("version=" + version + "\nsleepMs=" + sleepMs + "\nexitCode=" + exitCode + "\n").getBytes());
            if (withMainClass) put(out, "META-INF/main-class", SERVER_MAIN.getBytes());
        }
        return jar;
    }

    private static void put(JarOutputStream out, String name, byte[] bytes) throws IOException {
        out.putNextEntry(new JarEntry(name));
        out.write(bytes);
        out.closeEntry();
    }

    private static List<Path> entries(Path cache) throws IOException {
        try (Stream<Path> list = Files.list(cache)) {
            return list.filter(Files::isDirectory).toList();
        }
    }

    private static long patchRuns(Path cache) throws IOException {
        long runs = 0;
        for (Path entry : entries(cache)) {
            Path file = entry.resolve("patch-runs.txt");
            if (Files.exists(file)) runs += Files.readAllLines(file).size();
        }
        return runs;
    }

}
