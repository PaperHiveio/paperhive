package org.abdullahcxd.paperhive.plugin.command;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;

/** The host's {@link HiveCommand}s. Names and aliases are case-insensitive and unique. */
public interface HiveCommandRegistry {

    /** @throws IllegalArgumentException if its name or an alias is already taken */
    void register(@NotNull HiveCommand command);

    void unregister(@NotNull HiveCommand command);

    @Nullable HiveCommand find(@NotNull String nameOrAlias);

    @NotNull Collection<HiveCommand> commands();

}
