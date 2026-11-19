package org.abdullahcxd.paperhive.plugin;

import java.nio.file.Path;
import java.util.List;

public interface PluginManager {

    List<PaperhivePlugin> loadPlugins(Path directory);
    PaperhivePlugin loadPlugin(Path jarFile);

    void disablePlugin(String name);
    void disablePlugin(PaperhivePlugin plugin);

    void enablePlugin(String name);
    void enablePlugin(PaperhivePlugin plugin);

    PaperhivePlugin findPlugin(String name);

}
