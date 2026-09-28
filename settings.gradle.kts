pluginManagement {
    repositories { gradlePluginPortal(); mavenCentral() }
}
dependencyResolutionManagement {
    repositories { mavenLocal(); mavenCentral() }
}
rootProject.name = "http-build"
// com.netonstream:http (neton.http, neton.http.header, neton.http.uri, neton.http.h1, neton.http.h2). SPEC §1.
include(":http")
