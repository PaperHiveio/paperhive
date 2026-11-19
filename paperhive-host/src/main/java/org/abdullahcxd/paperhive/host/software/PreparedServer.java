package org.abdullahcxd.paperhive.host.software;

import org.jetbrains.annotations.NotNull;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Output of a Paperclip patch: everything an instance classloader needs to boot the server.
 * <p>
 * While open, the cache entry is marked in use so other hosts sharing the cache won't prune it.
 * Keep it open for as long as any instance runs from it.
 */
public final class PreparedServer implements AutoCloseable {

    private final Path serverJar;
    private final List<Path> libraries;
    private final String mainClass;
    private final Runnable release;
    private final AtomicBoolean closed = new AtomicBoolean();

    public PreparedServer(Path serverJar, List<Path> libraries, String mainClass, Runnable release) {
        this.serverJar = serverJar;
        this.libraries = List.copyOf(libraries);
        this.mainClass = mainClass;
        this.release = release;
    }

    public @NotNull Path serverJar() {
        return serverJar;
    }

    public @NotNull List<Path> libraries() {
        return libraries;
    }

    public @NotNull String mainClass() {
        return mainClass;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) release.run();
    }

}
