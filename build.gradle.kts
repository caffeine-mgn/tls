import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.dokka)
    alias(libs.plugins.vanniktech.maven.publish)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
}

group = "pw.binom"
version = "0.1.0-SNAPSHOT"

kotlin {
    jvm()
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate()

    linuxX64()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.io.core)
            implementation(libs.kotlinx.io.bytestring)
            implementation(libs.kotlinx.atomicfu)
            implementation(libs.kotlinx.coroutines)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmMain.dependencies {
            implementation(libs.bouncycastle.bcprov)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.bouncycastle.bcpkix)
        }
        // linuxX64 pulls in OpenSSL via cinterop + prebuilt libssl/libcrypto
        linuxX64Main.dependencies { }
        linuxX64Test.dependencies {
            implementation(kotlin("test"))
        }
    }
}

kotlin.targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>().configureEach {
    val nativeTarget = this
    val opensslLibSsl = "/home/subochev/projects/WORK/pw.binom.io/ssl/build/openssl/linux_x64/build/libssl.a"
    val opensslLibCrypto = "/home/subochev/projects/WORK/pw.binom.io/ssl/build/openssl/linux_x64/build/libcrypto.a"
    compilations.getByName("main") {
        cinterops {
            val openssl by creating {
                defFile(file("src/nativeInterop/cinterop/openssl.def"))
                includeDirs("/usr/include")
                compilerOpts("-I/usr/include")
                linkerOpts(opensslLibSsl, opensslLibCrypto)
            }
        }
    }
    // Link against the prebuilt libssl.a / libcrypto.a (sourced from the
    // reference build) for both main and test. Static linking avoids the
    // GLIBC version-mismatch that the system .so creates against the
    // LLVM 21 toolchain.
    nativeTarget.binaries.all {
        linkerOpts(
            opensslLibSsl, opensslLibCrypto,
            "-lresolv", "-lm", "-lpthread", "-lutil", "-lcrypt", "-lrt", "-ldl",
        )
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
    useJUnit()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
        showExceptions = true
        showCauses = true
        showStackTraces = true
    }
}
