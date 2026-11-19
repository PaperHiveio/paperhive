package org.abdullahcxd.paperhive.host.instance;

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
    public static String resolve(String path) {
        if (path == null || new File(path).isAbsolute()) return path;
        Path root = root();
        return root == null ? path : new File(root.toFile(), path).getPath();
    }

    /** {@code new File(parent, child)} becomes {@code new File(join(parent, child))}. */
    public static String join(String parent, String child) {
        return resolve(new File(parent, child).getPath());
    }

    /** {@code new File(parent, child)} becomes {@code new File(join(parent, child))}. */
    public static String join(File parent, String child) {
        return resolve(new File(parent, child).getPath());
    }

    /** Replaces {@code Path.of(first, more...)} and {@code Paths.get(first, more...)}. */
    public static Path path(String first, String... more) {
        return absolute(FileSystems.getDefault().getPath(first, more));
    }

    /** Replaces {@code FileSystem.getPath(first, more...)}; only the default file system is the JVM's disk. */
    public static Path getPath(FileSystem fs, String first, String... more) {
        Path path = fs.getPath(first, more);
        return fs == FileSystems.getDefault() ? absolute(path) : path;
    }

    /** {@code Files.*} calls ({@link FilesMirror}): a relative path argument, made absolute; null stays null. */
    public static Path file(Path path) {
        return path == null ? null : absolute(path);
    }

    public static Path toAbsolutePath(Path path) {
        return absolute(path).toAbsolutePath();
    }

    public static String getAbsolutePath(File file) {
        return absolute(file).getAbsolutePath();
    }

    public static File getAbsoluteFile(File file) {
        return absolute(file).getAbsoluteFile();
    }

    public static String getCanonicalPath(File file) throws IOException {
        return absolute(file).getCanonicalPath();
    }

    public static File getCanonicalFile(File file) throws IOException {
        return absolute(file).getCanonicalFile();
    }

    /** Replaces {@code System.getProperty(key)}: {@code user.dir} is the instance root. */
    public static String getProperty(String key) {
        if ("user.dir".equals(key)) {
            Path root = root();
            if (root != null) return root.toString();
        }
        return System.getProperty(key);
    }

    /** Replaces {@code System.getProperty(key, def)}. */
    public static String getProperty(String key, String def) {
        String value = getProperty(key);
        return value != null ? value : def;
    }

    /** Replaces {@code ProcessBuilder.start()}: a child process without a directory starts in the instance root. */
    public static Process start(ProcessBuilder builder) throws IOException {
        if (builder.directory() == null) {
            Path root = root();
            if (root != null) builder.directory(root.toFile());
        }
        return builder.start();
    }

    @SuppressWarnings("deprecation")
    public static Process exec(Runtime runtime, String command) throws IOException {
        return runtime.exec(command, null, rootFile());
    }

    @SuppressWarnings("deprecation")
    public static Process exec(Runtime runtime, String command, String[] env) throws IOException {
        return runtime.exec(command, env, rootFile());
    }

    public static Process exec(Runtime runtime, String[] command) throws IOException {
        return runtime.exec(command, null, rootFile());
    }

    public static Process exec(Runtime runtime, String[] command, String[] env) throws IOException {
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
