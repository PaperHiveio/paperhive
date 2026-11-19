package org.abdullahcxd.paperhive.host.config;

import org.abdullahcxd.paperhive.downloader.MCSoftware;
import org.abdullahcxd.paperhive.downloader.MCVersion;
import org.abdullahcxd.paperhive.host.instance.ServerInstance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HiveConfigTest {

    @TempDir
    Path tmp;

    @Test
    void missingFileGetsOneMainServer() throws Exception {
        Path file = tmp.resolve(HiveConfig.FILE_NAME);
        HiveConfig config = HiveConfig.load(file);
        assertTrue(Files.readString(file).contains("ConfigurationVersion: 1"));
        assertEquals(MCSoftware.PAPERMC, config.software());
        assertEquals(MCVersion.MC1_21_11, config.version());
        HiveConfig.Server main = config.servers().getFirst();
        assertEquals(new HiveConfig.Server("main", 25565, null, Path.of("instances", "main"), null, List.of(), true,
                ServerInstance.RestartPolicy.NEVER, List.of(), List.of()), main);
    }

    @Test
    void readsEveryKey() throws Exception {
        Path file = write("""
                ConfigurationVersion: 1
                Software: papermc
                Version: 1.21.11
                Servers:
                  - Name: lobby
                    Port: 25565
                  - Name: survival
                    Port: 25566
                    IP: 127.0.0.1
                    Root: servers/survival
                    Plugins: shared/plugins
                    Args: [--nojline, -Dfoo]
                    Autostart: false
                    Restart: on-crash
                    DisabledHivePlugins: [Chat]
                    SharedPlugins: [shared/all, shared/survival]
                """);
        HiveConfig.Server survival = HiveConfig.load(file).servers().get(1);
        assertEquals(new HiveConfig.Server("survival", 25566, "127.0.0.1", Path.of("servers", "survival"), Path.of("shared", "plugins"),
                List.of("--nojline", "-Dfoo"), false, ServerInstance.RestartPolicy.ON_CRASH, List.of("Chat"),
                List.of(Path.of("shared", "all"), Path.of("shared", "survival"))), survival);
        ServerInstance.Options options = survival.options();
        assertEquals(25566, options.port());
        assertEquals(List.of("--host", "127.0.0.1", "--add-plugin-dir", Path.of("shared", "all").toAbsolutePath().toString(),
                "--add-plugin-dir", Path.of("shared", "survival").toAbsolutePath().toString(), "--nojline", "-Dfoo"), options.args());
        assertEquals(ServerInstance.RestartPolicy.ON_CRASH, options.restart());
    }

    @Test
    void readsTheHostSection() throws Exception {
        Path file = write("""
                ConfigurationVersion: 1
                Host:
                  Plugins: hive-plugins
                  Cache: /tmp/cache
                  Mirror: https://mirror.example/versions
                  StopTimeout: 30
                  MemoryWarning: 80
                  RestartDelay: 0
                  MaxCrashes: 5
                  CrashWindow: 60
                Servers:
                  - Name: a
                    Port: 1
                    Restart: on-crash
                """);
        HiveConfig config = HiveConfig.load(file);
        assertEquals(new HiveConfig.Host(Path.of("hive-plugins"), Path.of("/tmp/cache"), "https://mirror.example/versions/",
                Duration.ofSeconds(30), 80, Duration.ZERO, 5, Duration.ofSeconds(60)), config.host());
        ServerInstance.Options options = config.servers().getFirst().options(config.host());
        assertEquals(List.of(Duration.ZERO, 5, Duration.ofSeconds(60)), List.of(options.restartDelay(), options.maxCrashes(), options.crashWindow()));

        assertEquals(HiveConfig.Host.DEFAULT, HiveConfig.load(write("ConfigurationVersion: 1\nServers: [{Name: a, Port: 1}]\n")).host());

        String message = problems("""
                ConfigurationVersion: 1
                Host:
                  Mirror: http://insecure.example/
                  MemoryWarning: 150
                  stop-timeout: 5
                Servers: [{Name: a, Port: 1}]
                """);
        for (String expected : List.of("Host.Mirror: expected an https:// URL", "Host.MemoryWarning: expected a whole number 1-100",
                "Host.stop-timeout: unknown key, did you mean StopTimeout?")) {
            assertTrue(message.contains(expected), expected + " missing from:\n" + message);
        }
    }

    @Test
    void wronglyCasedKeysNameTheRightOne() throws Exception {
        String message = problems("""
                ConfigurationVersion: 1
                servers:
                  - Name: a
                    port: 1
                    disabled-hive-plugins: []
                    Colour: red
                """);
        assertTrue(message.contains("servers: unknown key, did you mean Servers?"), message);
        assertTrue(message.contains("Servers: expected a list"), message);

        message = problems("""
                ConfigurationVersion: 1
                Servers:
                  - Name: a
                    port: 1
                    disabled-hive-plugins: []
                    Colour: red
                """);
        assertTrue(message.contains("Servers[0] (a).port: unknown key, did you mean Port?"), message);
        assertTrue(message.contains("disabled-hive-plugins: unknown key, did you mean DisabledHivePlugins?"), message);
        assertTrue(message.contains("Colour: unknown key; known: ["), message);
        assertTrue(message.contains("Port: missing"), message);
    }

    @Test
    void reportsEveryProblemAtOnce() throws Exception {
        String message = problems("""
                ConfigurationVersion: 1
                Software: spigot
                Version: 1.8
                Servers:
                  - Name: a
                    Port: 25565
                  - Name: A
                    Port: 25565
                  - Name: b c
                    Port: 70000
                  - Name: d
                    Port: 1
                    Root: instances/a
                  - Name: e
                    Port: 2
                    Autostart: maybe
                    Restart: always
                    Args: --x
                """);
        for (String expected : List.of("Software: unknown 'spigot'", "Version: unknown '1.8'", "the name A is used twice (as a)",
                "a and A both use Port 25565", "may only use letters", "Port: expected 1-65535", "a and d share the folder",
                "Autostart: expected true or false", "Restart: expected never or on-crash", "Args: expected a list")) {
            assertTrue(message.contains(expected), expected + " missing from:\n" + message);
        }
    }

    @Test
    void rejectsEmptyAndNewerFiles() throws Exception {
        assertTrue(problems("ConfigurationVersion: 1\nServers: []\n").contains("at least one server"));
        assertTrue(problems("ConfigurationVersion: 2\nServers: []\n").contains("from a newer Paperhive"));
        assertTrue(problems("ConfigurationVersion: 1\nServers: [\n").contains("not valid YAML"));
    }

    @Test
    void migratesTheVersion0Format() throws Exception {
        Path file = write("""
                instances:
                  - name: alpha
                    port: 25601
                  - name: beta
                    port: 25602
                    root: servers/beta
                """);
        HiveConfig config = HiveConfig.load(file);
        assertEquals(List.of("alpha", "beta"), config.servers().stream().map(HiveConfig.Server::name).toList());
        assertEquals(Path.of("servers", "beta"), config.servers().get(1).root());

        String migrated = Files.readString(file);
        assertTrue(migrated.contains("ConfigurationVersion: 1") && migrated.contains("Servers:") && migrated.contains("Port: 25602"), migrated);
        assertTrue(Files.readString(tmp.resolve(HiveConfig.FILE_NAME + ".v0.bak")).contains("instances:"), "the original is kept");
        assertEquals(config, HiveConfig.load(file), "the rewritten file reads the same");
    }

    @Test
    void addServerAppendsAndKeepsComments() throws Exception {
        Path file = tmp.resolve(HiveConfig.FILE_NAME);
        HiveConfig.load(file); // the commented default
        HiveConfig config = HiveConfig.addServer(file, "lobby", 25566);
        assertEquals(new HiveConfig.Server("lobby", 25566, null, Path.of("instances", "lobby"), null, List.of(), true,
                ServerInstance.RestartPolicy.NEVER, List.of(), List.of()), config.servers().get(1));
        String text = Files.readString(file);
        assertTrue(text.contains("# Restart: never") && text.endsWith("  - Name: lobby\n    Port: 25566\n"), text);
        assertEquals(config, HiveConfig.load(file));
        assertFalse(Files.exists(tmp.resolve(HiveConfig.FILE_NAME + ".bak")));
    }

    @Test
    void addServerRewritesWhenItCantAppend() throws Exception {
        Path file = write("""
                ConfigurationVersion: 1
                Servers: [{Name: a, Port: 1}]
                Version: 1.21.11
                """);
        HiveConfig config = HiveConfig.addServer(file, "b", 2);
        assertEquals(List.of("a", "b"), config.servers().stream().map(HiveConfig.Server::name).toList());
        assertEquals(config, HiveConfig.load(file));
        assertTrue(Files.readString(tmp.resolve(HiveConfig.FILE_NAME + ".bak")).contains("[{Name: a"));
    }

    @Test
    void addServerWritesNothingWhenInvalid() throws Exception {
        Path file = write("ConfigurationVersion: 1\nServers:\n  - Name: a\n    Port: 1\n");
        String before = Files.readString(file);
        String message = assertThrows(HiveConfig.HiveConfigException.class, () -> HiveConfig.addServer(file, "A", 1)).getMessage();
        assertTrue(message.contains("used twice") && message.contains("both use Port 1"), message);
        assertThrows(HiveConfig.HiveConfigException.class, () -> HiveConfig.addServer(file, "b c", 2));
        assertEquals(before, Files.readString(file));
    }

    @Test
    void setAndRemoveKeepComments() throws Exception {
        Path file = write("""
                ConfigurationVersion: 1
                Servers:
                  # the lobby
                  - Name: lobby
                    Port: 1 # first
                    Restart: never

                  # the game
                  - Name: game
                    Port: 2
                """);
        HiveConfig.setServerKey(file, "lobby", "Port", "5");
        HiveConfig.setServerKey(file, "lobby", "Restart", null);
        HiveConfig.setServerKey(file, "game", "Args", "[--a, --b]");
        HiveConfig config = HiveConfig.removeServer(file, "lobby");
        assertEquals(List.of("game"), config.servers().stream().map(HiveConfig.Server::name).toList());
        assertEquals(List.of("--a", "--b"), config.servers().getFirst().args());
        assertEquals("""
                ConfigurationVersion: 1
                Servers:
                  # the lobby

                  # the game
                  - Name: game
                    Args: [--a, --b]
                    Port: 2
                """, Files.readString(file));
        assertFalse(Files.exists(tmp.resolve(HiveConfig.FILE_NAME + ".bak")));
    }

    @Test
    void setValidatesAndRewritesWhatItCantEdit() throws Exception {
        Path file = write("""
                ConfigurationVersion: 1
                Servers:
                  - Name: a
                    Port: 1
                    Args:
                      - --x
                  - Name: b
                    Port: 2
                """);
        String before = Files.readString(file);
        for (String[] bad : List.of(new String[]{"Port", "2"}, new String[]{"Port", "x"}, new String[]{"port", "3"}, new String[]{"Name", "c"},
                new String[]{"Args", "[--y"})) {
            assertThrows(HiveConfig.HiveConfigException.class, () -> HiveConfig.setServerKey(file, "a", bad[0], bad[1]), bad[0]);
        }
        assertThrows(HiveConfig.HiveConfigException.class, () -> HiveConfig.setServerKey(file, "nope", "Port", "3"));
        assertEquals(before, Files.readString(file));

        // a block list can't be swapped line by line: rewritten, the old file kept
        assertEquals(List.of("--y"), HiveConfig.setServerKey(file, "a", "Args", "[--y]").servers().getFirst().args());
        assertEquals(before, Files.readString(tmp.resolve(HiveConfig.FILE_NAME + ".bak")));
        assertThrows(HiveConfig.HiveConfigException.class, () -> HiveConfig.removeServer(file, "nope"));
    }

    private String problems(String yaml) throws Exception {
        Path file = write(yaml);
        return assertThrows(HiveConfig.HiveConfigException.class, () -> HiveConfig.load(file)).getMessage();
    }

    private Path write(String yaml) throws Exception {
        return Files.writeString(tmp.resolve(HiveConfig.FILE_NAME), yaml);
    }

}
