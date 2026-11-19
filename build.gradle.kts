// Module setup lives in build-logic (paperhive.java, paperhive.library, paperhive.publishing); this only wires the
// included Gradle plugin build into `build`, `publish` and `publishToMavenLocal` here.
plugins {
    alias(libs.plugins.shadow) apply false
}

allprojects {
    group = "org.abdullahcxd.paperhive" // version: gradle.properties
}

val gradlePlugin = gradle.includedBuild("paperhive-gradle")
tasks.register("build") { dependsOn(gradlePlugin.task(":build")) }
tasks.register("publish") { dependsOn(gradlePlugin.task(":publish")) }
tasks.register("publishToMavenLocal") { dependsOn(gradlePlugin.task(":publishToMavenLocal")) }
