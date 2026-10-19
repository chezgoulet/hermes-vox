# Reproducible builds

## Status since #126 (2026-10-19)

The artifact this page used to be about is gone. Until #126 the app linked
`mobile.aar`, a gomobile binding of the Go voice core, and its `libgojni.so` was the
one native library that differed between builds of the same source (#138): Go
recorded gomobile's random work directory and the checkout path in the binary. The
fix was `-trimpath` plus binding from a canonical symlinked path
(`scripts/bind-aar.sh`), proved by `scripts/repro-check.sh` and a CI job.

#126 replaced the Go core with Kotlin (`docs/KOTLIN-CORE.md`), so there is no bind,
no NDK link, and no `libgojni.so`. `scripts/bind-aar.sh`, `scripts/repro-check.sh` and
the `reproducible` CI job were removed with it. The full investigation and the
measurements are in this file's git history (the version before #126).

## What the APK is built from now

- **Kotlin/Java**, compiled by the Gradle wrapper (Gradle 8.12.1, checksum-pinned),
  AGP 8.7.3, Kotlin 2.2.21, JDK 17.
- **Native libraries**, all prebuilt by their upstreams and packaged as-is:
  - sherpa-onnx 1.13.6 (`libsherpa-onnx-*.so`, `libonnxruntime.so`), fetched by
    `scripts/fetch-runtime.sh` and checked against a pinned SHA-256;
  - LiteRT-LM 0.16.1 (`liblitertlm_jni.so`), from Maven by version.

Nothing in the build compiles native code, so the class of nondeterminism #138
fixed (paths baked into a native binary) cannot recur from this repo.

## What has not been measured

Byte-for-byte reproducibility of the **APK** — two builds from different checkouts
compared file by file — has not been measured since the bind went away. If you need
that guarantee, build twice from two paths and compare the unsigned APK contents
(`unzip -l`, then `sha256sum` per entry). The signature block will differ by design
for anything signed with a different key.
