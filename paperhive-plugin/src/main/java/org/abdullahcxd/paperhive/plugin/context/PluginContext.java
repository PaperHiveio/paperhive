package org.abdullahcxd.paperhive.plugin.context;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.abdullahcxd.paperhive.plugin.command.HiveCommandRegistry;

/** What a Hive plugin gets in {@code onEnable}. */
@AllArgsConstructor
@Getter
public class PluginContext {

    /** Register {@link org.abdullahcxd.paperhive.plugin.command.HiveCommand}s for the host's {@code hive>} prompt here. */
    private final HiveCommandRegistry commands;

}
