package org.abdullahcxd.paperhive.host.software;

import org.abdullahcxd.paperhive.downloader.MCSoftware;
import org.abdullahcxd.paperhive.downloader.MCVersion;
import org.abdullahcxd.paperhive.host.instance.ServerInstance;
import org.abdullahcxd.paperhive.host.software.paper.PaperSoftware;
import org.abdullahcxd.paperhive.host.software.velocity.VelocitySoftware;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.*;

class ServerSoftwareTest {

    @TempDir
    Path tmp;

    private static ServerInstance.Options options(Integer port) {
        return new ServerInstance.Options(port, null, List.of(), ServerInstance.RestartPolicy.NEVER);
    }

    @Test
    void registry() {
        assertInstanceOf(PaperSoftware.class, ServerSoftwares.of(MCSoftware.PAPERMC));
        assertInstanceOf(VelocitySoftware.class, ServerSoftwares.of(MCSoftware.VELOCITYMC));
        assertSame(ServerSoftwares.of(MCSoftware.PAPERMC), ServerSoftwares.paper());
        assertEquals(MCVersion.VELOCITY_4_3_1_45, MCSoftware.VELOCITYMC.defaultVersion());
        assertEquals(List.of(MCVersion.MC1_21_11), MCSoftware.PAPERMC.versions());
    }

    @Test
    void paperArguments() {
        Path root = tmp.resolve("lobby");
        List<String> args = ServerSoftwares.paper().defaultArgs(root, options(25565));
        assertTrue(args.containsAll(List.of("--nogui", "--noconsole", "--universe", root.toString(), "--port", "25565")), args.toString());
        assertTrue(ServerSoftwares.paper().runsServerHalves());
    }

    @Test
    void velocityArgumentsAndPlugin() throws IOException {
        VelocitySoftware velocity = new VelocitySoftware();
        assertEquals(List.of("--port", "25577"), velocity.defaultArgs(tmp, options(25577)));
        assertEquals(List.of(), velocity.defaultArgs(tmp, options(null)));
        assertFalse(velocity.runsServerHalves());

        Path plugin = Files.writeString(tmp.resolve("bundled.jar"), "plugin v1");
        Path root = tmp.resolve("proxy");
        assertEquals(List.of(), velocity.loadPlugin(plugin, root), "copied in, no flags");
        assertEquals("plugin v1", Files.readString(root.resolve("plugins/paperhive-velocity.jar")));
        assertFalse(Files.exists(root.resolve("plugins/paperhive")), "no config: inside the hive it reads the Hive API");

        Files.writeString(plugin, "plugin v2");
        velocity.loadPlugin(plugin, root);
        assertEquals("plugin v2", Files.readString(root.resolve("plugins/paperhive-velocity.jar")), "an update is copied over");
        assertEquals(java.util.Map.of("terminal.jline", "false"), velocity.systemProperties(), "no JLine console in a shared JVM");
        assertTrue(ServerSoftwares.overridesProperty("terminal.jline"));
    }

    @Test
    void velocityIsReadyOnceItsPortAcceptsConnections() throws IOException {
        VelocitySoftware velocity = new VelocitySoftware();
        ClassLoader loader = getClass().getClassLoader();
        try (ServerSocket listening = new ServerSocket(0)) {
            assertTrue(velocity.isReady(loader, options(listening.getLocalPort())));
            int port = listening.getLocalPort();
            listening.close();
            assertFalse(velocity.isReady(loader, options(port)));
        }
        assertTrue(velocity.isReady(loader, options(null)), "no port: no way to tell");
    }

    @Test
    void refusesAJarNeedingANewerJava() throws IOException {
        int tooNew = Runtime.version().feature() + 1;
        Path jar = jarWithMain(tmp.resolve("future.jar"), "x.Main", tooNew + 44);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new VelocitySoftware().prepare(MCVersion.VELOCITY_4_3_1_45, jar, tmp));
        assertTrue(e.getMessage().contains("needs Java " + tooNew) && e.getMessage().contains("Velocity 4.3.1-45"), e.getMessage());

        Path ok = jarWithMain(tmp.resolve("ok.jar"), "x.Main", 65);
        PreparedServer prepared = new VelocitySoftware().prepare(MCVersion.VELOCITY_4_3_1_45, ok, tmp);
        assertEquals("x.Main", prepared.mainClass());
        assertEquals(ok, prepared.serverJar());
    }

    /** A jar whose manifest names {@code main}, with a class file header of {@code classVersion}. */
    private static Path jarWithMain(Path jar, String main, int classVersion) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, main);
        try (OutputStream file = Files.newOutputStream(jar); JarOutputStream out = new JarOutputStream(file, manifest)) {
            out.putNextEntry(new JarEntry(main.replace('.', '/') + ".class"));
            out.write(new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0, 0, (byte) (classVersion >> 8), (byte) classVersion});
            out.closeEntry();
        }
        return jar;
    }

}
