import java.util.Properties

// The Gradle plugin for Hive plugin projects (like paperweight-userdev): SDK dependency, paperhive.yml, runHive, scaffolding.
// Try it locally: ./gradlew publishToMavenLocal (from the root), then see "Gradle plugin" in docs/architecture.md.
plugins {
    `java-gradle-plugin`
    `maven-publish`
}

group = "org.abdullahcxd.paperhive"
// one version for all of Paperhive, from the root gradle.properties
version = Properties().apply { file("../gradle.properties").reader().use { load(it) } }.getProperty("version")

java.toolchain.languageVersion.set(JavaLanguageVersion.of(21))
tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }
tasks.test { useJUnitPlatform() }

gradlePlugin {
    plugins {
        create("userdev") {
            id = "org.abdullahcxd.paperhive.userdev"
            implementationClass = "org.abdullahcxd.paperhive.gradle.PaperhiveUserdevPlugin"
        }
    }
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:6.0.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.processResources {
    // the plugin's own version is the default Paperhive version it adds
    val version = project.version.toString()
    inputs.property("version", version)
    filesMatching("paperhive-gradle.properties") { expand("version" to version) }
}
