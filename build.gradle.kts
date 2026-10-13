import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.konan.target.Family
import org.jetbrains.kotlin.konan.target.HostManager
import org.jetbrains.kotlin.konan.target.KonanTarget
import pw.binom.kotlin.clang.KnClangExtension
import pw.binom.tls.gradle.OpenSSLBuildTask
import pw.binom.tls.gradle.OpenSslBuildGuard
import java.io.File
import java.net.URI

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kn.clang)
    alias(libs.plugins.dokka)
    alias(libs.plugins.vanniktech.maven.publish)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
}

group = "pw.binom"
version = "0.1.0-SNAPSHOT"

// The kn-clang extension (applied above) — resolves the Kotlin/Native Clang
// toolchain (compiler, archiver, sysroot, per-target CFLAGS) for us.
val knClang = extensions.getByName("knClang") as KnClangExtension

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

// All K/N toolchain access goes through the `pw.binom.kn-clang` plugin's
// `knClang.toolchain(target)`. The plugin reads the selected Kotlin/Native
// distribution's `konan.properties` and resolves the compiler, archiver,
// sysroot, `--gcc-toolchain`, and the per-target `-target` / `--sysroot` /
// `-isystem` flags — the same flag set the Kotlin/Native backend itself
// uses. Because the flags are consistent (headers AND libc come from the
// same sysroot), cross-targets (linuxArm64, android*, mingw, apple) work
// exactly like they do for any other C library built with this plugin;
// no hand-rolled sysroot probing.
//
// In particular the host build needs `--gcc-toolchain=<gcc8>` so clang
// resolves the glibc 2.19 headers inside the sysroot instead of the host
// gcc 16 headers (which would pull in `__isoc23_*` versioned symbols the
// sysroot's libc doesn't define).
//
// The OpenSSLBuildTask stays K/N-agnostic: it only receives a configure
// name, a CFLAGS line and an env map, resolved lazily at task-execution
// time (so the first-run sysroot download does not slow Gradle config).

fun opensslConfigureName(kt: KonanTarget): String = when (kt) {
    KonanTarget.LINUX_X64 -> "linux-x86_64"
    KonanTarget.LINUX_ARM64 -> "linux-aarch64"
    KonanTarget.MINGW_X64 -> "mingw64"
    KonanTarget.MACOS_X64 -> "darwin64-x86_64-cc"
    KonanTarget.MACOS_ARM64 -> "darwin64-arm64-cc"
    KonanTarget.IOS_ARM64 -> "ios64-cross"
    KonanTarget.IOS_X64, KonanTarget.IOS_SIMULATOR_ARM64 -> "iossimulator-xcrun"
    // Android: the plugin's cFlags already carry `-target *-linux-android`
    // (which makes clang define `__ANDROID__`, driving OpenSSL's bionic
    // code paths) plus the bionic sysroot and `-D__ANDROID_API__`. OpenSSL's
    // own `android-*` configs insist on a full NDK layout and reject a
    // custom CC, so we use the generic linux config name; the triplet and
    // sysroot still come from the flags.
    KonanTarget.ANDROID_ARM32 -> "linux-armv4"
    KonanTarget.ANDROID_ARM64 -> "linux-aarch64"
    KonanTarget.ANDROID_X86 -> "linux-elf"
    KonanTarget.ANDROID_X64 -> "linux-x86_64"
    else -> error("OpenSSL self-build not implemented for $kt")
}

data class OpenSSLToolchain(
    val configureName: String,
    val cflags: String,
    val env: Map<String, String>,
)

fun opensslToolchain(kt: KonanTarget): OpenSSLToolchain {
    val tc = knClang.toolchain(kt)
    val llvmBin = tc.archiver.parentFile
    val ranlib = File(llvmBin, "llvm-ranlib" + if (tc.archiver.name.endsWith(".exe")) ".exe" else "")
    val extra = mutableListOf("-std=gnu17")
    // The K/N Linux sysroots ship glibc 2.19/2.25, where `strcasecmp_l` and
    // friends are only declared under POSIX-2008 feature macros (OpenSSL's
    // linux config relies on the newer host glibc declaring them by default).
    // Android's bionic must NOT see `_GNU_SOURCE`: it makes OpenSSL pick the
    // GNU `char *strerror_r` while bionic returns `int`.
    if (kt.family == Family.LINUX) extra += "-D_GNU_SOURCE"
    val cflags = (extra + tc.cFlags).joinToString(" ")
    val env = mapOf(
        "CC" to tc.compiler.absolutePath,
        "CXX" to tc.cxxCompiler.absolutePath,
        "AR" to tc.archiver.absolutePath,
        "RANLIB" to (if (ranlib.exists()) ranlib.absolutePath else "ranlib"),
    )
    return OpenSSLToolchain(opensslConfigureName(kt), cflags, env)
}

val opensslBuildGuard = gradle.sharedServices.registerIfAbsent("opensslBuildGuard", OpenSslBuildGuard::class) {
    maxParallelUsages.set(1)
}

fun opensslBuildTask(name: String, kmpTargetName: String, kt: KonanTarget) =
    tasks.register<OpenSSLBuildTask>("buildOpenssl$name") {
        usesService(opensslBuildGuard)
        dependsOn(extractSsl)
        sourceDir.set(layout.buildDirectory.dir("openssl/source"))
        targetName.set(kt.name)
        libssl.set(layout.buildDirectory.file("openssl/${kt.name}/libssl.a"))
        libcrypto.set(layout.buildDirectory.file("openssl/${kt.name}/libcrypto.a"))
        description = "Builds OpenSSL $opensslVersion static libs for $name (${kt.name})"
        // The two providers below run at task-execution time, so the
        // K/N dependency download (sysroot / NDK materialize()) is deferred
        // to the actual build, not Gradle configuration.
        val toolchain = providers.provider { opensslToolchain(kt) }
        configureName.set(toolchain.map { it.configureName })
        cflags.set(toolchain.map { it.cflags })
        extraEnv.set(toolchain.map { it.env })
        // The K/N cinterop task uses a custom Up-To-Date check (header
        // content) that ignores our .a files. So when OpenSSL is rebuilt
        // and the .a changes, the klib's targets/.../included/libssl.a and
        // libcrypto.a stay stale, and downstream link steps reference
        // the old symbols. Workaround: after building, copy the fresh .a
        // files into BOTH the cinterop klib's and the main klib's
        // included/ directories. (The link step reads from
        // klib/tls/.../included/; the cinterop klib is in
        // cinterop/tls-cinterop-openssl/.../included/.)
        doLast {
            val srcSsl = libssl.get().asFile
            val srcCrypto = libcrypto.get().asFile
            logger.lifecycle("=== buildOpenssl$name: copying ${srcCrypto.length()}B to klib included dirs ===")
            listOf(
                "classes/kotlin/$kmpTargetName/main/cinterop/tls-cinterop-openssl/default/targets/${kt.name}/included",
                "classes/kotlin/$kmpTargetName/main/klib/tls/default/targets/${kt.name}/included",
            ).forEach { relPath ->
                val dir = layout.buildDirectory
                    .dir(relPath)
                    .get()
                    .asFile
                if (dir.exists() || dir.mkdirs()) {
                    srcSsl.copyTo(File(dir, "libssl.a"), overwrite = true)
                    srcCrypto.copyTo(File(dir, "libcrypto.a"), overwrite = true)
                    logger.lifecycle("=== buildOpenssl$name: copied to $dir ===")
                } else {
                    logger.lifecycle("=== buildOpenssl$name: cannot create $dir ===")
                }
            }
        }
    }

val buildOpensslLinuxX64 = opensslBuildTask("LinuxX64", "linuxX64", KonanTarget.LINUX_X64)
val buildOpensslLinuxArm64 = opensslBuildTask("LinuxArm64", "linuxArm64", KonanTarget.LINUX_ARM64)
val buildOpensslAndroidArm32 = opensslBuildTask("AndroidArm32", "androidNativeArm32", KonanTarget.ANDROID_ARM32)
val buildOpensslAndroidArm64 = opensslBuildTask("AndroidArm64", "androidNativeArm64", KonanTarget.ANDROID_ARM64)
val buildOpensslAndroidX64 = opensslBuildTask("AndroidX64", "androidNativeX64", KonanTarget.ANDROID_X64)
val buildOpensslAndroidX86 = opensslBuildTask("AndroidX86", "androidNativeX86", KonanTarget.ANDROID_X86)
val buildOpensslMingwX64 = opensslBuildTask("MingwX64", "mingwX64", KonanTarget.MINGW_X64)
val buildOpensslMacosX64 = opensslBuildTask("MacosX64", "macosX64", KonanTarget.MACOS_X64)
val buildOpensslMacosArm64 = opensslBuildTask("MacosArm64", "macosArm64", KonanTarget.MACOS_ARM64)
val buildOpensslIosX64 = opensslBuildTask("IosX64", "iosX64", KonanTarget.IOS_X64)
val buildOpensslIosArm64 = opensslBuildTask("IosArm64", "iosArm64", KonanTarget.IOS_ARM64)
val buildOpensslIosSimulatorArm64 =
    opensslBuildTask("IosSimulatorArm64", "iosSimulatorArm64", KonanTarget.IOS_SIMULATOR_ARM64)

kotlin {
    jvm()
    applyDefaultHierarchyTemplate()

    linuxX64()
    linuxArm64()

    // Apple targets require a macOS host (Kotlin/Native has no cross-compiler
    // for them, and OpenSSL must be built with the Apple SDK). Declaring them
    // on Linux makes the metadata/publish tasks demand an iOS OpenSSL build,
    // which cannot succeed. Publish Apple artefacts from a mac.
    if (HostManager.hostIsMac) {
        iosArm64()
        iosSimulatorArm64()
        iosX64()

        macosArm64()
    }

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
            if (opensslBuild != null) {
                // Make the cinterop task treat the self-built .a files as
                // inputs, so it re-runs and copies the fresh libssl.a /
                // libcrypto.a into the klib's targets/.../included/
                // directory whenever the OpenSSL build changes its
                // output. Without this, downstream link steps pick up
                // a stale copy of the .a inside the klib.
                tasks.named("cinteropOpenssl${targetName.replaceFirstChar { it.uppercase() }}") {
                    dependsOn(opensslBuild)
                    inputs.file(opensslBuild.get().libssl)
                    inputs.file(opensslBuild.get().libcrypto)
                }
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
            // above. Only the platform system libs (resolv/m/dl/pthread, or the
            // Windows sockets stack) need to be named at link time.
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
