package pw.binom.tls.gradle

import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

/**
 * Serializes the per-target OpenSSL configure/build steps.
 *
 * All targets configure out of the same shared `build/openssl/source` tree.
 * OpenSSL's `Configure` is not concurrency-safe against a shared source tree:
 * running two configures at once leaves a truncated/duplicated rule in the
 * generated `Makefile` (e.g. a stray `missing separator` line), which then
 * fails the build. Limiting this service to one concurrent usage makes the
 * OpenSSL tasks run one at a time.
 */
abstract class OpenSslBuildGuard : BuildService<BuildServiceParameters.None>
