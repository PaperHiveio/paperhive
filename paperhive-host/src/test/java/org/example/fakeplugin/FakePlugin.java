package org.example.fakeplugin;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Stand-in plugin, packed into its own jar and loaded by {@code FakeServer}'s plugin loader (like Paper's
 * {@code PluginClassLoader}), not by the instance loader.
 */
public final class FakePlugin {

    public static void run() throws SQLException {
        // a JDBC driver of the plugin's own loader, like a plugin shading MySQL: it must be deregistered on stop
        DriverManager.registerDriver(new PluginDriver());
        System.exit(11); // must end only the instance, never the JVM
    }

    public static final class PluginDriver implements java.sql.Driver {
        @Override public Connection connect(String url, Properties info) { return null; }
        @Override public boolean acceptsURL(String url) { return false; }
        @Override public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) { return new DriverPropertyInfo[0]; }
        @Override public int getMajorVersion() { return 1; }
        @Override public int getMinorVersion() { return 0; }
        @Override public boolean jdbcCompliant() { return false; }
        @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { throw new SQLFeatureNotSupportedException(); }
    }

}
