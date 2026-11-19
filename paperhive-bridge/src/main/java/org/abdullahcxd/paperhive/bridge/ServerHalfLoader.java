package org.abdullahcxd.paperhive.bridge;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NonNull;

import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;

/**
 * One server half's classes. Its own jar first: a lookup of its own class doesn't go through Paper's plugin loader
 * (which searches every other plugin on a miss), and libraries it shades stay its own. The Java, Bukkit/Paper,
 * Adventure, Brigadier, logging and Paperhive APIs always come from the server, so they're the same classes there.
 */
final class ServerHalfLoader extends URLClassLoader {

    static {
        registerAsParallelCapable();
    }

    private static final String[] SHARED = {"java.", "javax.", "jdk.", "sun.", "org.bukkit.", "io.papermc.", "com.destroystokyo.paper.",
            "org.spigotmc.", "net.kyori.", "com.mojang.", "net.minecraft.", "org.slf4j.", "org.apache.logging.", "org.jetbrains.annotations.",
            "org.jspecify.", "org.abdullahcxd.paperhive."};

    ServerHalfLoader(String name, @NonNull Path jar, ClassLoader bridge) throws MalformedURLException {
        super("hive-server-" + name, new URL[]{jar.toUri().toURL()}, bridge);
    }

    @Override
    protected Class<?> loadClass(@NotNull String name, boolean resolve) throws ClassNotFoundException {
        if (shared(name)) return super.loadClass(name, resolve);
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = findLoadedClass(name);
            if (c == null) {
                try {
                    c = findClass(name);
                } catch (ClassNotFoundException notOurs) {
                    c = getParent().loadClass(name); // other plugins' and the server's libraries
                }
            }
            if (resolve) resolveClass(c);
            return c;
        }
    }

    @Override
    public @Nullable URL getResource(@NotNull String name) {
        URL own = findResource(name); // its config.yml, not some other plugin's
        return own != null ? own : super.getResource(name);
    }

    private static boolean shared(String name) {
        for (String prefix : SHARED) if (name.startsWith(prefix)) return true;
        return false;
    }

}
