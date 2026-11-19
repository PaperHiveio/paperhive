package org.abdullahcxd.paperhive.host.instance;

import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.CodeSigner;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipFile;

/**
 * Child-first loader over one instance's server jar and libraries, so every instance gets its own
 * copy of every server class and its statics. {@code java.*} and Paperhive's own classes always come from
 * the parent ({@link ApiGateLoader}), so they stay shared (even if a jar bundles a copy) and JDK classes can't be shadowed.
 * <p>
 * Classes are defined here (not by {@link URLClassLoader}) so their bytes pass through {@link InstanceTransformer}.
 * Resources still come from {@link URLClassLoader}.
 */
public final class InstanceClassLoader extends URLClassLoader {

    static {
        registerAsParallelCapable();
    }

    @Getter
    private final String instanceName;
    /** The instance this loader belongs to; null only for loaders built without one (tests). */
    @Getter
    private final @Nullable ServerInstance instance;

    private record Source(JarFile jar, URL url, ProtectionDomain domain) {}

    private final List<JarFile> openJars = new ArrayList<>();
    private final java.util.concurrent.atomic.AtomicInteger defined = new java.util.concurrent.atomic.AtomicInteger();
    private volatile Map<String, Source> classIndex;

    public InstanceClassLoader(String instanceName, URL[] urls, ApiGateLoader parent) {
        this(instanceName, urls, parent, null);
    }

    public InstanceClassLoader(String instanceName, URL[] urls, ApiGateLoader parent, @Nullable ServerInstance instance) {
        super("hive-" + instanceName, urls, parent);
        this.instanceName = instanceName;
        this.instance = instance;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = findLoadedClass(name);
            if (c == null) {
                if (parentFirst(name)) {
                    c = getParent().loadClass(name);
                } else {
                    try {
                        c = findClass(name);
                    } catch (ClassNotFoundException e) {
                        c = getParent().loadClass(name);
                    }
                }
            }
            if (resolve) resolveClass(c);
            return c;
        }
    }

    private static boolean parentFirst(String name) {
        return name.startsWith("java.") || ApiGateLoader.isShared(name);
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        String path = name.replace('.', '/').concat(".class");
        Source source = classIndex().get(path);
        if (source == null) throw new ClassNotFoundException(name);

        byte[] bytes;
        try (InputStream in = source.jar().getInputStream(source.jar().getJarEntry(path))) {
            bytes = in.readAllBytes();
        } catch (IOException e) {
            throw new ClassNotFoundException(name, e);
        }
        bytes = InstanceTransformer.transform(bytes);
        definePackageFor(name, source);
        Class<?> c = defineClass(name, bytes, 0, bytes.length, source.domain());
        defined.incrementAndGet();
        return c;
    }

    private void definePackageFor(String className, Source source) {
        int dot = className.lastIndexOf('.');
        if (dot < 0) return;
        String pkg = className.substring(0, dot);
        if (getDefinedPackage(pkg) != null) return;
        try {
            // keeps manifest data (Implementation-Version etc.), which Bukkit reads for its version string
            Manifest manifest = source.jar().getManifest();
            if (manifest != null) definePackage(pkg, manifest, source.url());
            else definePackage(pkg, null, null, null, null, null, null, null);
        } catch (IllegalArgumentException alreadyDefined) {
            // another thread defined it first
        } catch (IOException e) {
            definePackage(pkg, null, null, null, null, null, null, null);
        }
    }

    /** Class file path -> jar, first jar wins (same order as the URLs). Built on first use. */
    private Map<String, Source> classIndex() throws ClassNotFoundException {
        Map<String, Source> index = classIndex;
        if (index != null) return index;
        synchronized (openJars) {
            if (classIndex != null) return classIndex;
            index = new HashMap<>();
            try {
                for (URL url : getURLs()) {
                    if (!"file".equals(url.getProtocol())) continue;
                    File file = new File(url.toURI());
                    if (!file.isFile()) continue;
                    // multi-release aware, and not verified (same as Paperclip's own loader, which never checks signatures)
                    JarFile jar = new JarFile(file, false, ZipFile.OPEN_READ, Runtime.version());
                    openJars.add(jar);
                    Source source = new Source(jar, url,
                            new ProtectionDomain(new CodeSource(url, (CodeSigner[]) null), null, this, null));
                    for (JarEntry entry : (Iterable<JarEntry>) jar.versionedStream()::iterator) {
                        if (entry.getName().endsWith(".class")) index.putIfAbsent(entry.getName(), source);
                    }
                }
            } catch (IOException | URISyntaxException e) {
                throw new ClassNotFoundException("Could not index " + getName(), e);
            }
            classIndex = index;
            return index;
        }
    }

    /** Classes this loader defined (the server and its libraries; not its plugins', which their own loaders define). */
    public int definedClassCount() {
        return defined.get();
    }

    /** Defines a host-generated class inside this loader, so it acts as instance code (see {@link JdbcDrivers}). */
    Class<?> defineHelper(String name, byte[] bytes) {
        synchronized (getClassLoadingLock(name)) {
            Class<?> existing = findLoadedClass(name);
            return existing != null ? existing : defineClass(name, bytes, 0, bytes.length);
        }
    }

    @Override
    public URL getResource(String name) {
        URL url = findResource(name);
        return url != null ? url : getParent().getResource(name);
    }

    @Override
    public @NotNull Enumeration<URL> getResources(String name) throws IOException {
        List<URL> urls = new ArrayList<>(Collections.list(findResources(name)));
        urls.addAll(Collections.list(getParent().getResources(name)));
        return Collections.enumeration(urls);
    }

    @Override
    public void close() throws IOException {
        IOException failure = null;
        try {
            super.close();
        } catch (IOException e) {
            failure = e;
        }
        synchronized (openJars) {
            for (JarFile jar : openJars) {
                try {
                    jar.close();
                } catch (IOException e) {
                    if (failure == null) failure = e;
                    else failure.addSuppressed(e);
                }
            }
            openJars.clear();
            classIndex = Map.of();
        }
        if (failure != null) throw failure;
    }

}
