package org.abdullahcxd.paperhive.host.plugin;

import org.abdullahcxd.paperhive.plugin.command.ConsoleText;
import org.abdullahcxd.paperhive.api.internal.BridgeHooks.ServerHalf;
import org.abdullahcxd.paperhive.host.hive.TestHive;
import org.abdullahcxd.paperhive.host.server.ServerManager;
import org.abdullahcxd.paperhive.host.server.TestServers;
import org.abdullahcxd.paperhive.host.command.CommandRegistry;
import org.abdullahcxd.paperhive.host.command.PluginCommand;
import org.abdullahcxd.paperhive.host.config.HiveConfig;
import org.abdullahcxd.paperhive.plugin.PaperhivePlugin;
import org.abdullahcxd.paperhive.plugin.command.HiveCommandSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** With the example plugin's real jar (test-plugin). */
class HivePluginManagerTest {

    static final Path EXAMPLE = Path.of(System.getProperty("paperhive.test.examplePlugin"));

    @TempDir
    Path tmp;
    ServerManager servers;
    CommandRegistry commands;
    HivePluginManager plugins;

    @BeforeAll
    static void hive() {
        TestHive.get(); // the example subscribes to the Hive in onEnable
    }

    @BeforeEach
    void setUp() throws Exception {
        servers = TestServers.create(tmp, i -> {});
        commands = CommandRegistry.withBuiltins(servers, name -> null);
        plugins = new HivePluginManager(Runnable::run, loader -> {});
        commands.register(new PluginCommand(plugins));
        Files.createDirectories(tmp.resolve("plugins"));
        Files.copy(EXAMPLE, tmp.resolve("plugins/example.jar"));
    }

    @AfterEach
    void close() {
        plugins.close(); // Windows can't delete a jar a loader holds open
    }

    private String run(String line) {
        List<String> out = new ArrayList<>();
        commands.execute(new HiveCommandSender() {
            @Override public void sendMessage(String message) { out.add(ConsoleText.strip(message)); }
            @Override public boolean isConsole() { return true; }
        }, line);
        return String.join("\n", out);
    }

    @Test
    void loadEnableDisable() {
        List<PaperhivePlugin> loaded = plugins.loadPlugins(tmp.resolve("plugins"));
        assertEquals(1, loaded.size());
        PaperhivePlugin example = loaded.getFirst();
        assertEquals("ExamplePlugin", example.getPluginDescriptor().getName());
        assertSame(example, plugins.findPlugin("exampleplugin"));
        assertTrue(plugins.hasServerHalf(example));
        assertNotSame(PaperhivePlugin.class.getClassLoader(), example.getClass().getClassLoader(), "its own loader");
        assertFalse(plugins.isEnabled(example));

        plugins.enableAll(commands);
        assertTrue(plugins.isEnabled(example));
        assertEquals("Hello, world!", run("greet world"), "its Hive command");
        assertTrue(run("plugin list").matches(" +ExamplePlugin +1\\.0\\.0 +enabled +\\+ server half"), run("plugin list"));

        HiveConfig.Server main = servers.server("main");
        assertEquals(List.of(new ServerHalf("ExamplePlugin", "1.0.0", tmp.resolve("plugins/example.jar").toAbsolutePath(), "org.example.ExampleServerPlugin")),
                plugins.serverHalves(main));
        servers.set("main", "DisabledHivePlugins", "[exampleplugin]");
        assertEquals(List.of(), plugins.serverHalves(servers.server("main")), "DisabledHivePlugins, any case");

        assertEquals("» Disabled ExamplePlugin; its server half follows at each server's next start", run("plugin disable ExamplePlugin"));
        assertEquals("× Unknown command greet, help lists them", run("greet world"), "its commands went with it");
        assertEquals(List.of(), plugins.serverHalves(main));
        assertTrue(run("plugin enable exampleplugin").startsWith("» Enabled ExamplePlugin"));
        assertEquals("Hello, x!", run("hi x"));
        assertEquals("× No Hive plugin named nope", run("plugin enable nope"));
    }

    @Test
    void badJarsAreSkipped() throws Exception {
        jar(tmp.resolve("plugins/empty.jar"), List.of());
        jar(tmp.resolve("plugins/noserver.jar"), List.<String[]>of(new String[]{"paperhive.yml", "name: A\nversion: 1\nmain: x.Main\nserver-main: x.Server\n"}));
        jar(tmp.resolve("plugins/nomain.jar"), List.<String[]>of(new String[]{"paperhive.yml", "name: C\nversion: 1\nmain: x.Missing\n"}));
        assertEquals(List.of("ExamplePlugin"), plugins.loadPlugins(tmp.resolve("plugins")).stream().map(p -> p.getPluginDescriptor().getName()).toList());

        assertTrue(assertThrows(IllegalArgumentException.class, () -> plugins.loadPlugin(tmp.resolve("plugins/empty.jar"))).getMessage().contains("no paperhive.yml"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> plugins.loadPlugin(tmp.resolve("plugins/noserver.jar"))).getMessage().contains("server-main x.Server isn't in the jar"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> plugins.loadPlugin(tmp.resolve("plugins/nomain.jar"))).getMessage().contains("cannot create x.Missing"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> plugins.loadPlugin(tmp.resolve("plugins/example.jar"))).getMessage().contains("already loaded"));
    }

    private static void jar(Path path, List<String[]> entries) throws Exception {
        try (OutputStream file = Files.newOutputStream(path); JarOutputStream out = new JarOutputStream(file)) {
            for (String[] e : entries) {
                out.putNextEntry(new JarEntry(e[0]));
                out.write(e[1].getBytes());
            }
        }
    }

}
