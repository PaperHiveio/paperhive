// Publishing to https://maven.abdullahcxd.org/releases with one POM for every artifact. A module with a `java`
// component gets a `maven` publication of it with sources; others (the host's shadow jar, the Gradle plugin) add their
// own. Credentials: abdullahcxdUsername / abdullahcxdPassword (CI: ORG_GRADLE_PROJECT_abdullahcxd...).
plugins {
    `maven-publish`
}

pluginManager.withPlugin("java") {
    the<JavaPluginExtension>().withSourcesJar()
}

publishing {
    repositories {
        maven("https://maven.abdullahcxd.org/releases") {
            name = "abdullahcxd"
            credentials(PasswordCredentials::class)
        }
    }
    publications.withType<MavenPublication>().configureEach {
        pom {
            name = project.name
            description = "Paperhive: one JVM, many Paper servers"
            url = "https://github.com/paperhiveio/paperhive"
            licenses {
                license {
                    name = "MIT License"
                    url = "https://opensource.org/licenses/MIT"
                }
            }
            developers {
                developer {
                    id = "AbdullahCXD"
                    name = "Abdullah Saeed"
                }
            }
            scm {
                url = "https://github.com/paperhiveio/paperhive"
                connection = "scm:git:https://github.com/paperhiveio/paperhive.git"
                developerConnection = "scm:git:ssh://git@github.com/paperhiveio/paperhive.git"
            }
        }
    }
}
