package org.abdullahcxd.paperhive.gradle;

import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

import java.io.File;

/**
 * Runs the Paperhive host in {@link #getRunDirectory()} with the plugin's jar loaded from where it was built
 * ({@code -Dpaperhive.devPlugins}), so it works whatever {@code Host.Plugins} the run directory's config names.
 */
@DisableCachingByDefault(because = "runs a server")
public abstract class RunHive extends JavaExec {

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getPluginJar();

    @Internal
    public abstract DirectoryProperty getRunDirectory();

    @Override
    @TaskAction
    public void exec() {
        File dir = getRunDirectory().get().getAsFile();
        dir.mkdirs();
        setWorkingDir(dir);
        systemProperty("paperhive.devPlugins", getPluginJar().get().getAsFile().getAbsolutePath());
        super.exec();
    }

}
