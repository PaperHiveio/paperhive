package org.abdullahcxd.paperhive.downloader;

import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;

/** Server software the Paperhive mirror has jars for; {@link #getName()} is how {@code paperhive.yml} names it. */
@Getter
public enum MCSoftware {

    PAPERMC("papermc"),
    VELOCITYMC("velocitymc");

    private final String name;

    MCSoftware(@NotNull String name) {
        this.name = name;
    }

    /** Its versions the mirror has, newest first. */
    public @NotNull List<MCVersion> versions() {
        return Arrays.stream(MCVersion.values()).filter(v -> v.getSoftware() == this).toList();
    }

    /** The version a server of this software runs when its config names none: the newest. */
    public @NotNull MCVersion defaultVersion() {
        return versions().getFirst();
    }

    /** By its config name, or null. */
    public static @Nullable MCSoftware byName(@NotNull String name) {
        return Arrays.stream(values()).filter(s -> s.name.equals(name)).findFirst().orElse(null);
    }

}
