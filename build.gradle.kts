import org.gradle.api.tasks.testing.Test
import pw.binom.tls.gradle.OpenSSLBuildTask
import java.io.File
import java.net.URI

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.dokka)
    alias(libs.plugins.vanniktech.maven.publish)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
}

group = "pw.binom"
version = "0.1.0-SNAPSHOT"

// ----------------------------------------------------------------------------
// OpenSSL: self-built per native target via buildSrc/OpenSSLBuildTask.
//
// Source is downloaded once from GitHub into build/openssl/, extracted, and
// configured + built per target. Output is a static libssl.a + libcrypto.a
// pair (no libcrypto.so.3 dependency at runtime — the .a is baked into our
// KMP binary). This replaces the previously-used prebuilt .a files from
// /home/subochev/projects/WORK/pw.binom.io/ssl/build/openssl, which were
// compiled against a stale GLIBC and segfaulted in d2i_PrivateKey at runtime.
//
// Headers (162 files, OpenSSL 3.0.3) live in src/nativeInterop/cinterop/openssl/
// — no dependency on the reference project for headers or prebuilts.
// ----------------------------------------------------------------------------

val opensslVersion = "3.0.3"
val opensslZipUrl = "https://github.com/openssl/openssl/archive/refs/tags/openssl-$opensslVersion.zip"

val downloadSsl by tasks.registering {
    val output = layout.buildDirectory.file("openssl/openssl-$opensslVersion.zip")
    outputs.file(output)
    group = "build"
    description = "Downloads OpenSSL $opensslVersion source from GitHub"
    doLast {
        val out = output.get().asFile
        out.parentFile.mkdirs()
        if (out.exists() && out.length() > 14_000_000) {
            logger.lifecycle("OpenSSL $opensslVersion archive already present at $out (${out.length()} bytes)")
            return@doLast
        }
        logger.lifecycle("Downloading OpenSSL $opensslVersion from $opensslZipUrl")
        URI(opensslZipUrl).toURL().openStream().use { input ->
            out.outputStream().use { sink -> input.copyTo(sink) }
        }
        check(out.length() > 14_000_000) { "Downloaded archive is suspiciously small: ${out.length()} bytes" }
    }
}

val extractSsl by tasks.registering {
    dependsOn(downloadSsl)
    val output = layout.buildDirectory.dir("openssl/source")
    inputs.file(layout.buildDirectory.file("openssl/openssl-$opensslVersion.zip"))
    outputs.dir(output)
    group = "build"
    description = "Extracts OpenSSL $opensslVersion source into build/openssl/source/"
    doLast {
        val out = output.get().asFile
        if (out.exists() && File(out, "Configure").exists()) {
            logger.lifecycle("OpenSSL source already extracted at $out")
            return@doLast
        }
        out.deleteRecursively()
        out.mkdirs()
        val zip = downloadSsl
            .get()
            .outputs.files.singleFile
        val tempDir = File(out.parentFile, "extract-temp")
        tempDir.deleteRecursively()
        tempDir.mkdirs()
        logger.lifecycle("Unzipping $zip to $tempDir")
        val proc = ProcessBuilder("unzip", "-q", "-o", zip.absolutePath, "-d", tempDir.absolutePath)
            .redirectErrorStream(true)
            .start()
        val exit = proc.waitFor()
        check(exit == 0) { "unzip failed with exit $exit" }
        val extracted: File = tempDir.listFiles()!!.first { it.isDirectory }
        check(extracted.renameTo(out)) { "Failed to rename $extracted -> $out" }
        tempDir.deleteRecursively()
    }
}

fun opensslBuildTask(name: String, konanTargetName: String) =
    tasks.register<OpenSSLBuildTask>("buildOpenssl$name") {
        dependsOn(extractSsl)
        sourceDir.set(layout.buildDirectory.dir("openssl/source"))
        target.set(konanTargetName)
        libssl.set(layout.buildDirectory.file("openssl/$konanTargetName/libssl.a"))
        libcrypto.set(layout.buildDirectory.file("openssl/$konanTargetName/libcrypto.a"))
        description = "Builds OpenSSL $opensslVersion static libs for $name"
    }

val buildOpensslLinuxX64 = opensslBuildTask("LinuxX64", "linux_x64")
val buildOpensslLinuxArm64 = opensslBuildTask("LinuxArm64", "linux_arm64")
val buildOpensslAndroidArm32 = opensslBuildTask("AndroidArm32", "android_arm32")
val buildOpensslAndroidArm64 = opensslBuildTask("AndroidArm64", "android_arm64")
val buildOpensslAndroidX64 = opensslBuildTask("AndroidX64", "android_x64")
val buildOpensslAndroidX86 = opensslBuildTask("AndroidX86", "android_x86")
val buildOpensslMingwX64 = opensslBuildTask("MingwX64", "mingw_x64")
val buildOpensslMacosX64 = opensslBuildTask("MacosX64", "macos_x64")
val buildOpensslMacosArm64 = opensslBuildTask("MacosArm64", "macos_arm64")
val buildOpensslIosX64 = opensslBuildTask("IosX64", "ios_x64")
val buildOpensslIosArm64 = opensslBuildTask("IosArm64", "ios_arm64")
val buildOpensslIosSimulatorArm64 = opensslBuildTask("IosSimulatorArm64", "ios_simulator_arm64")

kotlin {
    jvm()
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
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines)
            implementation(libs.ktor.network)
        }
        jvmMain.dependencies {
            implementation(libs.bouncycastle.bcprov)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.bouncycastle.bcpkix)
        }
    }
}

kotlin.targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>().configureEach {
    val nativeTarget = this
    val targetName = nativeTarget.name

    val opensslBuild: org.gradle.api.tasks.TaskProvider<OpenSSLBuildTask>? = when (targetName) {
        "linuxX64" -> buildOpensslLinuxX64
        "linuxArm64" -> buildOpensslLinuxArm64
        "androidNativeArm32" -> buildOpensslAndroidArm32
        "androidNativeArm64" -> buildOpensslAndroidArm64
        "androidNativeX64" -> buildOpensslAndroidX64
        "androidNativeX86" -> buildOpensslAndroidX86
        "mingwX64" -> buildOpensslMingwX64
        "macosArm64" -> buildOpensslMacosArm64
        "macosX64" -> buildOpensslMacosX64
        "iosX64" -> buildOpensslIosX64
        "iosArm64" -> buildOpensslIosArm64
        "iosSimulatorArm64" -> buildOpensslIosSimulatorArm64
        else -> null
    }

    compilations.getByName("main") {
        cinterops {
            val openssl by creating {
                defFile(file("src/nativeInterop/cinterop/openssl.def"))
                // headerFilterOnly() scopes the include path to our local OpenSSL
                // headers (162 files, src/nativeInterop/cinterop/openssl/); the
                // parent dir is "cinterop/", which is what the def's
                // `headers = openssl/ssl.h ...` resolves against.
                includeDirs.headerFilterOnly(file("src/nativeInterop/cinterop").absolutePath)
            }
        }
        compileTaskProvider.configure {
            if (opensslBuild != null) {
                dependsOn(opensslBuild)
                compilerOptions.freeCompilerArgs.addAll(
                    "-include-binary",
                    opensslBuild
                        .get()
                        .libssl
                        .get()
                        .asFile.absolutePath,
                    "-include-binary",
                    opensslBuild
                        .get()
                        .libcrypto
                        .get()
                        .asFile.absolutePath,
                )
            }
        }
    }

    nativeTarget.binaries.all {
        if (opensslBuild != null) {
            // Self-built OpenSSL: the .a is already attached via -include-binary
            // above. Only need glibc / mingw system libs at link time.
            val linuxExtras = if (targetName.startsWith("linux") || targetName.startsWith("android")) {
                listOf("-lresolv", "-lm", "-lpthread", "-lutil", "-lcrypt", "-lrt", "-ldl")
            } else {
                listOf("-lwsock32", "-lws2_32", "-lcrypt32", "-lbcrypt")
            }
            linkerOpts(linuxExtras)
        } else {
            // No self-built OpenSSL for this target: link against the host's
            // system / SDK libssl + libcrypto (only used on developer machines
            // for quick smoke tests; release builds should always self-build).
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
