plugins { kotlin("multiplatform") }

// Benchmark servers (SPEC §8): not published.
kotlin {
    listOf(linuxX64(), linuxArm64(), macosArm64()).forEach { target ->
        target.binaries {
            executable("helloServer") { entryPoint = "neton.http.bench.main" }
        }
    }
    sourceSets {
        commonMain.dependencies { implementation(project(":http")) }
    }
}
