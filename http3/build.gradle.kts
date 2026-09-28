plugins { kotlin("multiplatform") }

// com.netonstream:http3 (SPEC §1, §5): the targets com.netonstream:http and com.netonstream:quic both provide (quic,
// through openssl, has no 32-bit Android). The protocol core (frames, QPACK, stream state machines) depends on
// com.netonstream:http only; the connection to com.netonstream:quic is added with the QUIC adapter.
kotlin {
    linuxX64(); linuxArm64()
    macosArm64(); macosX64()
    mingwX64()
    iosArm64(); iosSimulatorArm64(); iosX64()
    androidNativeArm64(); androidNativeX64()

    sourceSets {
        commonMain.dependencies { api(project(":http")) }
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}
