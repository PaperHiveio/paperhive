package org.abdullahcxd.paperhive.downloader;

import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** A version of a server software on the Paperhive mirror. Within a software, newest first. */
@Getter
public enum MCVersion {

    MC1_21_11(MCSoftware.PAPERMC, "1.21.11"),
    VELOCITY_4_3_1_45(MCSoftware.VELOCITYMC, "4.3.1-45");

    private final MCSoftware software;
    private final String version;

    MCVersion(@NotNull MCSoftware software, @NotNull String version) {
        this.software = software;
        this.version = version;
    }

    /** {@code software}'s version named {@code version}, or null if the mirror doesn't have it. */
    public static @Nullable MCVersion of(@NotNull MCSoftware software, @NotNull String version) {
        for (MCVersion v : values()) if (v.software == software && v.version.equals(version)) return v;
        return null;
    }

}
