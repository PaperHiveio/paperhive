package org.abdullahcxd.paperhive.plugin;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * A Hive plugin's {@code paperhive.yml}: {@code name}, {@code version}, {@code main} (its {@link PaperhivePlugin}
 * class), optionally {@code description}, and optionally {@code server-main}: its server half, a
 * {@code HiveBukkitPlugin} that every server it's enabled for loads (no {@code plugin.yml} needed).
 */
@Getter
@AllArgsConstructor
public final class PaperhivePluginDescriptor {

    private final String name, description, version, main;
    /** Null for a host-only plugin. */
    private final String serverMain;

}
