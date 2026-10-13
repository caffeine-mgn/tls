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
 * specific KMP target by running Configure + make **out-of-source**: the
 * extracted source tree ([sourceDir]) is read-only and each target gets its
 * own build directory (the parent of [libssl]) where `Configure` generates
 * the Makefile and `make` writes the objects and archives.
 *
 * Out-of-source is required for correctness: several targets are built by
 * different `buildOpenssl*` tasks, and building them in the shared source
 * tree left a foreign-arch `libssl.a` behind (e.g. an ELF archive copied
 * into the mingw klib), which then failed at link time with "unknown file
 * type". A per-target build dir makes the tasks independent and
 * parallel-safe.
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
 * Each run re-runs Configure (idempotent — overwrites Makefile +
 * configdata.pm) and `make build_libs` (only rebuilds what changed in the
 * per-target build dir). To force a clean rebuild, delete the per-target
 * build dir (the parent of [libssl]).
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

    private companion object {
        const val MAKE_ATTEMPTS = 3
    }

    @TaskAction
    fun build() {
        val src = sourceDir.get().asFile
        check(File(src, "Configure").exists()) {
            "OpenSSL source not extracted at $src (missing ./Configure). Run :extractSsl first."
        }

        val outSsl = libssl.get().asFile
        val outCrypto = libcrypto.get().asFile
        val buildDir = outSsl.parentFile
        buildDir.mkdirs()
        // Drop stale libs so a partial/failed build can't leave a foreign-arch
        // archive at the declared output path.
        outSsl.delete()
        outCrypto.delete()

        val baseArgs = listOf(
            "no-shared", "no-tests", "no-ui-console", "no-asm", "no-async",
            "no-err", "no-psk", "no-srp", "no-cms", "no-ts", "enable-tls1_3",
        )
        val cfgName = configureName.get()
        val cflagsValue = cflags.get()
        val env = extraEnv.get()

        // OpenSSL's Configure reads CFLAGS from the environment verbatim.
        // The caller has already put the full cross toolchain flag set in
        // there (`-target`, `--sysroot`, `--gcc-toolchain`, `-isystem`,
        // `-D_GNU_SOURCE` on glibc sysroots), so Configure just forwards it.
        val fullEnv = env + ("CFLAGS" to cflagsValue)
        val configure = File(src, "Configure").absolutePath

        logger.lifecycle("=== OpenSSL $targetName: $configure $cfgName (CFLAGS='$cflagsValue') in $buildDir ===")
        runProcess(workingDir = buildDir, args = listOf(configure, cfgName) + baseArgs, env = fullEnv)

        // `make -j<cores> build_libs` on OpenSSL 3.0.3 is occasionally racy:
        // the archiver can start before some object finishes compiling
        // ("<obj>.o: No such file or directory" in the `ar` line). `make` is
        // incremental, so re-running it simply finishes the missing objects.
        val makeArgs = listOf("make", "-j${jobs.get()}", "build_libs")
        var lastError: GradleException? = null
        for (attempt in 1..MAKE_ATTEMPTS) {
            try {
                logger.lifecycle("=== OpenSSL $targetName: make -j${jobs.get()} build_libs (attempt $attempt/$MAKE_ATTEMPTS) ===")
                runProcess(workingDir = buildDir, args = makeArgs, env = fullEnv)
                lastError = null
                break
            } catch (e: GradleException) {
                lastError = e
                logger.lifecycle("=== OpenSSL $targetName: make failed (attempt $attempt/$MAKE_ATTEMPTS), retrying ===")
            }
        }
        lastError?.let { throw it }

        check(outSsl.exists()) { "libssl.a not produced at $outSsl for $targetName" }
        check(outCrypto.exists()) { "libcrypto.a not produced at $outCrypto for $targetName" }

        logger.lifecycle("=== OpenSSL $targetName: $outSsl + $outCrypto ===")
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
