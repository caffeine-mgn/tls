package pw.binom.tls.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Builds OpenSSL 3.0.3 as static libraries (libssl.a + libcrypto.a) for a
 * specific KMP target by running ./Configure + make in the OpenSSL source
 * tree.
 *
 * This task is intentionally K/N-API-agnostic: it only knows about a
 * `configureName` (e.g. `linux-x86_64`), a fully-formed `cflags` line
 * (with `-target <triple> --sysroot=<sysroot> --gcc-toolchain=<gcc>` and
 * `-D_GNU_SOURCE` where needed, already resolved by the caller) and an
 * `extraEnv` map (CC, CXX, AR, RANLIB, …). The caller (`build.gradle.kts`)
 * computes these from the `pw.binom.kn-clang` plugin's
 * `knClang.toolchain(target)`, which reads the selected Kotlin/Native
 * distribution's `konan.properties` — so the task never touches the K/N
 * Gradle plugin API and buildSrc keeps its minimal classpath.
 *
 * The configured source tree is REUSED across invocations: each run
 * re-runs ./Configure (idempotent — overwrites Makefile + configdata.pm)
 * and `make build_libs` (OpenSSL's make only rebuilds what changed).
 * To force a clean rebuild, delete the source dir.
 */
abstract class OpenSSLBuildTask : DefaultTask() {

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val sourceDir: DirectoryProperty

    @get:Input
    abstract val targetName: Property<String>

    @get:Input
    abstract val configureName: Property<String>

    @get:Input
    abstract val cflags: Property<String>

    @get:Input
    abstract val extraEnv: MapProperty<String, String>

    @get:OutputFile
    abstract val libssl: RegularFileProperty

    @get:OutputFile
    abstract val libcrypto: RegularFileProperty

    @get:Input
    abstract val jobs: Property<Int>

    init {
        jobs.convention(Runtime.getRuntime().availableProcessors())
        group = "build"
        description = "Builds OpenSSL 3.0.3 static libs for the given KMP target"
    }

    @TaskAction
    fun build() {
        val src = sourceDir.get().asFile
        check(File(src, "Configure").exists()) {
            "OpenSSL source not extracted at $src (missing ./Configure). Run :extractSsl first."
        }

        val baseArgs = listOf(
            "no-shared", "no-tests", "no-ui-console", "no-asm", "no-async",
            "no-err", "no-psk", "no-srp", "no-cms", "no-ts", "enable-tls1_3",
        )
        val cfgName = configureName.get()
        val cflagsValue = cflags.get()
        val env = extraEnv.get()

        // OpenSSL's ./Configure reads CFLAGS from the environment verbatim.
        // The caller has already put the full cross toolchain flag set in
        // there (`-target`, `--sysroot`, `--gcc-toolchain`, `-isystem`,
        // `-D_GNU_SOURCE` on glibc sysroots), so Configure just forwards it.
        val fullEnv = env + ("CFLAGS" to cflagsValue)

        logger.lifecycle("=== OpenSSL $targetName: ./Configure $cfgName (CFLAGS='$cflagsValue') ===")
        runProcess(workingDir = src, args = listOf("./Configure", cfgName) + baseArgs, env = fullEnv)

        logger.lifecycle("=== OpenSSL $targetName: make -j${jobs.get()} build_libs ===")
        runProcess(workingDir = src, args = listOf("make", "-j${jobs.get()}", "build_libs"), env = fullEnv)

        val builtSsl = File(src, "libssl.a")
        val builtCrypto = File(src, "libcrypto.a")
        check(builtSsl.exists()) { "libssl.a not produced in $src for $targetName" }
        check(builtCrypto.exists()) { "libcrypto.a not produced in $src for $targetName" }

        val outDir = libssl.get().asFile.parentFile
        outDir.mkdirs()
        builtSsl.copyTo(libssl.get().asFile, overwrite = true)
        builtCrypto.copyTo(libcrypto.get().asFile, overwrite = true)

        logger.lifecycle("=== OpenSSL $targetName: ${libssl.get().asFile} + ${libcrypto.get().asFile} ===")
    }

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
            throw GradleException("Process exited with $exit: ${args.joinToString(" ")} (in $workingDir)")
        }
    }
}
