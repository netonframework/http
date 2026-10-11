plugins { kotlin("multiplatform"); `maven-publish` }

// Same targets as com.netonstream:io, the JVM included (desktop and Android apps): bytecode 1.8, API 2.2 and stdlib
// 2.2.21 so Android hosts on Kotlin 2.1 can use it (as neton-io).
kotlin {
    coreLibrariesVersion = "2.2.21"
    // Language 2.2 for every source set the JVM compiles (KMP also requires a source set to be no older than the ones it
    // depends on, so native-only ones may stay newer): class metadata then says 2.2, which a Kotlin 2.1 compiler still
    // reads (one version ahead); with the 2.4 default it refuses to compile against these classes.
    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
            apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
            languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
        }
        compilations.configureEach { allKotlinSourceSets.forAll { it.languageSettings.languageVersion = "2.2" } }
    }
    linuxX64(); linuxArm64()
    macosArm64(); macosX64()
    mingwX64()
    iosArm64(); iosSimulatorArm64(); iosX64()
    androidNativeArm64(); androidNativeArm32(); androidNativeX64(); androidNativeX86()

    sourceSets {
        commonMain.dependencies { api("com.netonstream:io:0.4.0") }
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}
