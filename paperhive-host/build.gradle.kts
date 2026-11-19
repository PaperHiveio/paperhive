plugins {
    id("paperhive.java")
    id("paperhive.publishing")
    application
    alias(libs.plugins.shadow)
}

// the self-contained jar, for the Gradle plugin's runHive
publishing {
    publications.create<MavenPublication>("maven") { artifact(tasks.shadowJar) }
}

// the bridge plugin travels inside the host jar as a resource (not on the host's classpath); each server loads it
val bridge by configurations.creating { isTransitive = false }
// the Velocity plugin travels the same way; Paperhive puts it into proxies that run inside the hive
val velocityPlugin by configurations.creating { isTransitive = false }

dependencies {
    bridge(project(":paperhive-bridge"))
    velocityPlugin(project(":paperhive-velocity"))
    implementation(project(":paperhive-api"))
    implementation(project(":paperhive-constants"))
    implementation(project(":paperhive-downloader"))
    implementation(project(":paperhive-plugin")) // shared with instances through ApiGateLoader

    implementation(platform(libs.jackson.bom))
    implementation(libs.jackson.databind)
    implementation(libs.jackson.yaml)

    implementation(libs.asm)
    implementation(libs.asm.commons)

    implementation(libs.jline) // the hive> prompt

    runtimeOnly(platform(libs.log4j.bom))
    runtimeOnly(libs.log4j.core)
    runtimeOnly(libs.log4j.slf4j2.impl)
}

// needed to shut down thread pools a stopped instance leaves behind (see LeftoverThreads)
val addOpens = listOf("java.base/java.lang", "java.base/java.util.concurrent")

tasks.processResources {
    from(bridge) { rename { "paperhive-bridge.jar" } }
    from(velocityPlugin) { rename { "paperhive-velocity.jar" } }
}

application {
    mainClass.set("org.abdullahcxd.paperhive.host.PaperhiveMain")
}

tasks.shadowJar {
    archiveBaseName.set("paperhive")
    archiveClassifier.set("")
    mergeServiceFiles()
    // log4j-api ships as a multi-release jar; without this its Java 9+ StackLocator is skipped
    manifest {
        attributes("Multi-Release" to "true", "Add-Opens" to addOpens.joinToString(" "),
            // JLine talks to the terminal through the FFM API; without this Java 22+ warns at every start
            "Enable-Native-Access" to "ALL-UNNAMED",
            "Implementation-Title" to "Paperhive", "Implementation-Version" to project.version.toString())
    }
}

tasks.named<JavaExec>("run") {
    // the Paperhive home: .paperhive-cache/ and instances/ live here
    val runDir = layout.projectDirectory.dir("run").asFile
    doFirst { runDir.mkdirs() }
    workingDir = runDir
    standardInput = System.`in`
    jvmArgs(addOpens.map { "--add-opens=$it=ALL-UNNAMED" })
}
tasks.test {
    // HivePluginManagerTest loads the example Hive plugin's real jar
    val examplePlugin = project(":test-plugin").tasks.named<Jar>("jar")
    dependsOn(examplePlugin)
    systemProperty("paperhive.test.examplePlugin", examplePlugin.get().archiveFile.get().asFile.absolutePath)
    // files an instance writes relative to the cwd (eula.txt, logs/, ...) land here, not in the module dir
    val work = layout.buildDirectory.dir("test-work").get().asFile
    doFirst { work.mkdirs() }
    workingDir = work
    jvmArgs(addOpens.map { "--add-opens=$it=ALL-UNNAMED" })

    // opt-in Paper soak test (PaperInstanceIT): forward its -D flags to the test JVM
    // (io.netty.transport.noNative=false lets Paper use epoll/kqueue instead of the NIO Paperhive picks by default)
    System.getProperties().stringPropertyNames()
        .filter { it.startsWith("paperhive.it.") || it == "com.mojang.eula.agree" || it == "io.netty.transport.noNative" }
        .forEach { systemProperty(it, System.getProperty(it)) }
    if (System.getProperty("paperhive.it.paperJar") != null) {
        maxHeapSize = "3g"
        jvmArgs("-XX:SoftRefLRUPolicyMSPerMB=0") // see PaperInstanceIT
        testLogging { showStandardStreams = true }
    }
}
