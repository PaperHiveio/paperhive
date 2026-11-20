package org.abdullahcxd.paperhive.gradle;

import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;

/**
 * The {@code paperhive { }} block. Everything is optional:
 * <pre>
 * paperhive {
 *     paperhiveVersion = "0.1.1-SNAPSHOT"           // the SDK and host to use; default: this plugin's version
 *     main = "com.example.MyHivePlugin"             // set it to have paperhive.yml generated (else write your own)
 *     serverMain = "com.example.MyServerPlugin"     // the server half, if there is one
 *     pluginName = "MyPlugin"                       // default: the project's name
 *     pluginVersion = "1.0.0"                       // default: the project's version
 *     pluginDescription = "Does things"
 *     runDirectory = layout.projectDirectory.dir("run")
 *     addRepositories = true                        // false when settings.gradle declares them all
 * }
 * </pre>
 */
public abstract class PaperhiveExtension {

    public abstract Property<String> getPaperhiveVersion();

    public abstract Property<String> getMain();

    public abstract Property<String> getServerMain();

    public abstract Property<String> getPluginName();

    public abstract Property<String> getPluginVersion();

    public abstract Property<String> getPluginDescription();

    public abstract DirectoryProperty getRunDirectory();

    public abstract Property<Boolean> getAddRepositories();

}
