package org.abdullahcxd.paperhive.host.command;

import org.abdullahcxd.paperhive.plugin.command.ConsoleText;
import org.abdullahcxd.paperhive.host.server.ServerManager;
import org.abdullahcxd.paperhive.host.config.HiveConfig;
import org.abdullahcxd.paperhive.host.instance.ApiGateLoader;
import org.abdullahcxd.paperhive.host.instance.ServerInstance;
import org.abdullahcxd.paperhive.host.software.PreparedServer;
import org.abdullahcxd.paperhive.plugin.command.HiveCommand;
import org.abdullahcxd.paperhive.plugin.command.HiveCommandSender;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The commands that don't run a server; starting and stopping are {@link ServerInstance}'s (see its tests). */
class CommandRegistryTest {

    @TempDir
    Path tmp;
    ServerManager servers;
    CommandRegistry registry;

    @BeforeEach
    void setUp() {
        Path file = tmp.resolve(HiveConfig.FILE_NAME);
        PreparedServer server = new PreparedServer(tmp.resolve("none.jar"), List.of(), "none.Main", () -> {});
        ApiGateLoader gate = new ApiGateLoader(getClass().getClassLoader());
        servers = new ServerManager(file, HiveConfig.load(file), List.of(), s -> List.of(),
                s -> new ServerInstance(s.name(), tmp.resolve(s.root()), server, gate, s.options()));
        registry = CommandRegistry.withBuiltins(servers, name -> null);
    }

    private String run(String line) {
        return run(line, true);
    }

    private String run(String line, boolean console) {
        List<String> out = new ArrayList<>();
        registry.execute(sender(out, console), line);
        return String.join("\n", out);
    }

    private static HiveCommandSender sender(List<String> out, boolean console) {
        return new HiveCommandSender() {
            @Override public void sendMessage(@NonNull String message) { out.add(ConsoleText.strip(message)); }
            @Override public boolean isConsole() { return console; }
        };
    }

    /** What a Hive plugin would register. */
    private static final class GreetCommand extends HiveCommand {
        GreetCommand() {
            super("greet", "<who>", "say hello", "hi");
        }

        @Override
        public void execute(@NonNull HiveCommandSender sender, @NonNull List<String> args) {
            if (args.size() != 1) sendUsage(sender);
            else if (args.getFirst().equals("boom")) throw new IllegalStateException("boom");
            else sender.sendMessage("hello " + args.getFirst());
        }

        @Override
        public @NonNull List<String> complete(@NonNull HiveCommandSender sender, @NonNull List<String> args) {
            return List.of("world", "hive");
        }
    }

    @Test
    void pluginCommandsRunLikeBuiltins() {
        GreetCommand greet = new GreetCommand();
        registry.register(greet);
        assertEquals("hello world", run("greet world"));
        assertEquals("hello world", run("hive HI world"), "alias, any case, optional hive");
        assertEquals("× Usage: greet <who>", run("greet"));
        assertEquals("× greet failed: java.lang.IllegalStateException: boom", run("greet boom"), "a throwing command doesn't take the console down");
        assertTrue(run("help").contains("greet <who>"));
        assertThrows(IllegalArgumentException.class, () -> registry.register(new GreetCommand()), "name taken");
        assertThrows(IllegalArgumentException.class, () -> registry.register(new HiveCommand("x", "", "", "LIST") {
            @Override public void execute(@NonNull HiveCommandSender sender, @NonNull List<String> args) {}
        }), "an alias can't take a built-in's name either");

        registry.unregister(greet);
        assertEquals("× Unknown command greet, help lists them", run("greet world"));
        assertNull(registry.find("hi"));
    }

    @Test
    void completion() {
        registry.register(new GreetCommand());
        HiveCommandSender s = sender(new ArrayList<>(), true);
        List<String> firsts = registry.complete(s, "");
        assertTrue(firsts.containsAll(List.of("list", "greet", "hi", "main", "all")), firsts.toString());
        assertEquals(registry.complete(s, ""), registry.complete(s, "hive "));
        assertEquals(List.of("world", "hive"), registry.complete(s, "greet w"));
        assertEquals(List.of("main"), registry.complete(s, "start "));
        assertEquals(List.of("never", "on-crash"), registry.complete(s, "hive set main Restart "));
        assertEquals(List.of(), registry.complete(s, "nope x"));
    }

    @Test
    void createListStatus() throws Exception {
        assertTrue(run("list").matches("(?s) +SERVER +STATE.*\n +main +CREATED +25565 +papermc *"), run("list"));
        assertTrue(run("create lobby").startsWith("» Created lobby on port 25566"));
        assertTrue(run("hive create game 30000").startsWith("» Created game on port 30000"));
        assertEquals(List.of("main", "lobby", "game"), servers.names());
        assertTrue(Files.readString(tmp.resolve(HiveConfig.FILE_NAME)).contains("- Name: game\n    Port: 30000"));
        String status = run("status game");
        assertTrue(status.startsWith("game  CREATED") && status.contains("Port        30000") && status.contains("Restart     never"), status);
    }

    @Test
    void badInputGetsAnAnswer() {
        assertTrue(run("hive").contains("list"), "a bare hive is help");
        assertEquals("× Unknown command frobnicate, help lists them", run("frobnicate"));
        assertEquals("× No server named nope; known: main", run("start nope"));
        assertEquals("× Usage: stop <name>", run("stop"));
        assertEquals("! main isn't running", run("stop main"));
        assertEquals("! main isn't running", run("main list"), "<server> <command>");
        assertEquals("! main isn't running", run("all list"));
        assertTrue(run("create main").startsWith("× Not created:"));
        assertTrue(run("create x port").startsWith("× Port must be a number"));
        assertTrue(run("attach main", false).startsWith("× attach only works on the host console"));
    }

    @Test
    void setAndDelete() {
        run("create lobby");
        ServerInstance before = servers.instance("lobby");
        assertEquals("Set Restart of lobby to on-crash", run("set lobby Restart on-crash"));
        assertEquals("Set Args of lobby to [--a, --b]", run("set lobby Args [--a, --b]"));
        assertTrue(run("status lobby").contains("Restart     on-crash"));
        assertTrue(run("set lobby Port 25565").startsWith("× Not changed:"), "main has that port");
        assertTrue(run("set lobby Colour red").contains("Colour: unknown key"));
        assertEquals("× Usage: set <name> <Key> [value]", run("set lobby"));
        assertSame(before, servers.instance("lobby"), "a new instance is only made when it starts");

        run("attach lobby");
        assertTrue(run("delete lobby").startsWith("» Deleted lobby from"));
        assertNull(servers.attached(), "deleting the attached server detaches");
        assertEquals(List.of("main"), servers.names());
        assertTrue(run("delete main").startsWith("× Not deleted:"), "the last server stays");
    }

    @Test
    void attachSilencesTheOthers() {
        run("create other");
        ServerInstance main = servers.instance("main");
        run("attach main");
        assertSame(main, servers.attached());
        run("create late");
        assertEquals(List.of(true, false, false), servers.instances().stream().map(i -> i.getConsole().isEcho()).toList());
        assertEquals("» Detached", run("detach"));
        assertNull(servers.attached());
        assertTrue(servers.instances().stream().allMatch(i -> i.getConsole().isEcho()));
        assertEquals("! Not attached", run("detach"));
    }

}
