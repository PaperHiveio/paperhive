package org.abdullahcxd.paperhive.bridge;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NonNull;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.nio.file.Path;
import java.security.CodeSigner;
import java.security.CodeSource;

/**
 * One server half's classes. Its own jar first: a lookup of its own class doesn't go through Paper's plugin loader
 * (which searches every other plugin on a miss), and libraries it shades stay its own. The Java, Bukkit/Paper,
 * Adventure, Brigadier, logging and Paperhive APIs always come from the server, so they're the same classes there.
 * <p>
 * It defines its classes itself (not through {@code URLClassLoader}'s JDK code), so Paperhive rewrites them like
 * Paper's plugin classes: a server half calling {@code System.exit} ends only its server, relative files land in the
 * server's folder, and its JDBC drivers are deregistered when the server stops.
 */
final class ServerHalfLoader extends URLClassLoader {

    static {
        registerAsParallelCapable();
    }

    private static final String[] SHARED = {"java.", "javax.", "jdk.", "sun.", "org.bukkit.", "io.papermc.", "com.destroystokyo.paper.",
            "org.spigotmc.", "net.kyori.", "com.mojang.", "net.minecraft.", "org.slf4j.", "org.apache.logging.", "org.jetbrains.annotations.",
            "org.jspecify.", "org.abdullahcxd.paperhive."};

    private final CodeSource codeSource;

    ServerHalfLoader(String name, @NonNull Path jar, ClassLoader bridge) throws MalformedURLException {
        super("hive-server-" + name, new URL[]{jar.toUri().toURL()}, bridge);
        codeSource = new CodeSource(jar.toUri().toURL(), (CodeSigner[]) null);
    }

    @Override
    protected @NotNull Class<?> findClass(@NotNull String name) throws ClassNotFoundException {
        URL url = findResource(name.replace('.', '/') + ".class");
        if (url == null) throw new ClassNotFoundException(name);
        byte[] bytes;
        try {
            URLConnection connection = url.openConnection();
            connection.setUseCaches(false); // a cached JarFile would keep the jar open (locked on Windows) after this loader closes
            try (InputStream in = connection.getInputStream()) {
                bytes = in.readAllBytes();
            }
        } catch (IOException e) {
            throw new ClassNotFoundException(name, e);
        }
        int dot = name.lastIndexOf('.');
        if (dot > 0 && getDefinedPackage(name.substring(0, dot)) == null) {
            try {
                definePackage(name.substring(0, dot), null, null, null, null, null, null, null);
            } catch (IllegalArgumentException definedMeanwhile) {
                // another thread defined it first: fine
            }
        }
        // this call is what Paperhive rewrites (it's in the bridge, which the server loads like any plugin)
        return defineClass(name, bytes, 0, bytes.length, codeSource);
    }

    @Override
    protected @NotNull Class<?> loadClass(@NotNull String name, boolean resolve) throws ClassNotFoundException {
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
