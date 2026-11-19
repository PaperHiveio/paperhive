package org.abdullahcxd.paperhive.host.instance;

import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.io.PrintStream;

/**
 * Per-instance {@code System.out}, {@code System.err} and {@code System.in}.
 * <ul>
 *   <li>Instance code: {@link InstanceTransformer} rewrites reads of {@code System.out/err/in} and calls to
 *       {@code System.setOut/setErr/setIn} into {@link #out()}, {@link #setOut} etc., which use the calling instance's
 *       own slots. So Paper redirecting {@code System.out} into its logger only affects its own instance.</li>
 *   <li>JDK code (e.g. {@code Throwable.printStackTrace()}) still reads the JVM-wide streams, so those are replaced by
 *       routing streams ({@link #install()}) that pick the instance from the current thread, or the host.</li>
 * </ul>
 * Public because rewritten instance code calls it; shared with instances through {@link ApiGateLoader}.
 */
public final class HiveIo {

    private static volatile PrintStream hostOut = System.out;
    private static volatile PrintStream hostErr = System.err;
    private static volatile InputStream hostIn = System.in;
    private static boolean installed;

    private HiveIo() {}

    /**
     * Captures the host's streams and puts routing streams in their place. Call once, after the host's logging is
     * set up (its console appender has to keep the real stdout, not a routing stream).
     */
    static synchronized void install() {
        if (installed) return;
        installed = true;
        hostOut = System.out;
        hostErr = System.err;
        hostIn = System.in;
        System.setOut(new RoutingPrintStream(true));
        System.setErr(new RoutingPrintStream(false));
        System.setIn(new RoutingInputStream());
    }

    /** The host's real stdout (instances' console echo and host-only output go here). */
    public static PrintStream hostOut() {
        return hostOut;
    }

    public static PrintStream hostErr() {
        return hostErr;
    }

    // ---- targets of rewritten instance code ----

    public static PrintStream out() {
        InstanceIo io = callerIo();
        return io == null ? System.out : io.out;
    }

    public static PrintStream err() {
        InstanceIo io = callerIo();
        return io == null ? System.err : io.err;
    }

    public static InputStream in() {
        InstanceIo io = callerIo();
        return io == null ? System.in : io.in;
    }

    public static void setOut(PrintStream out) {
        InstanceIo io = callerIo();
        if (io == null) System.setOut(out);
        else io.out = out;
    }

    public static void setErr(PrintStream err) {
        InstanceIo io = callerIo();
        if (io == null) System.setErr(err);
        else io.err = err;
    }

    public static void setIn(InputStream in) {
        InstanceIo io = callerIo();
        if (io == null) System.setIn(in);
        else io.in = in;
    }

    // ---- routing for JDK code ----

    /** The stream JDK code on this thread should write to: its instance's current slot, else the host's. */
    static PrintStream routed(boolean out) {
        InstanceIo io = threadIo();
        if (io == null) return out ? hostOut : hostErr;
        return out ? io.out : io.err;
    }

    static InputStream routedIn() {
        InstanceIo io = threadIo();
        return io == null ? hostIn : io.in;
    }

    private static @Nullable InstanceIo callerIo() {
        ServerInstance instance = InstanceHooks.callerOrNull();
        return instance == null ? null : instance.io();
    }

    private static @Nullable InstanceIo threadIo() {
        ServerInstance instance = ServerInstance.ofThread(Thread.currentThread());
        return instance == null ? null : instance.io();
    }

    /** One run's slots. They start as the instance's console sinks and an empty stdin (the host forwards commands). */
    static final class InstanceIo {
        volatile PrintStream out;
        volatile PrintStream err;
        volatile InputStream in = InputStream.nullInputStream();

        InstanceIo(InstanceConsole.Sinks sinks) {
            this.out = sinks.out();
            this.err = sinks.err();
        }
    }

}
