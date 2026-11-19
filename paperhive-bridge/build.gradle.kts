// A Paper plugin, loaded by each server (Paperhive passes it with --add-plugin); see "Bukkit bridge" in docs/architecture.md
dependencies {
    compileOnly(libs.paper.api)
    // shared from the host through ApiGateLoader at runtime
    compileOnly(project(":paperhive-api"))
    compileOnly(project(":paperhive-plugin"))
}

tasks.processResources {
    filesMatching("plugin.yml") { expand("version" to project.version) }
}
