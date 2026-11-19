package org.abdullahcxd.paperhive.api;

import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A {@code namespace:value} key, like Paper's {@code NamespacedKey}: names channels, services and shared data so two
 * plugins never collide. The namespace is a plugin's name ({@code PaperhivePlugin.key("...")},
 * {@code HiveBukkitPlugin.key("...")}), {@link #PAPERHIVE} for Paperhive's own, or anything else you choose.
 * <pre>{@code
 * Namespaced joins = key("joins");                     // in a plugin: "myplugin:joins"
 * Namespaced parsed = Namespaced.parse("lobby:queue"); // "lobby:queue"
 * Namespaced ours = Namespaced.paperhive("servers");   // "paperhive:servers"
 * }</pre>
 * The namespace may use {@code a-z 0-9 . _ -}; the value also {@code /}. Both are lowercase and not empty, so a key
 * reads the same everywhere it's written.
 *
 * @param namespace who the key belongs to
 * @param value     what it names, within that namespace
 */
public record Namespaced(@NotNull String namespace, @NotNull String value) implements Comparable<Namespaced> {

    /** Paperhive's own namespace. */
    public static final String PAPERHIVE = "paperhive";

    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9._-]+");
    private static final Pattern VALUE = Pattern.compile("[a-z0-9._/-]+");

    /** @throws IllegalArgumentException if either part is empty or uses other characters */
    public Namespaced {
        if (!NAMESPACE.matcher(namespace).matches()) {
            throw new IllegalArgumentException("Namespace '" + namespace + "' may only use a-z 0-9 . _ - (and not be empty)");
        }
        if (!VALUE.matcher(value).matches()) {
            throw new IllegalArgumentException("Value '" + value + "' may only use a-z 0-9 . _ - / (and not be empty)");
        }
    }

    /** {@code namespace:value}; both parts must already be valid. */
    public static @NotNull Namespaced of(@NotNull String namespace, @NotNull String value) {
        return new Namespaced(namespace, value);
    }

    /** {@code paperhive:value}. */
    public static @NotNull Namespaced paperhive(@NotNull String value) {
        return new Namespaced(PAPERHIVE, value);
    }

    /**
     * A key in a plugin's namespace: the plugin's name made valid ({@code My Plugin} becomes {@code my_plugin}), then
     * {@code value}.
     */
    public static @NotNull Namespaced forPlugin(@NotNull String pluginName, @NotNull String value) {
        return new Namespaced(namespaceOf(pluginName), value);
    }

    /**
     * Reads {@code namespace:value}; without a {@code :} the namespace is {@link #PAPERHIVE}.
     *
     * @throws IllegalArgumentException if it isn't a valid key
     */
    public static @NotNull Namespaced parse(@NotNull String key) {
        int colon = key.indexOf(':');
        return colon < 0 ? paperhive(key) : new Namespaced(key.substring(0, colon), key.substring(colon + 1));
    }

    /** A valid namespace from any name: lowercased, other characters replaced by {@code _}. */
    public static @NotNull String namespaceOf(@NotNull String name) {
        String ns = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
        return ns.isEmpty() ? "_" : ns;
    }

    /** A key in the same namespace. */
    public @NotNull Namespaced with(@NotNull String value) {
        return new Namespaced(namespace, value);
    }

    /** {@code namespace:value}. */
    public @NotNull String asString() {
        return namespace + ":" + value;
    }

    @Override
    public @NotNull String toString() {
        return asString();
    }

    @Override
    public int compareTo(@NotNull Namespaced other) {
        return asString().compareTo(other.asString());
    }

}
