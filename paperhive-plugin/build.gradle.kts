// The Hive plugin API (host halves). The API comes from the host at runtime.
plugins {
    id("paperhive.library")
}

dependencies {
    compileOnly(project(":paperhive-api"))
}
