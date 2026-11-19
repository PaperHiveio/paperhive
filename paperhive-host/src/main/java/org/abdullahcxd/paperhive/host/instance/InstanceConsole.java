package org.abdullahcxd.paperhive.host.instance;

import lombok.Getter;
import lombok.Setter;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * One instance's console output: what the instance writes to its {@code System.out}/{@code System.err}
 * (Paper's log appender included), split into lines and handed to listeners. It's the same object across restarts.
 * <p>
 * By default each line is also echoed to the host's stdout/stderr, prefixed with {@code [name]}.
 */
public final class InstanceConsole {

    @FunctionalInterface
    public interface Listener {
        void line(String line, boolean error);
    }

    private final String name;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    /**
     * -- SETTER --
     * Whether lines are echoed to the host's console, prefixed with
     * .
     */
    @Getter
    @Setter
    private volatile boolean echo = true;

    InstanceConsole(String name) {
        this.name = name;
    }

    public void addListener(@NotNull Listener listener) {
        listeners.add(listener);
    }

    public void removeListener(@NotNull Listener listener) {
        listeners.remove(listener);
    }

    /** A fresh pair of sinks for one run of the instance; its {@code System.out}/{@code err} start out as these. */
    Sinks newSinks() {
        return new Sinks(new PrintStream(new LineSink(false), true, StandardCharsets.UTF_8),
                new PrintStream(new LineSink(true), true, StandardCharsets.UTF_8));
    }

    record Sinks(PrintStream out, PrintStream err) {
        void flush() {
            out.flush();
            err.flush();
        }
    }

    private void emit(String line, boolean error) {
        if (echo) (error ? HiveIo.hostErr() : HiveIo.hostOut()).println("[" + name + "] " + line);
        for (Listener listener : listeners) {
            try {
                listener.line(line, error);
            } catch (RuntimeException ignored) {
                // a broken listener must not break the instance's output
            }
        }
    }

    /** Buffers bytes until a line ends; a partial last line goes out on flush. */
    private final class LineSink extends OutputStream {
        private final boolean error;
        private final ByteArrayOutputStream line = new ByteArrayOutputStream();

        private LineSink(boolean error) {
            this.error = error;
        }

        @Override
        public synchronized void write(int b) {
            if (b == '\n') {
                emitLine();
            } else {
                line.write(b);
            }
        }

        @Override
        public synchronized void write(byte @NotNull [] b, int off, int len) {
            for (int i = off; i < off + len; i++) write(b[i]);
        }

        @Override
        public synchronized void flush() {
            // PrintStream flushes after every println; only a line without '\n' is still buffered then
        }

        @Override
        public synchronized void close() {
            if (line.size() > 0) emitLine();
        }

        private void emitLine() {
            String text = line.toString(StandardCharsets.UTF_8);
            line.reset();
            if (text.endsWith("\r")) text = text.substring(0, text.length() - 1);
            emit(text, error);
        }
    }

}
