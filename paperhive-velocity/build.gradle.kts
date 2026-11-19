// A Velocity plugin: registers the Paperhive host's running servers with the proxy (put the jar in Velocity's plugins/)
dependencies {
    compileOnly(libs.velocity.api)
    annotationProcessor(libs.velocity.api) // writes velocity-plugin.json from @Plugin

    testImplementation(platform("org.junit:junit-bom:6.0.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
