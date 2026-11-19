---
title: "Gradle plugin"
description: "Set up a Hive plugin project in one line: dependencies, descriptor, scaffolding and a test hive."
---

# Gradle plugin

The Paperhive Gradle plugin (`org.abdullahcxd.paperhive.userdev`) sets up everything a Hive plugin project needs,
like paperweight-userdev does for Paper plugins. You don't add dependencies or repositories by hand.

## Set up a project

::: steps

1. **Point Gradle at the Paperhive repository** in `settings.gradle.kts`:

   ```kotlin
   pluginManagement {
       repositories {
           maven("https://maven.abdullahcxd.org/releases")
           gradlePluginPortal()
       }
   }

   rootProject.name = "my-plugin"
   ```

2. **Apply the plugin** in `build.gradle.kts`, with the
   [latest release](https://github.com/paperhiveio/paperhive/releases)'s version:

   ```kotlin
   plugins {
       id("org.abdullahcxd.paperhive.userdev") version "x.y.z"
   }

   group = "com.example"
   version = "1.0.0"
   ```

3. **Create a starter plugin:**

   ```bash
   ./gradlew createHivePlugin
   ```

   This writes a host half, a server half and the plugin's `paperhive.yml`, named after your project
   (`my-plugin` → `com.example.myplugin.MyPluginHivePlugin`). Existing files are never overwritten.

4. **Run it in a test hive:**

   ```bash
   ./gradlew runHive
   ```

   This builds your plugin and starts a Paperhive host in `run/` with your plugin loaded, attached to your terminal.
   Accept the EULA in `run/instances/main/eula.txt` (or add the system property, below) and run it again.

:::

## What the plugin does

When applied, it:

- applies `java` with a **Java 21** toolchain;
- adds the repositories: Maven Central, PaperMC, the Paperhive repository, and Maven local;
- adds **`compileOnly("org.abdullahcxd.paperhive:paperhive-sdk:<version>")`**, which brings the Hive API, the plugin
  API, the server-half API and the Paper API. All of it is provided at runtime, so nothing is shaded into your jar;
- registers the tasks below (group `paperhive`).

## Tasks

| Task                          | What it does                                                                                                                         |
|-------------------------------|--------------------------------------------------------------------------------------------------------------------------------------|
| `createHivePlugin`            | Writes a starter host half, server half and `paperhive.yml`. Options: `--name=MyPlugin`, `--package=com.example.my`.                 |
| `generatePaperhiveDescriptor` | Writes `paperhive.yml` into your jar from the `paperhive { }` block, when `main` is set. Runs automatically with `processResources`. |
| `runHive`                     | Builds your jar and runs a Paperhive host in `run/` with it loaded.                                                                  |

## Configuration

Every setting is optional:

```kotlin
paperhive {
    // Which Paperhive to compile against and run; default: this Gradle plugin's own version
    paperhiveVersion = "x.y.z"

    // Generate paperhive.yml instead of writing it yourself
    main = "com.example.myplugin.MyPluginHivePlugin"
    serverMain = "com.example.myplugin.MyPluginServerPlugin"   // only with a server half
    pluginName = "MyPlugin"                                     // default: the project name
    pluginVersion = "1.0.0"                                     // default: the project version
    pluginDescription = "Does great things across the hive"

    // Where runHive runs the test hive; default: run/
    runDirectory = layout.projectDirectory.dir("run")

    // Set false when settings.gradle.kts declares all repositories (RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    addRepositories = true
}
```

::: callout info "Generated or hand-written paperhive.yml"
Either set `main` (and the plugin writes `paperhive.yml`), **or** keep your own in `src/main/resources/`, which is
what `createHivePlugin` does. Not both: the build fails with a duplicate `paperhive.yml`.
:::

## The test hive

`runHive` runs a real Paperhive host in `run/`. The first run writes `run/paperhive.yml` with one server and downloads
Paper. Edit that file to add servers, just like a real hive.

Your plugin is loaded from the freshly built jar each run (nothing is copied into `run/plugins/`), so stop the hive,
change code, and `runHive` again.

To accept the EULA for the test hive from the build:

```kotlin
tasks.runHive {
    systemProperty("com.mojang.eula.agree", "true")
}
```

## Without the Gradle plugin

If you'd rather set things up yourself (or use Maven), depend on the SDK as `compileOnly`/`provided`:

```kotlin
repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://maven.abdullahcxd.org/releases")
}

dependencies {
    compileOnly("org.abdullahcxd.paperhive:paperhive-sdk:x.y.z")
}

java.toolchain.languageVersion = JavaLanguageVersion.of(21)
```

and write `src/main/resources/paperhive.yml` yourself (see the [descriptor reference](../reference/plugin-descriptor.md)).
