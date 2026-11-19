package org.abdullahcxd.paperhive.host.instance;

import org.abdullahcxd.paperhive.api.Paperhive;
import org.abdullahcxd.paperhive.host.software.paper.PaperclipCache;
import org.abdullahcxd.paperhive.plugin.PaperhivePlugin;
import org.example.isolated.IsolatedCounter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class InstanceClassLoaderTest {

    @TempDir
    Path tmp;

    private final ApiGateLoader gate = new ApiGateLoader(getClass().getClassLoader());

    @Test
    void gateSharesPaperhiveAndJdkOnly() throws ClassNotFoundException {
        assertSame(Paperhive.class, gate.loadClass(Paperhive.class.getName()));
        assertSame(PaperclipCache.class, gate.loadClass(PaperclipCache.class.getName()), "host classes are shared");
        assertSame(PaperhivePlugin.class, gate.loadClass(PaperhivePlugin.class.getName()), "plugin classes are shared");
        assertSame(String.class, gate.loadClass("java.lang.String"));
        assertNotNull(gate.loadClass("java.sql.Connection"), "platform modules must be visible");

        // the host's third-party libraries stay hidden, so they can't clash with Paper's copies
        assertThrows(ClassNotFoundException.class, () -> gate.loadClass(IsolatedCounter.class.getName()));
        assertThrows(ClassNotFoundException.class, () -> gate.loadClass("org.slf4j.Logger"));
        assertThrows(ClassNotFoundException.class, () -> gate.loadClass("org.apache.logging.log4j.LogManager"));
    }

    @Test
    void eachInstanceGetsItsOwnClassesAndStatics() throws Exception {
        URL[] urls = {counterJar().toUri().toURL()};
        try (InstanceClassLoader a = new InstanceClassLoader("a", urls, gate);
             InstanceClassLoader b = new InstanceClassLoader("b", urls, gate)) {
            Class<?> ca = a.loadClass(IsolatedCounter.class.getName());
            Class<?> cb = b.loadClass(IsolatedCounter.class.getName());

            assertNotSame(ca, cb);
            assertSame(a, ca.getClassLoader());
            assertNotSame(IsolatedCounter.class, ca);

            assertEquals(1, ca.getMethod("increment").invoke(null));
            assertEquals(2, ca.getMethod("increment").invoke(null));
            assertEquals(1, cb.getMethod("increment").invoke(null), "b must not see a's static");
        }
    }

    @Test
    void paperhiveIsSharedThroughInstances() throws Exception {
        try (InstanceClassLoader a = new InstanceClassLoader("a", new URL[0], gate);
             InstanceClassLoader b = new InstanceClassLoader("b", new URL[0], gate)) {
            assertSame(Paperhive.class, a.loadClass(Paperhive.class.getName()));
            assertSame(Paperhive.class, b.loadClass(Paperhive.class.getName()));
            assertSame(PaperclipCache.class, a.loadClass(PaperclipCache.class.getName()));
            assertSame(PaperhivePlugin.class, b.loadClass(PaperhivePlugin.class.getName()));
        }
    }

    @Test
    void ownResourcesWinOverParent() throws Exception {
        try (InstanceClassLoader a = new InstanceClassLoader("a", new URL[]{counterJar().toUri().toURL()}, gate)) {
            assertNotNull(a.getResource("hive.txt"));
            // getResourceAsStream skips the JDK jar cache, so the jar is closed with the loader (Windows can delete it)
            try (InputStream in = a.getResourceAsStream("hive.txt")) {
                assertNotNull(in);
                assertEquals("instance", new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            assertNull(a.getResource("log4j2.xml"), "host resources must not leak into instances");
        }
    }

    private Path counterJar() throws IOException {
        Path jar = tmp.resolve("counter.jar");
        if (Files.exists(jar)) return jar;
        String classFile = IsolatedCounter.class.getName().replace('.', '/') + ".class";
        try (OutputStream file = Files.newOutputStream(jar);
             JarOutputStream out = new JarOutputStream(file);
             InputStream classBytes = getClass().getClassLoader().getResourceAsStream(classFile)) {
            assertNotNull(classBytes, classFile);
            out.putNextEntry(new JarEntry(classFile));
            out.write(classBytes.readAllBytes());
            out.closeEntry();
            out.putNextEntry(new JarEntry("hive.txt"));
            out.write("instance".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return jar;
    }

}
