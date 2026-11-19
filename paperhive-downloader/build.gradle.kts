// Downloads and verifies server jars from the Paperhive mirror
plugins {
    id("paperhive.java")
}

dependencies {
    implementation(project(":paperhive-constants"))
    implementation(platform(libs.jackson.bom))
    implementation(libs.jackson.databind)
    implementation(libs.jackson.yaml)
}
