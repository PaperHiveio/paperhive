plugins {
    alias(libs.plugins.shadow) apply false
}

allprojects {
    group = "org.abdullahcxd.paperhive"
    // version: gradle.properties
}

// paperhive-gradle is an included build: make `build`, `publish` and `publishToMavenLocal` here cover it too
val gradlePlugin = gradle.includedBuild("paperhive-gradle")
tasks.register("build") { dependsOn(gradlePlugin.task(":build")) }
tasks.register("publish") { dependsOn(gradlePlugin.task(":publish")) }
tasks.register("publishToMavenLocal") { dependsOn(gradlePlugin.task(":publishToMavenLocal")) }

subprojects {
    apply(plugin = "java")

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(21)
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }

    // what the Gradle plugin (paperhive-gradle) resolves in Hive plugin projects: ./gradlew publishToMavenLocal
    if (name in setOf("paperhive-api", "paperhive-plugin", "paperhive-bridge", "paperhive-sdk")) {
        apply(plugin = "maven-publish")
        extensions.configure<JavaPluginExtension> { withSourcesJar() } // sources in plugin authors' IDEs
        extensions.configure<PublishingExtension> {
            publications.create<MavenPublication>("maven") { from(components["java"]) }
        }
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

    val libs = rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs")

    dependencies {
        "implementation"(libs.findLibrary("slf4j-api").get())

        "annotationProcessor"("org.jetbrains:annotations:26.0.2")
        "compileOnly"("org.jetbrains:annotations:26.0.2")

        "annotationProcessor"("org.projectlombok:lombok:1.18.46")
        "compileOnly"("org.projectlombok:lombok:1.18.46")
    }
}