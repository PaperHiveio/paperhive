package org.abdullahcxd.paperhive.host.instance;

import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Per-instance {@code java.util.logging} namespace. JUL has one JVM-wide {@link LogManager}: Paper puts its forwarding
 * handler on the root logger, and plugin loggers are registered by name (a second instance asking for "spark" would
 * get the first instance's logger). So {@link InstanceTransformer} sends instance code's {@code Logger.getLogger},
 * {@code Logger.getGlobal} and {@code LogManager.getLogger/addLogger/getLoggerNames/reset} here: each instance gets
 * its own tree of loggers with its own root, never registered JVM-wide, dropped when the instance stops.
 * <p>
 * Public because rewritten instance code calls it; shared with instances through {@link ApiGateLoader}.
 */
public final class HiveJul {

    private HiveJul() {}

    public static Logger getLogger(String name) {
        Namespace ns = callerNamespace();
        return ns == null ? Logger.getLogger(name) : ns.getOrCreate(name);
    }

    public static Logger getLogger(String name, String resourceBundleName) {
        Namespace ns = callerNamespace();
        return ns == null ? Logger.getLogger(name, resourceBundleName) : ns.getOrCreate(name);
    }

    public static Logger getGlobal() {
        Namespace ns = callerNamespace();
        return ns == null ? Logger.getGlobal() : ns.getOrCreate(Logger.GLOBAL_LOGGER_NAME);
    }

    public static @Nullable Logger getLogger(LogManager manager, String name) {
        Namespace ns = callerNamespace();
        return ns == null ? manager.getLogger(name) : ns.get(name);
    }

    public static boolean addLogger(LogManager manager, Logger logger) {
        Namespace ns = callerNamespace();
        return ns == null ? manager.addLogger(logger) : ns.add(logger);
    }

    public static Enumeration<String> getLoggerNames(LogManager manager) {
        Namespace ns = callerNamespace();
        return ns == null ? manager.getLoggerNames() : ns.names();
    }

    public static void reset(LogManager manager) {
        Namespace ns = callerNamespace();
        if (ns == null) manager.reset();
        else ns.reset();
    }

    private static boolean installed;

    /**
     * Records that reach the JVM-wide root logger from an instance thread (JDK-internal loggers, {@code System.Logger},
     * anything the rewrite didn't catch) go to that instance's root instead, so into its own log. The root's existing
     * handlers move behind a router and keep getting everything else.
     * shortcut: a later host {@code LogManager.reset/readConfiguration} drops the router; call this again if the host starts doing that.
     */
    static synchronized void install() {
        if (installed) return;
        installed = true;
        Logger jvmRoot = Logger.getLogger("");
        Handler[] host = jvmRoot.getHandlers();
        for (Handler handler : host) jvmRoot.removeHandler(handler);
        jvmRoot.addHandler(new Router(host));
    }

    private static final class Router extends Handler {
        private final Handler[] host;

        Router(Handler[] host) {
            this.host = host;
        }

        @Override
        public void publish(LogRecord record) {
            ServerInstance instance = ServerInstance.ofThread(Thread.currentThread());
            Namespace ns = instance == null ? null : instance.julNamespace();
            if (ns != null) {
                ns.root.log(record);
            } else {
                for (Handler handler : host) handler.publish(record);
            }
        }

        @Override
        public void flush() {
            for (Handler handler : host) handler.flush();
        }

        @Override
        public void close() {
            for (Handler handler : host) handler.close();
        }
    }

    private static @Nullable Namespace callerNamespace() {
        ServerInstance instance = InstanceHooks.callerOrNull();
        return instance == null ? null : instance.julNamespace();
    }

    /** One instance's loggers by name, wired into a hierarchy like {@link LogManager} does, under a private root. */
    static final class Namespace {

        private final Map<String, Logger> loggers = new HashMap<>();
        private final Logger root = new NamespaceLogger("");

        Namespace() {
            root.setLevel(Level.INFO);
            loggers.put("", root);
        }

        synchronized Logger getOrCreate(String name) {
            Logger logger = loggers.get(name);
            if (logger == null) {
                logger = new NamespaceLogger(name);
                add(logger);
            }
            return logger;
        }

        synchronized @Nullable Logger get(String name) {
            return loggers.get(name);
        }

        /** Like {@link LogManager#addLogger}: false if the name is taken; otherwise links it into the hierarchy. */
        synchronized boolean add(Logger logger) {
            String name = logger.getName();
            if (name == null || loggers.containsKey(name)) return false;
            loggers.put(name, logger);
            logger.setParent(nearestAncestor(name));
            // existing descendants that hung off a shallower ancestor now belong under this logger
            for (Logger other : loggers.values()) {
                String otherName = other.getName();
                if (other != logger && !name.isEmpty() && otherName.startsWith(name + ".")
                        && depth(nearestAncestor(otherName).getName()) <= depth(name)) {
                    other.setParent(logger);
                }
            }
            return true;
        }

        synchronized Enumeration<String> names() {
            return Collections.enumeration(new java.util.ArrayList<>(loggers.keySet()));
        }

        synchronized void reset() {
            for (Logger logger : loggers.values()) {
                for (Handler handler : logger.getHandlers()) {
                    logger.removeHandler(handler);
                    try {
                        handler.close();
                    } catch (RuntimeException ignored) {
                    }
                }
                logger.setLevel(logger == root ? Level.INFO : null);
            }
        }

        private Logger nearestAncestor(String name) {
            for (int dot = name.lastIndexOf('.'); dot > 0; dot = name.lastIndexOf('.', dot - 1)) {
                Logger ancestor = loggers.get(name.substring(0, dot));
                if (ancestor != null) return ancestor;
            }
            return root;
        }

        private static int depth(String name) {
            return name.isEmpty() ? 0 : (int) name.chars().filter(c -> c == '.').count() + 1;
        }
    }

    /** A logger that isn't registered with the JVM-wide {@link LogManager}. */
    private static final class NamespaceLogger extends Logger {
        private NamespaceLogger(String name) {
            super(name, null);
        }
    }

}
