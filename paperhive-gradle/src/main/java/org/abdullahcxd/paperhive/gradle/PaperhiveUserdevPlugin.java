package org.abdullahcxd.paperhive.gradle;

import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.jvm.toolchain.JavaLanguageVersion;
import org.gradle.jvm.toolchain.JavaToolchainService;
import org.gradle.language.jvm.tasks.ProcessResources;
import org.jspecify.annotations.NonNull;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Properties;

/**
 * {@code id("org.abdullahcxd.paperhive.userdev")}: makes a project a Hive plugin with nothing else to set up.
 * <ul>
 *   <li>Java 21, and the repositories the SDK comes from (Maven Central, PaperMC, Paperhive's, Maven local)</li>
 *   <li>{@code compileOnly} on {@code paperhive-sdk} (the Hive API, Hive plugin API, server-half API and Paper API)</li>
 *   <li>{@code paperhive.yml} generated from {@code paperhive { main = ... }}</li>
 *   <li>{@code createHivePlugin}: writes a starter host half, server half and {@code paperhive.yml}</li>
 *   <li>{@code runHive}: runs a Paperhive host in {@code run/} with this plugin loaded</li>
 * </ul>
 */
public final class PaperhiveUserdevPlugin implements Plugin<Project> {

    public static final String GROUP = "org.abdullahcxd.paperhive";
    public static final String REPOSITORY = "https://maven.abdullahcxd.org/releases";
    public static final String HOST_CONFIGURATION = "paperhiveHost";
    public static final String TASK_GROUP = "paperhive";

    @Override
    public void apply(@NonNull Project project) {
        project.getPluginManager().apply(JavaPlugin.class);
        PaperhiveExtension ext = project.getExtensions().create("paperhive", PaperhiveExtension.class);
        ext.getPaperhiveVersion().convention(ownVersion());
        ext.getPluginName().convention(project.getName());
        ext.getPluginVersion().convention(project.provider(() -> project.getVersion().toString()));
        ext.getRunDirectory().convention(project.getLayout().getProjectDirectory().dir("run"));
        ext.getAddRepositories().convention(true);

        JavaPluginExtension java = project.getExtensions().getByType(JavaPluginExtension.class);
        java.getToolchain().getLanguageVersion().convention(JavaLanguageVersion.of(21));

        // after the build script, so `addRepositories = false` is seen (settings with FAIL_ON_PROJECT_REPOS reject project repos)
        project.afterEvaluate(p -> {
            if (!ext.getAddRepositories().get()) return;
            p.getRepositories().mavenCentral();
            p.getRepositories().maven(r -> r.setUrl("https://repo.papermc.io/repository/maven-public/"));
            p.getRepositories().maven(r -> r.setUrl(REPOSITORY)); // the SDK and host releases
            p.getRepositories().mavenLocal(); // where `publishToMavenLocal` of Paperhive puts the SDK
        });

        Configuration host = project.getConfigurations().create(HOST_CONFIGURATION, c -> {
            c.setDescription("The Paperhive host runHive runs (a self-contained jar)");
            c.setTransitive(false);
            c.setCanBeConsumed(false);
        });
        // everything in the SDK is provided at runtime by the host and the servers, so nothing is shaded
        if (project.getRootProject().findProject(":paperhive-sdk") != null) {
            // inside the Paperhive build itself (test-plugin): the modules, not published artifacts
            project.getDependencies().add(JavaPlugin.COMPILE_ONLY_CONFIGURATION_NAME, project.getDependencies().project(Map.of("path", ":paperhive-sdk")));
            project.getDependencies().add(HOST_CONFIGURATION, project.getDependencies().project(Map.of("path", ":paperhive-host", "configuration", "shadowRuntimeElements")));
        } else {
            project.getDependencies().addProvider(JavaPlugin.COMPILE_ONLY_CONFIGURATION_NAME,
                    ext.getPaperhiveVersion().map(v -> GROUP + ":paperhive-sdk:" + v));
            host.getDependencies().addLater(ext.getPaperhiveVersion().map(v -> project.getDependencies().create(GROUP + ":paperhive-host:" + v)));
        }

        SourceSet main = project.getExtensions().getByType(SourceSetContainer.class).getByName(SourceSet.MAIN_SOURCE_SET_NAME);

        var descriptor = project.getTasks().register("generatePaperhiveDescriptor", GeneratePaperhiveDescriptor.class, t -> {
            t.setGroup(TASK_GROUP);
            t.setDescription("Writes paperhive.yml from the paperhive { } block (only when main is set)");
            t.getPluginName().set(ext.getPluginName());
            t.getPluginVersion().set(ext.getPluginVersion());
            t.getPluginDescription().set(ext.getPluginDescription());
            t.getMain().set(ext.getMain());
            t.getServerMain().set(ext.getServerMain());
            t.getOutputDirectory().set(project.getLayout().getBuildDirectory().dir("generated/paperhive"));
        });
        project.getTasks().named(main.getProcessResourcesTaskName(), ProcessResources.class, t -> t.from(descriptor));

        project.getTasks().register("createHivePlugin", CreateHivePlugin.class, t -> {
            t.setGroup(TASK_GROUP);
            t.setDescription("Writes a starter Hive plugin (host half, server half, paperhive.yml); never overwrites");
            t.getPluginName().convention(ext.getPluginName());
            Object group = project.getGroup();
            t.getPackageName().convention(ext.getPluginName().map(name ->
                    (group.toString().isBlank() ? "com.example" : group.toString()) + "." + CreateHivePlugin.packageSegment(name)));
            t.getJavaDirectory().set(project.getLayout().getProjectDirectory().dir("src/main/java"));
            t.getResourcesDirectory().set(project.getLayout().getProjectDirectory().dir("src/main/resources"));
        });

        var jar = project.getTasks().named(JavaPlugin.JAR_TASK_NAME, Jar.class);
        project.getTasks().register("runHive", RunHive.class, t -> {
            t.setGroup(TASK_GROUP);
            t.setDescription("Runs a Paperhive host in the run directory with this plugin loaded");
            t.classpath(host);
            t.getMainClass().set("org.abdullahcxd.paperhive.host.PaperhiveMain");
            t.getPluginJar().set(jar.flatMap(Jar::getArchiveFile));
            t.getRunDirectory().set(ext.getRunDirectory());
            t.setStandardInput(System.in);
            // what the host jar's manifest asks for (Add-Opens), as -jar isn't used here
            t.jvmArgs("--add-opens=java.base/java.lang=ALL-UNNAMED", "--add-opens=java.base/java.util.concurrent=ALL-UNNAMED");
            JavaToolchainService toolchains = project.getExtensions().getByType(JavaToolchainService.class);
            t.getJavaLauncher().set(toolchains.launcherFor(java.getToolchain()));
        });
    }

    static String ownVersion() {
        try (InputStream in = PaperhiveUserdevPlugin.class.getResourceAsStream("/paperhive-gradle.properties")) {
            if (in == null) throw new GradleException("paperhive-gradle.properties is missing from the Paperhive Gradle plugin");
            Properties properties = new Properties();
            properties.load(in);
            return properties.getProperty("version");
        } catch (IOException e) {
            throw new GradleException("Cannot read the Paperhive Gradle plugin's version", e);
        }
    }

}
