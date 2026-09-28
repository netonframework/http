plugins { kotlin("multiplatform") }

// Benchmark servers (SPEC §8): not published.
kotlin {
    listOf(linuxX64(), linuxArm64(), macosArm64()).forEach { target ->
        target.binaries {
            executable("helloServer") { entryPoint = "neton.http.bench.main" }
            // Same code with debug info, for line-level profiles (cachegrind / callgrind).
            executable("helloServerProfile") { entryPoint = "neton.http.bench.main"; freeCompilerArgs += "-g" }
            // The curl interop check (SPEC §6).
            executable("echoServer") { entryPoint = "neton.http.bench.echoMain" }
        }
    }
    sourceSets {
        commonMain.dependencies { implementation(project(":http")) }
    }
}
