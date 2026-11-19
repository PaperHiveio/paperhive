package org.abdullahcxd.paperhive.host.instance;

import org.jetbrains.annotations.NotNull;

import java.io.OutputStream;
import java.io.PrintStream;
import java.util.Locale;

/**
 * The JVM-wide {@code System.out}/{@code System.err} once {@link HiveIo#install()} ran: every call goes to the
 * current thread's instance stream, or the host's. Delegates whole calls (not bytes), so each target keeps its own
 * charset and {@code println} stays one call.
 */
final class RoutingPrintStream extends PrintStream {

    private final boolean out;

    RoutingPrintStream(boolean out) {
        super(OutputStream.nullOutputStream(), true);
        this.out = out;
    }

    private PrintStream target() {
        return HiveIo.routed(out);
    }

    @Override public void flush() { target().flush(); }
    @Override public void close() { /* the JVM's stdout/stderr is never closed through here */ }
    @Override public boolean checkError() { return target().checkError(); }
    @Override public void write(int b) { target().write(b); }
    @Override public void write(byte @NotNull [] buf, int off, int len) { target().write(buf, off, len); }
    @Override public void write(byte @NotNull [] buf) throws java.io.IOException { target().write(buf); }
    @Override public void writeBytes(byte[] buf) { target().writeBytes(buf); }
    @Override public void print(boolean b) { target().print(b); }
    @Override public void print(char c) { target().print(c); }
    @Override public void print(int i) { target().print(i); }
    @Override public void print(long l) { target().print(l); }
    @Override public void print(float f) { target().print(f); }
    @Override public void print(double d) { target().print(d); }
    @Override public void print(char @NotNull [] s) { target().print(s); }
    @Override public void print(String s) { target().print(s); }
    @Override public void print(Object obj) { target().print(obj); }
    @Override public void println() { target().println(); }
    @Override public void println(boolean x) { target().println(x); }
    @Override public void println(char x) { target().println(x); }
    @Override public void println(int x) { target().println(x); }
    @Override public void println(long x) { target().println(x); }
    @Override public void println(float x) { target().println(x); }
    @Override public void println(double x) { target().println(x); }
    @Override public void println(char @NotNull [] x) { target().println(x); }
    @Override public void println(String x) { target().println(x); }
    @Override public void println(Object x) { target().println(x); }
    @Override public PrintStream printf(@NotNull String format, Object... args) { return target().printf(format, args); }
    @Override public PrintStream printf(Locale l, @NotNull String format, Object... args) { return target().printf(l, format, args); }
    @Override public PrintStream format(@NotNull String format, Object... args) { return target().format(format, args); }
    @Override public PrintStream format(Locale l, @NotNull String format, Object... args) { return target().format(l, format, args); }
    @Override public PrintStream append(CharSequence csq) { return target().append(csq); }
    @Override public PrintStream append(CharSequence csq, int start, int end) { return target().append(csq, start, end); }
    @Override public PrintStream append(char c) { return target().append(c); }

}
