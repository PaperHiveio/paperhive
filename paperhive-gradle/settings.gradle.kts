// Its own build (included by the root settings) so the Paperhive build itself can apply the plugin (test-plugin)
rootProject.name = "paperhive-gradle"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
