package org.abdullahcxd.paperhive.host.console;

import org.abdullahcxd.paperhive.host.server.ServerManager;
import org.abdullahcxd.paperhive.host.command.CommandRegistry;
import org.abdullahcxd.paperhive.host.instance.ServerInstance;
import org.abdullahcxd.paperhive.plugin.command.HiveCommandSender;
import org.jetbrains.annotations.NotNull;
import org.jline.reader.Candidate;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.jline.terminal.TerminalBuilder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
        PrintStream above = new PrintStream(new AboveOutput(reader), true, StandardCharsets.UTF_8);
        System.setOut(above);
        System.setErr(above);
        return self[0];
    }

    /**
     * The Paperhive logo, a honeycomb, with the version; in colour where the terminal has it (JLine drops the colours
     * for a plain one, e.g. a service's log).
     */
    public void banner(@NotNull String version) {
        AttributedStyle comb = AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW);
        AttributedStyle bold = AttributedStyle.BOLD.foreground(AttributedStyle.YELLOW);
        AttributedStyle name = AttributedStyle.BOLD.foreground(AttributedStyle.WHITE);
        AttributedStyle dim = AttributedStyle.DEFAULT.foreground(AttributedStyle.BRIGHT + AttributedStyle.BLACK);
        AttributedStyle link = AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN);
        String[] cells = {
                "      __    __",
                "   __/  \\__/  \\__",
                "  /  \\__/  \\__/  \\",
                "  \\__/  \\__/  \\__/",
                "     \\__/  \\__/",
        };
        AttributedString[] text = {
                null,
                new AttributedStringBuilder().style(name).append("Paperhive").style(dim).append("  v" + version).toAttributedString(),
                new AttributedStringBuilder().style(AttributedStyle.DEFAULT).append("One JVM, many Paper servers").toAttributedString(),
                new AttributedStringBuilder().style(link).append("https://paperhive.abdullahcxd.org").toAttributedString(),
                null,
        };
        StringBuilder out = new StringBuilder("\n");
        for (int i = 0; i < cells.length; i++) {
            AttributedStringBuilder line = new AttributedStringBuilder().style(i % 2 == 0 ? comb : bold).append(cells[i]);
            if (text[i] != null) line.append(" ".repeat(Math.max(1, 24 - cells[i].length()))).append(text[i]);
            out.append(line.toAttributedString().toAnsi(terminal)).append('\n');
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
                line = reader.readLine(dumb ? "" : (attached == null ? "hive" : attached.getName()) + "> ");
            } catch (UserInterruptException e) { // Ctrl+C at the prompt
                line = "shutdown";
            } catch (EndOfFileException e) {
                return; // input ended (e.g. a service): the servers keep running, `hive` clients still work
            }
            handle(line);
        }
    }

    private void handle(@NotNull String raw) {
        String line = raw.strip();
        if (line.isEmpty()) return;
        ServerInstance attached = servers.attached();
        if (attached != null && !line.equals("hive") && !line.startsWith("hive ")) {
            if (!attached.dispatchCommand(line)) sender.sendMessage(attached.getName() + " isn't running");
            return;
        }
        registry.execute(sender, line);
    }

    /** Whole lines go to {@link LineReader#printAbove}, which redraws the prompt and what's typed under them. */
    private static final class AboveOutput extends OutputStream {
        private final LineReader reader;
        private final ByteArrayOutputStream line = new ByteArrayOutputStream();

        private AboveOutput(LineReader reader) {
            this.reader = reader;
        }

        @Override
        public synchronized void write(int b) {
            if (b != '\n') {
                line.write(b);
                return;
            }
            String text = line.toString(StandardCharsets.UTF_8);
            line.reset();
            reader.printAbove(text.endsWith("\r") ? text.substring(0, text.length() - 1) : text);
        }
    }

}
