package org.abdullahcxd.paperhive.host.instance;

import org.abdullahcxd.paperhive.host.software.ServerSoftwares;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;

/**
 * Per-instance working directory. The JVM has one cwd and it can't be changed, so {@link InstanceTransformer}
 * rewrites instance code to make relative paths absolute against the calling instance's root at the moment
 * they're created ({@code new File("world")}, {@code Path.of("config")}, ...). Everything downstream then
 * works on an absolute path, whatever the JVM's cwd is.
 * <p>
 * Only relative paths look up the caller (a stack walk); absolute ones return straight away.
 * Public because rewritten instance code calls it; shared with instances through {@link ApiGateLoader}.
 * <p>
 * A relative {@code Path} the JDK derives later ({@code relativize}, {@code getFileName}) is resolved when it's opened
 * through {@code Files.*} ({@link FilesMirror}). Opening one through its {@code FileSystemProvider} directly, or
 * {@code toFile()} then a JDK API that takes a {@code File}, still resolves against the JVM cwd.
 */
public final class HiveFs {

    private HiveFs() {}

    /** {@code new File(path)}, {@code new FileInputStream(path)}, ...: the path argument. */
    public static @Nullable String resolve(@Nullable String path) {
        if (path == null || new File(path).isAbsolute()) return path;
        Path root = root();
        return root == null ? path : new File(root.toFile(), path).getPath();
    }

    /** {@code new File(parent, child)} becomes {@code new File(join(parent, child))}. */
    public static @Nullable String join(@NotNull String parent, @NotNull String child) {
        return resolve(new File(parent, child).getPath());
    }

    /** {@code new File(parent, child)} becomes {@code new File(join(parent, child))}. */
    public static @Nullable String join(@NotNull File parent, @NotNull String child) {
        return resolve(new File(parent, child).getPath());
    }

    /** Replaces {@code Path.of(first, more...)} and {@code Paths.get(first, more...)}. */
    public static @NotNull Path path(@NotNull String first, String @NotNull ... more) {
        return absolute(FileSystems.getDefault().getPath(first, more));
    }

    /** Replaces {@code FileSystem.getPath(first, more...)}; only the default file system is the JVM's disk. */
    public static @NotNull Path getPath(@NotNull FileSystem fs, @NotNull String first, String @NotNull ... more) {
        Path path = fs.getPath(first, more);
        return fs == FileSystems.getDefault() ? absolute(path) : path;
    }

    /** {@code Files.*} calls ({@link FilesMirror}): a relative path argument, made absolute; null stays null. */
    public static @Nullable Path file(@Nullable Path path) {
        return path == null ? null : absolute(path);
    }

    public static @NotNull Path toAbsolutePath(@NotNull Path path) {
        return absolute(path).toAbsolutePath();
    }

    public static @NotNull String getAbsolutePath(@NotNull File file) {
        return absolute(file).getAbsolutePath();
    }

    public static @NotNull File getAbsoluteFile(@NotNull File file) {
        return absolute(file).getAbsoluteFile();
    }

    public static @NotNull String getCanonicalPath(@NotNull File file) throws IOException {
        return absolute(file).getCanonicalPath();
    }

    public static @NotNull File getCanonicalFile(@NotNull File file) throws IOException {
        return absolute(file).getCanonicalFile();
    }

    /**
     * Replaces {@code System.getProperty(key)}: {@code user.dir} is the instance root, and the instance's software may
     * set its own value for a property ({@code ServerSoftware.systemProperties()}). Other keys don't look up the caller.
     */
    public static @Nullable String getProperty(@NotNull String key) {
        if ("user.dir".equals(key) || ServerSoftwares.overridesProperty(key)) {
            ServerInstance instance = InstanceHooks.callerOrNull();
            if (instance != null) {
                if ("user.dir".equals(key)) return instance.getRoot().toString();
                String own = instance.getSoftware().systemProperties().get(key);
                if (own != null) return own;
            }
        }
        return System.getProperty(key);
    }

    /** Replaces {@code System.getProperty(key, def)}. */
    public static @Nullable String getProperty(@NotNull String key, @Nullable String def) {
        String value = getProperty(key);
        return value != null ? value : def;
    }

    /** Replaces {@code ProcessBuilder.start()}: a child process without a directory starts in the instance root. */
    public static @NotNull Process start(@NotNull ProcessBuilder builder) throws IOException {
        if (builder.directory() == null) {
            Path root = root();
            if (root != null) builder.directory(root.toFile());
        }
        return builder.start();
    }

    @SuppressWarnings("deprecation")
    public static @NotNull Process exec(@NotNull Runtime runtime, @NotNull String command) throws IOException {
        return runtime.exec(command, null, rootFile());
    }

    @SuppressWarnings("deprecation")
    public static @NotNull Process exec(@NotNull Runtime runtime, @NotNull String command, String @NotNull [] env) throws IOException {
        return runtime.exec(command, env, rootFile());
    }

    public static @NotNull Process exec(@NotNull Runtime runtime, String @NotNull [] command) throws IOException {
        return runtime.exec(command, null, rootFile());
    }

    public static @NotNull Process exec(@NotNull Runtime runtime, String @NotNull [] command, String @NotNull [] env) throws IOException {
        return runtime.exec(command, env, rootFile());
    }

    private static Path absolute(Path path) {
        if (path.isAbsolute() || path.getFileSystem() != FileSystems.getDefault()) return path;
        Path root = root();
        return root == null ? path : root.resolve(path);
    }

    private static @NotNull File absolute(@NotNull File file) {
        if (file.isAbsolute()) return file;
        Path root = root();
        return root == null ? file : new File(root.toFile(), file.getPath());
    }

    private static @Nullable File rootFile() {
        Path root = root();
        return root == null ? null : root.toFile();
    }

    /** The calling instance's root, or null (keep JDK behaviour) if no instance owns the caller. */
    private static @Nullable Path root() {
        ServerInstance instance = InstanceHooks.callerOrNull();
        return instance == null ? null : instance.getRoot();
    }

}
