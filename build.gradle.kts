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
        // linuxX64 pulls in OpenSSL via cinterop + system libssl/libcrypto
        linuxX64Main.dependencies { }
        linuxX64Test.dependencies {
            implementation(kotlin("test"))
        }
    }
}

kotlin.targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>().configureEach {
    compilations.getByName("main") {
        cinterops {
            val openssl by creating {
                defFile(file("src/nativeInterop/cinterop/openssl.def"))
                includeDirs("/usr/include")
                compilerOpts("-I/usr/include")
                linkerOpts("-L/usr/lib", "-lssl", "-lcrypto")
            }
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
    useJUnit()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
        showExceptions = true
        showCauses = true
        showStackTraces = true
    }
}
