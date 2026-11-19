package org.abdullahcxd.paperhive.plugin.command;

import org.jetbrains.annotations.NotNull;

/** Who ran a {@link HiveCommand}: the host's console, or a {@code hive} client through the control socket. */
public interface HiveCommandSender {

    void sendMessage(@NotNull String message);

    /** True at the host's own {@code hive>} prompt; false for a client through the control socket. */
    boolean isConsole();

}
