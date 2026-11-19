package org.abdullahcxd.paperhive.host.console;

import org.abdullahcxd.paperhive.host.server.ServerManager;
import org.jetbrains.annotations.NotNull;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.Writer;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.abdullahcxd.paperhive.host.command.CommandRegistry;
import org.abdullahcxd.paperhive.host.instance.InstanceConsole;
import org.abdullahcxd.paperhive.plugin.command.HiveCommandSender;
import org.abdullahcxd.paperhive.host.instance.ServerInstance;

import org.abdullahcxd.paperhive.plugin.command.ConsoleText;

/**
 * Lets {@code java -jar paperhive.jar hive <command>} control the host running in the same folder, through the
 * Unix domain socket {@code paperhive.sock} (local only; on Linux its file permissions decide who may use it).
 * One command per connection: a line in, the reply lines out; {@code attach <name>} keeps the connection for its session.
 */
public final class ControlSocket {

    public static final Path PATH = Path.of("paperhive.sock");

    private ControlSocket() {}

    /** Answers commands in the background for the rest of the JVM's life. */
    public static void serve(@NotNull CommandRegistry commands, @NotNull ServerManager servers) throws IOException {
        if (Files.exists(PATH)) {
            if (reachable()) throw new IOException("Another Paperhive host already runs in this folder (" + PATH.toAbsolutePath() + ")");
            Files.delete(PATH); // left over from a host that didn't exit cleanly
        }
        ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(UnixDomainSocketAddress.of(PATH));
        PATH.toFile().deleteOnExit();
        Thread accept = createAccept(commands, servers, server);
        accept.start();
    }

    private static @NotNull Thread createAccept(CommandRegistry commands, ServerManager servers, ServerSocketChannel server) {
        Thread accept = new Thread(() -> {
            while (true) {
                try {
                    SocketChannel client = server.accept();
                    Thread.ofVirtual().name("paperhive-control-client").start(() -> answer(client, commands, servers));
                } catch (IOException e) {
                    LoggerFactory.getLogger(ControlSocket.class).warn("Control socket closed", e);
                    return;
                }
            }
        }, "paperhive-control");
        accept.setDaemon(true);
        return accept;
    }

    private static void answer(SocketChannel client, CommandRegistry commands, ServerManager servers) {
        try (client;
             BufferedReader in = new BufferedReader(new InputStreamReader(Channels.newInputStream(client), StandardCharsets.UTF_8));
             Writer out = Channels.newWriter(client, StandardCharsets.UTF_8)) {
            String line = in.readLine();
            if (line == null) return;
            String[] words = line.strip().split("\\s+");
            if (words.length == 2 && words[0].equals("attach")) {
                attach(words[1], in, out, commands, servers);
                return;
            }
            commands.execute(sender(out), line);
        } catch (IOException e) {
            LoggerFactory.getLogger(ControlSocket.class).debug("Control client went away", e);
        }
    }

    /** Streams the instance's output to the client and runs its lines there, until {@code hive detach} or end of input. */
    private static void attach(String name, BufferedReader in, Writer out, CommandRegistry commands, ServerManager servers) throws IOException {
        ServerInstance target = servers.instance(name);
        if (target == null) {
            out.write("no server named " + name + "\n");
            return;
        }
        InstanceConsole.Listener listener = (line, error) -> write(out, List.of(line));
        target.getConsole().addListener(listener);
        try {
            write(out, List.of("Attached to " + name + ": lines go to it; `hive detach` or end of input (Ctrl+D, Ctrl+Z on Windows) to leave"));
            for (String raw; (raw = in.readLine()) != null; ) {
                String line = raw.strip();
                if (line.isEmpty()) continue;
                if (line.equals("hive detach")) break;
                if (line.equals("hive") || line.startsWith("hive ")) commands.execute(sender(out), line);
                else if (!target.dispatchCommand(line)) write(out, List.of(name + " isn't running"));
            }
            write(out, List.of("Detached"));
        } finally {
            target.getConsole().removeListener(listener);
        }
    }

    private static HiveCommandSender sender(Writer out) {
        return new HiveCommandSender() {
            @Override
            public void sendMessage(@NotNull String message) {
                // tools read these lines (list --tsv); colours are for the host's own console
                write(out, List.of(ConsoleText.strip(message)));
            }

            @Override
            public boolean isConsole() {
                return false;
            }
        };
    }

    private static void write(Writer out, List<String> lines) {
        synchronized (out) { // instance threads and this client's thread both write
            try {
                for (String l : lines) out.write(l + "\n");
                out.flush();
            } catch (IOException ignored) {
                // the client went away; its read loop ends too
            }
        }
    }

    private static boolean reachable() {
        try (SocketChannel ignored = SocketChannel.open(UnixDomainSocketAddress.of(PATH))) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Client side: sends {@code command} to the host in this folder and prints its reply. Returns the exit code. */
    public static int send(@NotNull String command, @NotNull PrintStream out, @NotNull PrintStream err) {
        try (SocketChannel channel = SocketChannel.open(UnixDomainSocketAddress.of(PATH))) {
            Writer writer = Channels.newWriter(channel, StandardCharsets.UTF_8);
            writer.write(command + "\n");
            writer.flush();
            if (command.strip().startsWith("attach ")) {
                // keep sending what's typed; the host ends the connection on `hive detach`
                Thread input = createInput(writer, channel);
                input.start();
            } else {
                channel.shutdownOutput();
            }
            BufferedReader in = new BufferedReader(new InputStreamReader(Channels.newInputStream(channel), StandardCharsets.UTF_8));
            for (String line; (line = in.readLine()) != null; ) out.println(line);
            return 0;
        } catch (IOException e) {
            err.println("No Paperhive host runs in this folder (" + PATH.toAbsolutePath() + "): " + e.getMessage());
            return 1;
        }
    }

    public static @NotNull Thread createInput(@NotNull Writer writer, @NotNull SocketChannel channel) {
        Thread input = new Thread(() -> {
            try (BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in))) {
                for (String line; (line = stdin.readLine()) != null; ) {
                    writer.write(line + "\n");
                    writer.flush();
                }
                channel.shutdownOutput();
            } catch (IOException ignored) {
                // the host closed the connection
            }
        }, "hive-input");
        input.setDaemon(true);
        return input;
    }

}
