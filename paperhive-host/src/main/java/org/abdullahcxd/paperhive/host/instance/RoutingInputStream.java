package org.abdullahcxd.paperhive.host.instance;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;

/** The JVM-wide {@code System.in} once {@link HiveIo#install()} ran: the current thread's instance stdin, or the host's. */
final class RoutingInputStream extends InputStream {

    @Override public int read() throws IOException { return HiveIo.routedIn().read(); }
    @Override public int read(byte @NotNull [] b, int off, int len) throws IOException { return HiveIo.routedIn().read(b, off, len); }
    @Override public int available() throws IOException { return HiveIo.routedIn().available(); }
    @Override public long skip(long n) throws IOException { return HiveIo.routedIn().skip(n); }
    @Override public void close() { /* the JVM's stdin is never closed through here */ }

}
