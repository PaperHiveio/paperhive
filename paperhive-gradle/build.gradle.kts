import java.util.Properties

// The Gradle plugin for Hive plugin projects (like paperweight-userdev): SDK dependency, paperhive.yml, runHive, scaffolding.
// Try it locally: ./gradlew publishToMavenLocal (from the root), then see "Gradle plugin" in docs/architecture.md.
plugins {
    `java-gradle-plugin`
    `maven-publish`
}

group = "org.abdullahcxd.paperhive"
// one version for all of Paperhive: -Pversion (a release) or the root gradle.properties
version = version.toString().takeIf { it != "unspecified" }
    ?: Properties().apply { file("../gradle.properties").reader().use { load(it) } }.getProperty("version")

java.withSourcesJar()

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
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.processResources {
    // the plugin's own version is the default Paperhive version it adds
    val version = project.version.toString()
    inputs.property("version", version)
    filesMatching("paperhive-gradle.properties") { expand("version" to version) }
}

// Every Paperhive artifact goes to the releases repository with the same POM. Credentials: the Gradle properties
// abdullahcxdUsername / abdullahcxdPassword (in CI: ORG_GRADLE_PROJECT_abdullahcxdUsername / ...Password).
plugins.withId("maven-publish") {
    extensions.configure<PublishingExtension> {
        repositories {
            maven("https://maven.abdullahcxd.org/releases") {
                name = "abdullahcxd"
                credentials(PasswordCredentials::class)
            }
        }
        publications.withType<MavenPublication>().configureEach {
            pom {
                name.set(project.name)
                description.set("Paperhive: one JVM, many Paper servers")
                url.set("https://github.com/paperhiveio/paperhive")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                developers {
                    developer {
                        id.set("AbdullahCXD")
                        name.set("Abdullah Saeed")
                    }
                }
                scm {
                    url.set("https://github.com/paperhiveio/paperhive")
                    connection.set("scm:git:https://github.com/paperhiveio/paperhive.git")
                    developerConnection.set("scm:git:ssh://git@github.com/paperhiveio/paperhive.git")
                }
            }
        }
    }
}
