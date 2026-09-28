pluginManagement {
    repositories { gradlePluginPortal(); mavenCentral() }
}
dependencyResolutionManagement {
    repositories { mavenLocal(); mavenCentral() }
}
rootProject.name = "http-build"
// com.netonstream:http (neton.http, neton.http.header, neton.http.uri, neton.http.h1, neton.http.h2). SPEC §1.
include(":http")
// com.netonstream:http3 (neton.http.h3): HTTP/3 over com.netonstream:quic. SPEC §1, §5.
include(":http3")

// Benchmark executables (SPEC §8).
include(":http-bench")
