dependencies {
    compileOnly(project(":paperhive-api"))      // runtime copy comes from the parent loader, so use compileOnly instead:
    // compileOnly(project(":paperhive-api"))
}