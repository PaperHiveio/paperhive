package org.abdullahcxd.paperhive.host.console;

import org.abdullahcxd.paperhive.plugin.command.ConsoleText;
import org.abdullahcxd.paperhive.host.server.ServerManager;
import org.abdullahcxd.paperhive.host.command.CommandRegistry;
import org.abdullahcxd.paperhive.host.instance.ServerInstance;
import org.abdullahcxd.paperhive.plugin.command.HiveCommandSender;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jline.reader.Candidate;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.IOError;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * The host's {@code hive>} prompt: line editing, history ({@code .paperhive-history}) and tab completion of commands,
 * server names and arguments. Everything printed (logs, server output) goes above the prompt, so typing isn't torn up.
 * With input that isn't a terminal (piped, a service) it reads plain lines without a prompt.
 */
public final class HiveConsole {

    private final Terminal terminal;
    private final LineReader reader;
    private volatile CommandRegistry registry;
    private volatile ServerManager servers;
    private final HiveCommandSender sender = new HiveCommandSender() {
        @Override
        public void sendMessage(@NotNull String message) {
            System.out.println(message);
        }

        @Override
        public boolean isConsole() {
            return true;
        }
    };

    private HiveConsole(Terminal terminal, LineReader reader) {
        this.terminal = terminal;
        this.reader = reader;
    }

    /** Opens the terminal and points {@code System.out}/{@code err} above the prompt; before anything logs. */
    public static @NotNull HiveConsole open() throws IOException {
        Terminal terminal = TerminalBuilder.builder().system(true).dumb(true).build();
        HiveConsole[] self = new HiveConsole[1];
        LineReader reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .appName("paperhive")
                .variable(LineReader.HISTORY_FILE, Path.of(".paperhive-history").toAbsolutePath())
                .completer((r, line, candidates) -> {
                    HiveConsole console = self[0];
                    if (console.registry == null) return;
                    String typed = line.line().substring(0, line.cursor());
                    // while attached, plain lines are the server's
                    if (console.servers.attached() != null && !typed.startsWith("hive ")) return;
                    for (String s : console.registry.complete(console.sender, typed)) candidates.add(new Candidate(s));
                })
                .build();
        self[0] = new HiveConsole(terminal, reader);
        PrintStream above = new PrintStream(new AboveOutput(reader, System.out), true, StandardCharsets.UTF_8);
        System.setOut(above);
        System.setErr(above);
        return self[0];
    }

    /**
     * The Paperhive logo, a honeycomb, with the version; in colour where the terminal has it (the console drops the
     * colours for a plain one, e.g. a service's log).
     */
    public void banner(@NotNull String version) {
        String[] cells = {
                "      __    __",
                "   __/  \\__/  \\__",
                "  /  \\__/  \\__/  \\",
                "  \\__/  \\__/  \\__/",
                "     \\__/  \\__/",
        };
        String[] text = {
                "",
                ConsoleText.value("Paperhive") + ConsoleText.muted("  v" + version),
                "One JVM, many Paper servers",
                ConsoleText.command("https://paperhive.abdullahcxd.org"),
                "",
        };
        StringBuilder out = new StringBuilder("\n");
        for (int i = 0; i < cells.length; i++) {
            out.append(ConsoleText.pad(ConsoleText.name(cells[i]), 24)).append(text[i]).append('\n');
        }
        System.out.print(out);
    }

    /** Reads commands in the background for the rest of the JVM's life (or until input ends). */
    public void start(@NotNull CommandRegistry registry, @NotNull ServerManager servers) {
        this.registry = registry;
        this.servers = servers;
        Thread thread = new Thread(this::loop, "paperhive-console");
        thread.setDaemon(true);
        thread.start();
    }

    private void loop() {
        boolean dumb = terminal.getType().startsWith(Terminal.TYPE_DUMB);
        while (true) {
            ServerInstance attached = servers.attached();
            String line;
            try {
                line = reader.readLine(dumb ? "" : prompt(attached));
            } catch (UserInterruptException e) { // Ctrl+C at the prompt
                line = "shutdown";
            } catch (EndOfFileException e) {
                return; // input ended (e.g. a service): the servers keep running, `hive` clients still work
            } catch (IllegalStateException | IOError | UncheckedIOException e) {
                // the terminal was closed under the prompt: JLine's shutdown hook does that while the JVM exits
                return;
            }
            handle(line);
        }
    }

    /** {@code hive > }, or the attached server's name (in honey) while attached. */
    private static @NotNull String prompt(@Nullable ServerInstance attached) {
        return attached == null ? ConsoleText.name("hive") + ConsoleText.muted(" > ")
                : ConsoleText.name(attached.getName()) + ConsoleText.muted(" (attached) > ");
    }

    private void handle(@NotNull String raw) {
        String line = raw.strip();
        if (line.isEmpty()) return;
        ServerInstance attached = servers.attached();
        if (attached != null && !line.equals("hive") && !line.startsWith("hive ")) {
            if (!attached.dispatchCommand(line)) sender.sendMessage(ConsoleText.warning(ConsoleText.name(attached.getName()) + " isn't running"));
            return;
        }
        registry.execute(sender, line);
    }

    /** Whole lines go to {@link LineReader#printAbove}, which redraws the prompt and what's typed under them. */
    private static final class AboveOutput extends OutputStream {
        private final LineReader reader;
        private final PrintStream fallback;
        private final ByteArrayOutputStream line = new ByteArrayOutputStream();

        private final boolean plain;
        private boolean terminalGone;

        /** @param fallback the JVM's own standard output, for when the terminal is gone */
        private AboveOutput(@NotNull LineReader reader, PrintStream fallback) {
            this.reader = reader;
            this.fallback = fallback;
            this.plain = reader.getTerminal().getType().equals(Terminal.TYPE_DUMB); // "dumb-color" keeps them
        }

        @Override
        public synchronized void write(int b) {
            if (b != '\n') {
                line.write(b);
                return;
            }
            String text = line.toString(StandardCharsets.UTF_8);
            line.reset();
            if (text.endsWith("\r")) text = text.substring(0, text.length() - 1);
            // JLine passes text through as is when it isn't reading a line; without a colour terminal, drop the colours
            if (!terminalGone) {
                try {
                    reader.printAbove(plain ? ConsoleText.strip(text) : text);
                    return;
                } catch (IllegalStateException | IOError | UncheckedIOException e) {
                    // closed by JLine's shutdown hook while the JVM exits; what's still said (shutdown logs, an
                    // uncaught exception's trace) goes straight to standard output instead of failing in turn
                    terminalGone = true;
                }
            }
            fallback.println(ConsoleText.strip(text));
        }
    }

}
