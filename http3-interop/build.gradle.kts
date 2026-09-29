plugins { kotlin("multiplatform") }

// HTTP/3 interop peer (SPEC §6, §11 "HTTP/3 阶段 D"): a server and a client of neton.http.h3 on neton.quic with real
// TLS 1.3, certificates and trust anchors loaded from files, run against external HTTP/3 implementations (h3 +
// h3-quinn, aioquic, h3spec). Not published and not part of the com.netonstream:http3 artifact.
kotlin {
    listOf(linuxX64(), macosArm64()).forEach { target ->
        target.binaries {
            executable("h3interop") { entryPoint = "neton.http.h3.interop.main" }
        }
    }
    sourceSets {
        nativeMain.dependencies { implementation(project(":http3")) }
    }
}
