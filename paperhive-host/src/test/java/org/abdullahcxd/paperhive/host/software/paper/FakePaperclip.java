package org.abdullahcxd.paperhive.host.software.paper;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;

/**
 * Stand-in for Paperclip, packed alone into a jar by {@link PaperclipCacheTest}, so it must not use any other class.
 * Behaviour comes from {@code /fake.properties} in that jar: version, sleepMs, exitCode.
 */
public final class FakePaperclip {

    public static void main(String[] args) throws Exception {
        if (!"true".equals(System.getProperty("paperclip.patchonly"))) System.exit(2);

        Properties props = new Properties();
        try (InputStream in = FakePaperclip.class.getResourceAsStream("/fake.properties")) {
            props.load(in);
        }
        String version = props.getProperty("version");

        // one line per run, so tests can count how often patching happened
        Files.writeString(Path.of("patch-runs.txt"), "run\n", CREATE, APPEND);
        Thread.sleep(Long.parseLong(props.getProperty("sleepMs", "0")));

        Path serverJar = Path.of("versions", version, "paper-" + version + ".jar");
        Files.createDirectories(serverJar.getParent());
        Files.write(serverJar, new byte[0]);
        for (String lib : new String[]{"com/example/a/1.0/a-1.0.jar", "com/example/b/2.0/b-2.0.jar"}) {
            Path p = Path.of("libraries", lib);
            Files.createDirectories(p.getParent());
            Files.write(p, new byte[0]);
        }

        System.exit(Integer.parseInt(props.getProperty("exitCode", "0")));
    }

}
