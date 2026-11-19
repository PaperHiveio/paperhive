plugins {
    id("java")
}

dependencies {
    implementation(project(":paperhive-constants"))

    implementation(platform("tools.jackson:jackson-bom:3.3.0"))
    implementation("tools.jackson.core:jackson-databind")
    implementation("tools.jackson.dataformat:jackson-dataformat-yaml")

    testImplementation(platform("org.junit:junit-bom:6.0.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}