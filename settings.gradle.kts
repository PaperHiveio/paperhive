rootProject.name = "PaperHive"

pluginManagement {
    // the Gradle plugin for Hive plugin projects, used by test-plugin; its own build so it can be applied here
    includeBuild("paperhive-gradle")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/") // paper-api, for paperhive-bridge
        // Other repos
    }
}

//
// Paperhive related modules in the root projects
// are automatically added by this list here which
// appends paperhive- prefix to the module name
// and adds it using Gradle include
//
// Any non-paperhive modules can be added under using
// include(...name) which adds similarly to this.
//
for (projectName in listOf(
    "api",
    "host",
    "plugin",
    "downloader",
    "constants",
    "bridge",
    "sdk",
    "velocity"
)) {
    include("paperhive-$projectName")
}

// Other modules:
include("test-plugin")