// A published Paperhive library (the API, plugin API, bridge, SDK): its jar and sources as one Maven publication
plugins {
    id("paperhive.java")
    id("paperhive.publishing")
}

publishing {
    publications.create<MavenPublication>("maven") { from(components["java"]) }
}
