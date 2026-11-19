// Every Paperhive Java module: Java 21, UTF-8, JUnit 5, and the libraries every module uses
plugins {
    java
}

val libs = the<VersionCatalogsExtension>().named("libs")
fun lib(alias: String) = libs.findLibrary(alias).get()

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 21
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

dependencies {
    "implementation"(lib("slf4j-api"))
    "compileOnly"(lib("jetbrains-annotations"))
    "annotationProcessor"(lib("jetbrains-annotations"))
    "compileOnly"(lib("lombok"))
    "annotationProcessor"(lib("lombok"))

    "testImplementation"(platform(lib("junit-bom")))
    "testImplementation"(lib("junit-jupiter"))
    "testRuntimeOnly"(lib("junit-platform-launcher"))
}
