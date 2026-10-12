import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.dokka)
    alias(libs.plugins.vanniktech.maven.publish)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.android.library)
}

group = "pw.binom"
version = "0.1.0-SNAPSHOT"

android {
    namespace = "pw.binom.tls"
    compileSdk = 34
    defaultConfig {
        minSdk = 24
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

// Prebuilt OpenSSL static libs from the reference project. Each native
// target picks the matching arch. Targets not listed (macos, ios) fall
// back to system / SDK libraries at link time.
val opensslRoot = "/home/subochev/projects/WORK/pw.binom.io/ssl/build/openssl"

fun opensslLibs(arch: String): Pair<String, String> =
    "$opensslRoot/$arch/build/libssl.a" to "$opensslRoot/$arch/build/libcrypto.a"

val linuxX64Libs = opensslLibs("linux_x64")
val linuxArm64Libs = opensslLibs("linux_arm64")
val androidArm32Libs = opensslLibs("android_arm32")
val androidArm64Libs = opensslLibs("android_arm64")
val androidX64Libs = opensslLibs("android_x64")
val androidX86Libs = opensslLibs("android_x86")
val mingwX64Libs = opensslLibs("mingw_x64")

kotlin {
    jvm()
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
    androidTarget()
    applyDefaultHierarchyTemplate()

    linuxX64()
    linuxArm64()

    iosArm64()
    iosSimulatorArm64()
    iosX64()

    macosArm64()

    mingwX64()

    androidNativeArm32()
    androidNativeArm64()
    androidNativeX64()
    androidNativeX86()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.io.core)
            implementation(libs.kotlinx.io.bytestring)
            implementation(libs.kotlinx.atomicfu)
            implementation(libs.kotlinx.coroutines)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.ktor.network)
        }
        jvmMain.dependencies {
            implementation(libs.bouncycastle.bcprov)
        }
        androidMain.dependencies {
            implementation(libs.bouncycastle.bcprov)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.bouncycastle.bcpkix)
        }
        androidUnitTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.bouncycastle.bcpkix)
        }
        linuxX64Main.dependencies { }
        linuxX64Test.dependencies {
            implementation(kotlin("test"))
        }
        linuxArm64Main.dependencies { }
        linuxArm64Test.dependencies {
            implementation(kotlin("test"))
        }
    }
}

kotlin.targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>().configureEach {
    val nativeTarget = this
    val targetName = nativeTarget.name

    // Pick the prebuilt .a for this target's arch. macOS and iOS use
    // the system / SDK libraries (no prebuilt shipped with the reference).
    val libs: Pair<String, String>? = when (targetName) {
        "linuxX64" -> linuxX64Libs
        "linuxArm64" -> linuxArm64Libs
        "androidNativeArm32" -> androidArm32Libs
        "androidNativeArm64" -> androidArm64Libs
        "androidNativeX64" -> androidX64Libs
        "androidNativeX86" -> androidX86Libs
        "mingwX64" -> mingwX64Libs
        else -> null
    }
    val sslA = libs?.first
    val cryptoA = libs?.second

    compilations.getByName("main") {
        cinterops {
            val openssl by creating {
                defFile(file("src/nativeInterop/cinterop/openssl.def"))
                // headerFilterOnly() scopes the include path to the
                // prebuilt OpenSSL headers (162 files, all needed for
                // the transitive #includes) — no /usr/include recursion,
                // so <bits/floatn.h> on aarch64 etc. never enter.
                includeDirs.headerFilterOnly(
                    "/home/subochev/projects/WORK/pw.binom.io/ssl/src/cinterop/include",
                )
                if (sslA != null) {
                    linkerOpts(sslA, cryptoA!!)
                }
            }
        }
    }

    nativeTarget.binaries.all {
        if (sslA != null) {
            // Prebuilt .a path: static link + standard system libs.
            // -lresolv/-lutil/-lcrypt/-lrt are Linux glibc specific and
            // don't exist on mingw; we only add them on Linux.
            val linuxExtras = if (targetName.startsWith("linux") || targetName.startsWith("android")) {
                listOf("-lresolv", "-lm", "-lpthread", "-lutil", "-lcrypt", "-lrt", "-ldl")
            } else {
                listOf("-lwsock32", "-lws2_32", "-lcrypt32", "-lbcrypt")
            }
            linkerOpts(sslA, cryptoA!!)
            linkerOpts(linuxExtras)
        } else {
            // macOS / iOS: no prebuilt available, link against the SDK's
            // libssl/libcrypto (Apple ships them as part of the platform).
            linkerOpts(
                "-L/usr/lib", "-lssl", "-lcrypto",
                "-lresolv", "-lm", "-lpthread", "-lutil", "-ldl",
            )
        }
    }
}

detekt {
    config.setFrom("config/detekt/detekt.yml")
    buildUponDefaultConfig = false
    allRules = false
}

dokka {
    moduleName.set("tls")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
        showExceptions = true
        showCauses = true
        showStackTraces = true
    }
}
