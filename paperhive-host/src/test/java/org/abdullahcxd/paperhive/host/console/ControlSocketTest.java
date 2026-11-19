package org.abdullahcxd.paperhive.host.console;

import org.abdullahcxd.paperhive.host.server.ServerManager;
import org.abdullahcxd.paperhive.host.command.CommandRegistry;
import org.abdullahcxd.paperhive.host.config.HiveConfig;
import org.abdullahcxd.paperhive.host.instance.ApiGateLoader;
import org.abdullahcxd.paperhive.host.instance.ServerInstance;
import org.abdullahcxd.paperhive.host.software.PreparedServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The {@code hive} client and the host's Unix domain socket (what the Velocity plugin uses too). */
class ControlSocketTest {

    @TempDir
    Path tmp;

    @Test
    void clientRunsCommandsThroughTheSocket() throws Exception {
        Path file = tmp.resolve(HiveConfig.FILE_NAME);
        PreparedServer server = new PreparedServer(tmp.resolve("none.jar"), List.of(), "none.Main", () -> {});
        ApiGateLoader gate = new ApiGateLoader(getClass().getClassLoader());
        ServerManager servers = new ServerManager(file, HiveConfig.load(file), List.of(), s -> List.of(),
                s -> new ServerInstance(s.name(), tmp.resolve(s.root()), server, gate, s.options()));
        CommandRegistry commands = CommandRegistry.withBuiltins(servers, name -> null);

        // the socket lives in the working directory (build/test-work); one left by an earlier run isn't reachable
        assertEquals(1, send("list").code(), "no host yet");
        ControlSocket.serve(commands, servers);

        Reply tsv = send("list --tsv");
        assertEquals(0, tsv.code(), tsv.err());
        assertEquals("main\tCREATED\t25565\tpapermc", tsv.out().strip(), "the stable format the Velocity plugin reads");
        assertTrue(send("hive status main").out().startsWith("main: CREATED"), "an optional leading hive works too");

        IOException second = assertThrows(IOException.class, () -> ControlSocket.serve(commands, servers));
        assertTrue(second.getMessage().contains("already runs"), second.getMessage());
        assertTrue(Files.exists(ControlSocket.PATH));
    }

    private record Reply(int code, String out, String err) {}

    private static Reply send(String command) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(), err = new ByteArrayOutputStream();
        int code = ControlSocket.send(command, new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Reply(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

}
