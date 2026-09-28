plugins { kotlin("multiplatform") }

// Same targets as com.netonstream:io. The common HTTP types (SPEC §2) are pure Kotlin in commonMain; the
// connection layers (h1, h2) will add the io dependency.
kotlin {
    linuxX64(); linuxArm64()
    macosArm64(); macosX64()
    mingwX64()
    iosArm64(); iosSimulatorArm64(); iosX64()
    androidNativeArm64(); androidNativeArm32(); androidNativeX64(); androidNativeX86()

    sourceSets {
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}
