// A Hive plugin with a host half and a server half; drop the jar into the Paperhive host's plugins/ folder.
// Set up by the Gradle plugin, as any Hive plugin project would be (inside this build it uses :paperhive-sdk directly).
plugins {
    id("paperhive.java")
    id("org.abdullahcxd.paperhive.userdev")
}

paperhive {
    addRepositories = false // settings.gradle.kts declares them
}
