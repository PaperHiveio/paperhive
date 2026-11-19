// Everything a Hive plugin compiles against, as one dependency: compileOnly(project(":paperhive-sdk"))
// (all of it is provided at runtime: by the Paperhive host, and by each server for the server half)
plugins {
    `java-library`
    id("paperhive.library")
}

dependencies {
    api(project(":paperhive-api"))
    api(project(":paperhive-plugin"))
    api(project(":paperhive-bridge"))
    api(libs.paper.api)
}
