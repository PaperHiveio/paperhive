package org.abdullahcxd.paperhive.api;

import org.jetbrains.annotations.NotNull;

/** Where the {@link Hive} is found, from the host and from inside every server. */
public final class Paperhive {

    private static volatile Hive hive;

    private Paperhive() {}

    /** @throws IllegalStateException when not running under a Paperhive host */
    public static @NotNull Hive getHive() {
        Hive h = hive;
        if (h == null) throw new IllegalStateException("Not running under a Paperhive host");
        return h;
    }

    /** Set once, by the Paperhive host at startup. */
    public static synchronized void setHive(@NotNull Hive hive) {
        if (Paperhive.hive != null) throw new IllegalStateException("The Hive is already set");
        Paperhive.hive = hive;
    }

}
