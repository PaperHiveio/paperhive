package org.abdullahcxd.paperhive.plugin.logger;

import org.jetbrains.annotations.Nullable;
import org.abdullahcxd.paperhive.plugin.PaperhivePlugin;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.event.Level;
import org.slf4j.helpers.AbstractLogger;
import org.slf4j.spi.LoggingEventBuilder;

/**
 * Like Bukkit's {@code PluginLogger}: every message is prefixed with {@code [PluginName] }.
 */
public class PluginLogger extends AbstractLogger {

    private final Logger delegate;
    private final String prefix;

    public PluginLogger(@NotNull PaperhivePlugin plugin) {
        String pluginName = plugin.getPluginDescriptor().getName();
        this.name = pluginName;
        this.delegate = LoggerFactory.getLogger(plugin.getClass());
        this.prefix = "[" + pluginName + "] ";
    }

    @Override
    protected void handleNormalizedLoggingCall(@NotNull Level level, @Nullable Marker marker, @NotNull String msg, Object @Nullable [] args, @Nullable Throwable t) {
        LoggingEventBuilder event = delegate.makeLoggingEventBuilder(level)
                .setMessage(prefix + msg)
                .setCause(t);
        if (marker != null) event.addMarker(marker);
        if (args != null) for (Object arg : args) event.addArgument(arg);
        event.log();
    }

    @Override
    protected @Nullable String getFullyQualifiedCallerName() {
        return null;
    }

    @Override public boolean isTraceEnabled() { return delegate.isTraceEnabled(); }
    @Override public boolean isTraceEnabled(@Nullable Marker marker) { return delegate.isTraceEnabled(marker); }
    @Override public boolean isDebugEnabled() { return delegate.isDebugEnabled(); }
    @Override public boolean isDebugEnabled(@Nullable Marker marker) { return delegate.isDebugEnabled(marker); }
    @Override public boolean isInfoEnabled() { return delegate.isInfoEnabled(); }
    @Override public boolean isInfoEnabled(@Nullable Marker marker) { return delegate.isInfoEnabled(marker); }
    @Override public boolean isWarnEnabled() { return delegate.isWarnEnabled(); }
    @Override public boolean isWarnEnabled(@Nullable Marker marker) { return delegate.isWarnEnabled(marker); }
    @Override public boolean isErrorEnabled() { return delegate.isErrorEnabled(); }
    @Override public boolean isErrorEnabled(@Nullable Marker marker) { return delegate.isErrorEnabled(marker); }

}
