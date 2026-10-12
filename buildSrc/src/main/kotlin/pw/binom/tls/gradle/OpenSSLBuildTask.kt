package pw.binom.tls.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Builds OpenSSL 3.0.3 as static libraries (libssl.a + libcrypto.a)
 * for a specific Kotlin/Native target, by running ./Configure + make in
 * the OpenSSL source tree.
 *
 * The configured source tree is REUSED across invocations: each run
 * re-runs ./Configure (idempotent — overwrites Makefile + configdata.pm)
 * and `make build_libs` (OpenSSL's make only rebuilds what changed).
 * To force a clean rebuild, delete the source dir.
 *
 * Supported targets: linux_x64, linux_arm64. Other targets throw an
 * explicit error — they need a cross-compiler (NDK for Android, Apple
 * toolchain for macOS/iOS, mingw-w64 for Windows) which the host may
 * not have. Add support by extending [configureFor].
 */
abstract class OpenSSLBuildTask : DefaultTask() {

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val sourceDir: DirectoryProperty

    @get:Input
    abstract val target: Property<String>

    @get:Input
    abstract val jobs: Property<Int>

    @get:OutputFile
    abstract val libssl: RegularFileProperty

    @get:OutputFile
    abstract val libcrypto: RegularFileProperty

    init {
        jobs.convention(Runtime.getRuntime().availableProcessors())
        group = "build"
        description = "Builds OpenSSL 3.0.3 static libraries for the given KMP target"
    }

    @TaskAction
    fun build() {
        val src = sourceDir.get().asFile
        val tgt = target.get()

        check(File(src, "Configure").exists()) {
            "OpenSSL source not extracted at $src (missing ./Configure). " +
                "Run :extractSsl first."
        }

        val (configureName, env, extraArgs) = configureFor(tgt)

        logger.lifecycle("=== OpenSSL: configuring for $tgt (${configureName}) ===")
        runProcess(
            workingDir = src,
            args = listOf("./Configure", configureName) + extraArgs,
            env = env,
        )

        logger.lifecycle("=== OpenSSL: building for $tgt (jobs=${jobs.get()}) ===")
        runProcess(
            workingDir = src,
            args = listOf("make", "-j${jobs.get()}", "build_libs"),
            env = env,
        )

        val builtSsl = File(src, "libssl.a")
        val builtCrypto = File(src, "libcrypto.a")
        check(builtSsl.exists()) { "libssl.a not produced in $src for $tgt" }
        check(builtCrypto.exists()) { "libcrypto.a not produced in $src for $tgt" }

        val outDir = libssl.get().asFile.parentFile
        outDir.mkdirs()
        builtSsl.copyTo(libssl.get().asFile, overwrite = true)
        builtCrypto.copyTo(libcrypto.get().asFile, overwrite = true)

        logger.lifecycle("=== OpenSSL $tgt: ${libssl.get().asFile} + ${libcrypto.get().asFile} ===")
    }

    /**
     * Whether [target] needs extra CFLAGS to keep OpenSSL from calling
     * `__isoc23_strtol` / `__isoc23_strtoul` / `__isoc23_sscanf`.
     *
     * These symbols are only present in glibc 2.38+ AND only referenced when
     * the C compiler is in C23 mode (gcc 14+ defaults to `gnu23`, which
     * routes `strtol()` → `__isoc23_strtol`). Kotlin/Native's LLVM 21
     * `ld.lld` doesn't have a glibc version map and fails on these
     * versioned symbols.
     *
     * Fix: pin the C standard to `gnu17` (or older). The older `strtol`/
     * `strtoul`/`sscanf` are always present in libc.so.6 without a version
     * suffix and link cleanly.
     */
    private fun linuxLikeCflags(tgt: String): Map<String, String> = when (tgt) {
        "linux_x64", "linux_arm64",
        "android_arm32", "android_arm64", "android_x64", "android_x86",
        -> mapOf("CFLAGS" to "-std=gnu17")
        else -> emptyMap()
    }

    private data class TargetConfig(
        val configureName: String,
        val env: Map<String, String>,
        val extraArgs: List<String>,
    )

    private fun configureFor(tgt: String): TargetConfig {
        // OpenSSL 3.0.3 Configure options. The list is intentionally minimal —
        // each item below is verified in Configurations/10-main.conf /
        // INSTALL.md. In particular: no-err / no-cms / no-ts / no-psk / no-srp
        // are all valid; do NOT add no-engines (it's no-engine singular in 3.x,
        // and no-engines triggers the "unsupported options" error).
        val baseArgs = listOf(
            "no-shared",
            "no-tests",
            "no-ui-console",
            "no-asm",
            "no-async",
            "no-err",
            "no-psk",
            "no-srp",
            "no-cms",
            "no-ts",
            "enable-tls1_3",
        )
        val linuxLikeEnv: Map<String, String> = linuxLikeCflags(tgt)
        // iOS / macOS Configure targets fall in two camps:
        //   * darwin64-*-cc       — pure clang; cross-compile from any host.
        //   * *-xcrun             — invokes xcrun; macOS host only.
        //   * ios64-cross         — iOS device arm64; cross-compile from any host.
        // The simulator targets (iosX64, iosSimulatorArm64) only have
        // iossimulator-xcrun in 3.0.3, so they're macOS-host-only. On a
        // Linux build host, surface that as a clear error rather than
        // letting ./Configure dump a target list and exit 1.
        if (tgt == "ios_x64" || tgt == "ios_simulator_arm64") {
            check(isMacOsHost()) {
                "OpenSSL 3.0.3 only ships the 'iossimulator-xcrun' Configure " +
                    "target for the iOS simulator, and that requires xcrun on a " +
                    "macOS host. KMP target '$tgt' therefore can't be built on " +
                    "this host (${System.getProperty("os.name")}). Build it on a " +
                    "Mac, or skip this target on the CI."
            }
        }
        return when (tgt) {
            "linux_x64" -> TargetConfig("linux-x86_64", linuxLikeEnv, baseArgs)
            "linux_arm64" -> TargetConfig("linux-aarch64", linuxLikeEnv, baseArgs)
            "android_arm32" -> TargetConfig("android-arm", androidEnv("armv7a-linux-androideabi24-clang") + linuxLikeEnv, baseArgs)
            "android_arm64" -> TargetConfig("android-arm64", androidEnv("aarch64-linux-android24-clang") + linuxLikeEnv, baseArgs)
            "android_x64" -> TargetConfig("android-x86_64", androidEnv("x86_64-linux-android24-clang") + linuxLikeEnv, baseArgs)
            "android_x86" -> TargetConfig("android-x86", androidEnv("i686-linux-android24-clang") + linuxLikeEnv, baseArgs)
            "mingw_x64" -> TargetConfig("mingw64", mingwEnv(), baseArgs)
            "macos_x64" -> TargetConfig("darwin64-x86_64-cc", appleEnv(), baseArgs)
            "macos_arm64" -> TargetConfig("darwin64-arm64-cc", appleEnv(), baseArgs)
            "ios_x64" -> TargetConfig("iossimulator-xcrun", appleEnv(), baseArgs)
            "ios_arm64" -> TargetConfig("ios64-cross", appleEnv(), baseArgs)
            "ios_simulator_arm64" -> TargetConfig("iossimulator-xcrun", appleEnv(), baseArgs)
            else -> error(
                "OpenSSL self-build not implemented for KMP target '$tgt'. " +
                    "Add a branch in OpenSSLBuildTask.configureFor() with the right " +
                    "./Configure target name and (if needed) cross-compiler env vars.",
            )
        }
    }

    private fun isMacOsHost(): Boolean =
        System.getProperty("os.name").startsWith("Mac")

    private fun androidEnv(clangName: String): Map<String, String> {
        val ndk = System.getenv("ANDROID_NDK_HOME")
            ?: error(
                "ANDROID_NDK_HOME is not set; required to build OpenSSL for Android native targets. " +
                    "Install the NDK and export ANDROID_NDK_HOME=<path>.",
            )
        val toolchainBin = "$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"
        val clang = "$toolchainBin/$clangName"
        check(File(clang).exists()) {
            "Android NDK clang not found at $clang. " +
                "Check that \$ANDROID_NDK_HOME points to a valid NDK install."
        }
        val ndkBinOnPath = "$toolchainBin:" + (System.getenv("PATH") ?: "")
        return mapOf(
            // Don't pre-set CC to the arch-specific clang binary: OpenSSL 3.0.3's
            // android-arm Configure target's auto-detect (Configurations/15-android.conf
            // line 106) checks `which("clang")` and only picks the per-arch clang if
            // the generic `clang` (a symlink to clang-17 in NDK r26+) is on PATH and
            // resolves to $ndk/.../prebuilt/<host>/. When $user{CC} is pre-set to a
            // full path, the auto-detect is skipped and Configure falls through to
            // the GCC-toolchain check (line 142), which fails on modern NDK that
            // removed GCC. So: PATH-only, let Configure pick the per-arch clang.
            "PATH" to ndkBinOnPath,
            "AR" to "$toolchainBin/llvm-ar",
            "RANLIB" to "$toolchainBin/llvm-ranlib",
            "ANDROID_NDK_HOME" to ndk,
            "ANDROID_NDK_ROOT" to ndk,
        )
    }

    private fun mingwEnv(): Map<String, String> = mapOf(
        "CC" to "x86_64-w64-mingw32-gcc",
        "AR" to "x86_64-w64-mingw32-ar",
        "RANLIB" to "x86_64-w64-mingw32-ranlib",
    )

    private fun appleEnv(): Map<String, String> = emptyMap()

    private fun runProcess(workingDir: File, args: List<String>, env: Map<String, String>) {
        val builder = ProcessBuilder(args)
            .directory(workingDir)
            .redirectErrorStream(true)
        if (env.isNotEmpty()) {
            builder.environment().putAll(env)
        }
        val proc = builder.start()
        proc.inputStream.bufferedReader().use { reader ->
            reader.lineSequence().forEach { line ->
                logger.lifecycle("[${workingDir.name}] $line")
            }
        }
        val exit = proc.waitFor()
        if (exit != 0) {
            throw GradleException(
                "Process exited with $exit: ${args.joinToString(" ")} (in $workingDir)",
            )
        }
    }
}
