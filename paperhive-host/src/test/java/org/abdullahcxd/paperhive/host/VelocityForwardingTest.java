package org.abdullahcxd.paperhive.host;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class VelocityForwardingTest {

    @TempDir
    Path tmp;

    private Path secret(String value) throws IOException {
        Path file = tmp.resolve("forwarding.secret");
        Files.writeString(file, value);
        return file;
    }

    @Test
    void setsUpAFreshServer() throws Exception {
        Path root = tmp.resolve("lobby");
        VelocityForwarding.apply(root, secret("s3cret\n"));

        String global = Files.readString(root.resolve("config/paper-global.yml"));
        assertTrue(global.contains("proxies:") && global.contains("velocity:") && global.contains("enabled: true")
                && global.contains("online-mode: true") && global.contains("secret: s3cret"), global);
        assertEquals("online-mode=false\n", Files.readString(root.resolve("server.properties")));
    }

    @Test
    void keepsEverythingElseAndUpdatesTheSecret() throws Exception {
        Path root = tmp.resolve("survival");
        Files.createDirectories(root.resolve("config"));
        Files.writeString(root.resolve("config/paper-global.yml"), """
                _version: 31
                proxies:
                  bungee-cord:
                    online-mode: true
                  velocity:
                    enabled: false
                    online-mode: false
                    secret: old
                misc:
                  max-joins-per-tick: 5
                """);
        Files.writeString(root.resolve("server.properties"), "#Minecraft server properties\nmotd=Hi\nonline-mode=true\nserver-port=25566\n");

        VelocityForwarding.apply(root, secret("new"));

        String global = Files.readString(root.resolve("config/paper-global.yml"));
        for (String kept : new String[]{"_version: 31", "bungee-cord:", "max-joins-per-tick: 5", "enabled: true", "secret: new"}) {
            assertTrue(global.contains(kept), kept + " missing from:\n" + global);
        }
        assertFalse(global.contains("secret: old"), global);
        assertEquals("#Minecraft server properties\nmotd=Hi\nonline-mode=false\nserver-port=25566\n",
                Files.readString(root.resolve("server.properties")));
    }

    @Test
    void writesNothingWhenAlreadySetUp() throws Exception {
        Path root = tmp.resolve("game");
        Path secret = secret("abc");
        VelocityForwarding.apply(root, secret);
        Path global = root.resolve("config/paper-global.yml");
        long before = Files.getLastModifiedTime(global).toMillis();
        Files.setLastModifiedTime(global, java.nio.file.attribute.FileTime.fromMillis(before - 60_000));
        VelocityForwarding.apply(root, secret);
        assertEquals(before - 60_000, Files.getLastModifiedTime(global).toMillis(), "an up-to-date file is left alone");
    }

    @Test
    void refusesAMissingOrEmptySecret() throws Exception {
        Path root = tmp.resolve("x");
        assertThrows(IOException.class, () -> VelocityForwarding.apply(root, tmp.resolve("missing.secret")));
        assertThrows(IOException.class, () -> VelocityForwarding.apply(root, secret("  \n")));
        assertFalse(Files.exists(root.resolve("server.properties")), "nothing is written without a secret");
    }

}
