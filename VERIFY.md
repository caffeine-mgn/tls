# Verify pw.binom.tls builds on this host

One-shot script for any host with JDK 21+ and (optionally) cross-compile toolchains.

## macOS (Apple Silicon)

```bash
git clone https://github.com/caffeine-mgn/tls.git
cd tls

# Apple (native — macos + ios targets need no extra setup beyond Xcode CLT)
xcode-select --install

# Windows mingw target — via Homebrew
brew install mingw-w64

# Android native — install NDK 26 via Android Studio SDK Manager
#   Settings → Languages & Frameworks → Android SDK → SDK Tools → NDK (Side by side) 26.3.11579264
export ANDROID_NDK_HOME=$HOME/Library/Android/sdk/ndk/26.3.11579264

# Build OpenSSL for every target the host can cross-compile
./gradlew \
    :buildOpensslMacosArm64 :buildOpensslMacosX64 \
    :buildOpensslIosArm64 :buildOpensslIosX64 :buildOpensslIosSimulatorArm64 \
    :buildOpensslMingwX64 \
    :buildOpensslAndroidArm32 :buildOpensslAndroidArm64 \
    :buildOpensslAndroidX64 :buildOpensslAndroidX86

# Run the real e2e handshake test on the native Apple target
./gradlew macosArm64Test          # or macosX64Test on Intel
```

What success looks like:

- All `:buildOpenssl<Target>` tasks `BUILD SUCCESSFUL`. Each one downloads + extracts
  OpenSSL 3.0.3 source and produces `build/openssl/<arch>/libssl.a` + `libcrypto.a`.
  First run is slow (~30-90s per arch); subsequent runs hit Gradle build cache.
- `macosArm64Test` finishes `BUILD SUCCESSFUL` with `TlsEndToEndTest` green —
  this is the real TLS handshake over a loopback socket.

## macOS (Intel)

Same as above; replace `macosArm64Test` with `macosX64Test`.

## Linux (this dev host)

Already verified end-to-end. To re-run on the same host:

```bash
export ANDROID_NDK_HOME=/home/subochev/Android/Sdk/ndk/26.3.11579264
./gradlew :buildOpensslLinuxX64 :buildOpensslLinuxArm64 \
          :buildOpensslAndroidArm32 :buildOpensslAndroidArm64 \
          :buildOpensslAndroidX64 :buildOpensslAndroidX86
./gradlew linuxX64Test
```

## Targets that won't build on macOS

- `linuxX64`, `linuxArm64` — Kotlin/Native Linux toolchain is shipped with the K/N
  distribution, but cross-compiling OpenSSL from macOS to Linux glibc requires a
  separate sysroot + cross-gcc (not provided here). If you need this, install
  `x86_64-linux-gnu-gcc` via Homebrew and wire it into `OpenSSLBuildTask.linuxLikeEnv()`.

## Toolchain location reference

| Target                   | Required on host                                                      |
|--------------------------|-----------------------------------------------------------------------|
| `macosX64` / `macosArm64`| Xcode CLT (clang)                                                     |
| `iosX64` / `iosArm64` / `iosSimulatorArm64` | Xcode (full, not just CLT)                       |
| `mingwX64`               | Homebrew `mingw-w64` (`x86_64-w64-mingw32-gcc` on `$PATH`)            |
| `androidNative*`         | Android NDK 26+ at `$ANDROID_NDK_HOME`, `prebuilt/.../bin/clang` symlink to clang-17 must exist |
| `linuxX64`               | system gcc (or Homebrew `gcc` on macOS)                               |
| `linuxArm64`             | system aarch64 cross-gcc (or Homebrew `gcc-aarch64-linux-gnu` on macOS) |

## Common failure modes

- **`$ANDROID_NDK_ROOT is not defined`** — `OpenSSLBuildTask.androidEnv()` sets
  `ANDROID_NDK_ROOT` from `ANDROID_NDK_HOME` automatically; if you see this,
  the env var didn't reach the subprocess. Re-export `ANDROID_NDK_HOME` in the
  same shell session.
- **`no NDK ...-gcc on $PATH`** — means the auto-detect at
  `Configurations/15-android.conf:106` failed. Check that
  `$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/<host>/bin/clang` is a symlink
  to a real `clang-17+` binary.
- **`unrecognized command-line option '-mios-version-min=7.0.0'`** — iOS target
  needs Apple's clang. Configure fell back to system `cc` (gcc). On macOS this
  is automatic; on Linux it's a hard error (no iOS toolchain).
