plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

dependencies {
    implementation(gradleApi())
    // OpenSSLBuildTask is intentionally K/N-agnostic — it only consumes plain
    // strings (configure name, CFLAGS, env map). All Kotlin/Native knowledge
    // lives in the root build.gradle.kts, resolved through the
    // `pw.binom.kn-clang` plugin's `knClang.toolchain(target)`.
}
