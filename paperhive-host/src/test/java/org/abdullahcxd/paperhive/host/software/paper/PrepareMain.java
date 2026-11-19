package org.abdullahcxd.paperhive.host.software.paper;

import org.abdullahcxd.paperhive.host.software.PreparedServer;
import java.nio.file.Path;

/**
 * Test-only host stand-in: prepares {@code args[0]} into cache {@code args[1]} in its own JVM,
 * so {@link PaperclipCacheTest} can run two "hosts" against one cache without network access.
 */
public final class PrepareMain {

    public static void main(String[] args) {
        try (PreparedServer server = PaperclipCache.prepare(Path.of(args[0]), Path.of(args[1]))) {
            System.out.println("Main class: " + server.mainClass());
        }
    }

}
