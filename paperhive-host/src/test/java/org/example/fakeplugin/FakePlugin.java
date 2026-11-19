package org.example.fakeplugin;

/**
 * Stand-in plugin, packed into its own jar and loaded by {@code FakeServer}'s plugin loader (like Paper's
 * {@code PluginClassLoader}), not by the instance loader.
 */
public final class FakePlugin {

    public static void run() {
        System.exit(11); // must end only the instance, never the JVM
    }

}
