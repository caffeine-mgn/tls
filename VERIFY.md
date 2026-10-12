# Verify pw.binom.tls builds on this host

One-shot instructions for any host with JDK 21+. OpenSSL is cross-compiled per
Kotlin/Native target by the [`pw.binom.kn-clang`](https://github.com/caffeine-mgn/kn-clang-compiler-plugin)
plugin: it resolves the compiler, archiver, sysroot and per-target CFLAGS from
the selected Kotlin/Native distribution's `konan.properties`, materialising any
missing toolchain (clang, sysroot, Android NDK, msys2-mingw) into
`~/.konan/dependencies/` on demand. **No environment variables and no extra
package installs are required** — only the standard K/N distribution.

## macOS (Apple Silicon / Intel)

```bash
git clone https://github.com/caffeine-mgn/tls.git
cd tls

# Apple targets need Xcode (full Xcode, not just CLT, for the iOS SDKs).
xcode-select --install

# Build OpenSSL for every target this host can cross-compile.
./gradlew \
    :buildOpensslMacosArm64 :buildOpensslMacosX64 \
    :buildOpensslIosArm64 :buildOpensslIosX64 :buildOpensslIosSimulatorArm64 \
    :buildOpensslMingwX64 \
    :buildOpensslAndroidArm32 :buildOpensslAndroidArm64 \
    :buildOpensslAndroidX64 :buildOpensslAndroidX86

# Real e2e handshake test on the native Apple target
./gradlew macosArm64Test          # macosX64Test on Intel
```

On the first run the K/N plugin downloads the NDK / mingw / sysroots into
`~/.konan/dependencies/` (a few hundred MB) — this is a one-time cost.

What success looks like:

- Every `:buildOpenssl<Target>` task is `BUILD SUCCESSFUL` and leaves
  `build/openssl/<arch>/libssl.a` + `libcrypto.a` (arch-native static archives).
- `macosArm64Test` runs `TlsEndToEndTest` green — a real TLS handshake over a
  loopback socket, linking the self-built OpenSSL.

## Linux

```bash
./gradlew :buildOpensslLinuxX64 :buildOpensslLinuxArm64 \
          :buildOpensslAndroidArm32 :buildOpensslAndroidArm64 \
          :buildOpensslAndroidX64 :buildOpensslAndroidX86 \
          :buildOpensslMingwX64
./gradlew linuxX64Test
```

## Where the toolchains come from

Everything lives under `~/.konan/dependencies/` and is downloaded by the K/N
plugin on demand (no `ANDROID_NDK_HOME`, no Homebrew `mingw-w64`, no
`gcc-aarch64-*`):

| Target                    | Toolchain resolved by kn-clang                                        |
|---------------------------|-----------------------------------------------------------------------|
| `linuxX64`                | K/N LLVM clang + `x86_64-unknown-linux-gnu-gcc-8.3.0-glibc-2.19` sysroot |
| `linuxArm64`              | K/N LLVM clang + `aarch64-unknown-linux-gnu-gcc-8.3.0-glibc-2.25` sysroot |
| `androidNative*`          | K/N LLVM clang + `target-toolchain-2-linux-android_ndk` (NDK r27) + `target-sysroot-1-android_ndk` |
| `mingwX64`                | K/N LLVM clang + `msys2-mingw-w64-x86_64-2` |
| `macos*` / `ios*`         | Xcode SDKs (Apple host only)                                          |

## Building on the "wrong" host

Cross-compilation is limited by what the Kotlin/Native distribution can
materialise for the *current* host:

- Linux host: builds `linuxX64`, `linuxArm64`, `androidNative*`, `mingwX64`.
  Apple targets need a macOS host (Xcode SDKs are not downloadable).
- macOS host: builds `macos*`, `ios*`, `androidNative*`, `mingwX64`.
  Linux targets need a separate Linux cross-gcc + sysroot, which K/N does not
  provide for a macOS host.

## Common failure modes

- **`Target <x> is not supported by Kotlin/Native`** — the target's toolchain
  can't be resolved on this host family (e.g. iOS on Linux). Expected.
- **`strcasecmp_l` undeclared / glibc errors** — the host is using the wrong
  glibc headers. `knClang.toolchain()` must be used (it passes
  `--gcc-toolchain=<gcc-8>` next to `--sysroot`, so clang resolves the sysroot's
  glibc instead of the host's). Do not hand-roll `-isysroot`.
- **`__isoc23_strtol` / `__isoc23_strcasecmp` undefined at link** — clang picked
  host glibc 2.38+ headers (C23 redirects) while linking an older libc. Same
  root cause as above: use `knClang.toolchain()`.
