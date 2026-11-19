package org.abdullahcxd.paperhive.host.instance;

import lombok.extern.slf4j.Slf4j;

/**
 * Shuts down an instance's own Log4j (Paper's copy, in the instance loader) once the instance has stopped, which
 * closes its appenders' files ({@code logs/latest.log}). Paper would leave that to its JVM exit; here the files would
 * stay open until a GC collected them, and Windows can't rotate (rename) or delete an open file, so the server's next
 * start couldn't roll its log.
 */
@Slf4j
final class InstanceLog4j {

    private static final String LOG_MANAGER = "org.apache.logging.log4j.LogManager";
    private static final String CONTEXT = "org.apache.logging.log4j.spi.LoggerContext";

    private InstanceLog4j() {}

    static void shutdown(InstanceClassLoader loader) {
        if (!loader.hasLoaded(LOG_MANAGER)) return; // never used Log4j: nothing to close, and don't start it now
        try {
            Class<?> logManager = Class.forName(LOG_MANAGER, false, loader);
            Class<?> context = Class.forName(CONTEXT, false, loader);
            // the context of the instance loader: the one Paper's classes (defined by it) log through
            Object ctx = logManager.getMethod("getContext", ClassLoader.class, boolean.class).invoke(null, loader, false);
            logManager.getMethod("shutdown", context).invoke(null, ctx);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            log.warn("Could not shut down the logging of instance {}; its log files close when it's garbage collected",
                    loader.getInstanceName(), e);
        }
    }

}
