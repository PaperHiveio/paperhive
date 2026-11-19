package org.abdullahcxd.paperhive.host.software;

import org.abdullahcxd.paperhive.downloader.MCSoftware;
import org.abdullahcxd.paperhive.host.software.paper.PaperSoftware;
import org.abdullahcxd.paperhive.host.software.velocity.VelocitySoftware;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The {@link ServerSoftware} for each {@code Software:} of {@code paperhive.yml}. Paper and Velocity are built in;
 * {@link #register} adds (or replaces) one.
 */
public final class ServerSoftwares {

    private static final Map<MCSoftware, ServerSoftware> SOFTWARES = new ConcurrentHashMap<>();

    // every property some software overrides, declared before the static block that fills it; HiveFs only looks up the caller for those
    private static final Set<String> OVERRIDDEN = ConcurrentHashMap.newKeySet();

    static {
        register(new PaperSoftware());
        register(new VelocitySoftware());
    }

    private ServerSoftwares() {}

    /** Makes servers with {@code software.id()} use {@code software}. */
    public static void register(@NotNull ServerSoftware software) {
        SOFTWARES.put(software.id(), software);
        OVERRIDDEN.addAll(software.systemProperties().keySet());
    }

    /** Whether any registered software gives {@code key} its own value. */
    public static boolean overridesProperty(@NotNull String key) {
        return OVERRIDDEN.contains(key);
    }

    /** @throws IllegalArgumentException if no software is registered for {@code id} */
    public static @NotNull ServerSoftware of(@NotNull MCSoftware id) {
        ServerSoftware software = SOFTWARES.get(id);
        if (software == null) throw new IllegalArgumentException("Paperhive can't run " + id.getName() + " servers");
        return software;
    }

    /** Paper: what a server runs unless its config says otherwise. */
    public static @NotNull ServerSoftware paper() {
        return of(MCSoftware.PAPERMC);
    }

}
