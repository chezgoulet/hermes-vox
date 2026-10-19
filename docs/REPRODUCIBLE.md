# Reproducible `mobile.aar` (hermes-vox #138)

`jni/armeabi-v7a/libgojni.so` differed between two builds of the same Go source
while the other ten `.so` files in the APK were byte-identical. This documents
what was measured, the cause, and the fix that is now enforced by the build
scripts and CI.

## TL;DR

Two independent sources of nondeterminism, both in the bind, not in the app:

1. **`-trimpath` was missing.** gomobile builds in a fresh
   `$TMPDIR/gomobile-work-XXXXXXXX` directory, and Go recorded that directory
   (plus the checkout's absolute path) in `.debug_*`, `.gopclntab`, `.symtab`
   and `.rodata`. **Every invocation differed, even from the same checkout.**
2. **Go build info records the module's local-replace directory.** gomobile
   generates a temp module containing
   `replace github.com/chezgoulet/hermes-vox => <absolute checkout dir>`. That
   path lands in `.go.buildinfo`, is mirrored in `.rodata`, and `-trimpath`
   does **not** rewrite module replace paths. Two checkouts at different paths
   therefore differ by exactly the path bytes — and by the build-id notes
   derived from them.

Fix: bind with `-trimpath` **and** from a canonical absolute path
(`scripts/bind-aar.sh`). Both are required. `scripts/repro-check.sh` proves the
pair and CI fails if either regresses.

## Reproduction (before the fix)

Identical source, two copies, identical flags, no `-trimpath`:

```sh
rsync -a --exclude .git --exclude .tools /home/c/vox-g5/ /tmp/bind-a/
rsync -a --exclude .git --exclude .tools /home/c/vox-g5/ /tmp/bind-b/
cd /tmp/bind-a && gomobile bind -target android -androidapi 23 \
  -javapkg com.hermesvox -o mobile.aar github.com/chezgoulet/hermes-vox/mobile
# ...same in /tmp/bind-b
unzip -o mobile.aar 'jni/armeabi-v7a/libgojni.so' -d x
sha256sum x/jni/armeabi-v7a/libgojni.so
```

Measured (gomobile `v0.0.0-20260820040257-d11f821a26a6`, Go 1.26.4, NDK 25.2.9519653):

| artifact | arm32 size | sha256 |
|---|---|---|
| `/tmp/bind-a`, run 1 | 9,110,932 | `e5e61b6fd88908e2612eb1add2e13bd0b0d04366f75e6ecc40a71ae4d192ca25` |
| `/tmp/bind-b`, run 1 | 9,110,916 | `5f856a8c4635ec15132df4e886580435a78ba121e8d1e2311ad894d74fd1d2ab` |
| `/tmp/bind-a`, run 2 (same dir) | 9,110,948 | `8b4a7cd2ddb61add15e174e95b71c2176f66d9b043619f57a1ac0b031e8ad788` |

The third row is the important one: **the same checkout, rebuilt, also differs.**
The build is not reproducible per-machine, let alone across machines.

## Section-level diff (arm32, A vs B)

Section-by-section comparison (sha256 over each section's bytes):

```
DIFF .debug_info   889598 vs 889587
DIFF .debug_line   475991 vs 475993
DIFF .debug_str      4855 vs   4846
DIFF .go.buildinfo    528 bytes
DIFF .gopclntab   2132431 bytes (same size, different content)
DIFF .note.gnu.build-id
DIFF .note.go.buildid
DIFF .rodata       132552 bytes (same size, different content)
```

`.text` and `.data` were identical in all four architectures: code generation
was deterministic; only path metadata differed. `go version -m` on the `.so`
showed `dep github.com/chezgoulet/hermes-vox … => /tmp/bind-a (devel)`, and
`strings` showed `/tmp/bind-a/mobile/session.go` etc. — i.e. absolute paths from
both the checkout and gomobile's work dir.

## With `-trimpath` only

`-trimpath` removes the source-file paths (the random work dir and the checkout
path) from DWARF, `.gopclntab`, `.symtab` and the matching `.rodata` strings.
Two runs from the same checkout became byte-identical (arm32: 0 of 46 sections
differing). Across two different checkouts the only differing payload bytes were
the module replace path in build info and its copy in `.rodata`:

```
.go.buildinfo  offset 184: 'a' (bind-a) vs 'b' (bind-b)   <- the replace dir
.rodata        offset 127205: same byte, second copy
.note.gnu.build-id / .note.go.buildid: derived from the above
```

That byte is `replace … => /tmp/bind-a` vs `=> /tmp/bind-b`: the local-replace
directory in Go build info. There is no `go build` flag that trims it
(`-trimpath` only rewrites file paths; `-buildvcs=false` is unrelated), and
binary-patching it would change what ships.

## Fix

`scripts/bind-aar.sh` does two things:

1. adds `-trimpath` (accepted by `gomobile bind` — it registers the standard Go
   build flags);
2. symlinks the checkout to a **fixed** path (`/tmp/hermes-vox-build`,
   overridable with `HERMES_VOX_BIND_ROOT`) and `cd -L`s into it before binding,
   so the directory Go records in build info is constant. `go`'s `os.Getwd`
   returns `$PWD` when it names the current directory, so this works without
   copying the tree.

No linker overrides were needed: Go's `.note.go.buildid` and lld's
`.note.gnu.build-id` are content hashes, so they became stable once the content
did. (This is the opposite of Phonon's NDK link, where `--build-id` had to be
overridden because the build ID was the *only* nondeterminism.)

Nothing is stripped: the AAR still carries DWARF and symbols.

## Proof (after the fix)

`scripts/repro-check.sh` copies the tree to two paths of deliberately different
lengths, binds each with `scripts/bind-aar.sh`, and compares. Three separate
paths (the two temp copies plus the real worktree) produce the same AAR:

```
arm64-v8a    9161616  70670dfaeab5ba1d584487df840716ec1fc7ffc318ea81bf2b7238a037732657
armeabi-v7a  9054596  a11623c818b36ddf53c9c5923f1dad7e1ccb37eb8678986b185774087250c895
x86          12206812 d487d0fc629ba87fdf4b2e853e8091690075a3b6455e3161e3c7d2b53ab9d1ec
x86_64       9886680  6373b302ae1e6cfa2fc95c04cd109880e85381c5e25e08b173109a503456cebf
mobile.aar sha256 5b7d2b1ebf950f28c1439fe51d6fc296e8e7e7d1b2c8c47923b2152dbcd44f28
REPRO-OK
```

The whole AAR is byte-identical, not just `libgojni.so` (gomobile already pins
zip timestamps to 1980-01-01, and `classes.jar` was stable too).

## About the reported "3 MB / 26%"

The reproducible defect above accounts for **tens of bytes**, not megabytes.
The measured magnitude of a strip, on the same arm32 bind, is:

| arm32 build (same bind root) | size |
|---|---|
| `-trimpath` (what ships) | 9,054,644 |
| `-trimpath -ldflags="-s -w"` | 6,443,248 |

That is 2,611,396 bytes = 28.8% — the same order as the reported 3 MB / 26%.
So a delta that large is the fingerprint of one artifact being stripped (or
built by a different pipeline) rather than of path metadata. Compare **AARs**
(unstripped), not release APKs, where AGP may strip native debug symbols during
packaging. `scripts/repro-check.sh` compares the AAR, so it pins the whole
artifact.

## What is still required for bit-identical builds

- Same Go toolchain (`go1.26.4` via `GOTOOLCHAIN`), same gomobile pin
  (`v0.0.0-20260820040257-d11f821a26a6`), same NDK (`25.2.9519653`). A different
  compiler changes codegen and therefore every hash.
- Same `HERMES_VOX_BIND_ROOT` if overridden: the path is embedded in build info,
  so a different value yields a different (but still reproducible) binary.
- No source changes between the two builds, obviously.
- `GOCACHE`/`GOMODCACHE` locations do not matter (content-addressed).

## CI

`.github/workflows/ci.yml` has a `reproducible` job:

```yaml
- name: Two binds from different paths must be byte-identical
  run: bash scripts/repro-check.sh
```

It needs no secrets and fails on any path-, timestamp- or ordering-dependent
regression. `scripts/gate.sh` and both release workflows bind through
`scripts/bind-aar.sh`, so the shipped artifact and the checked artifact are
built the same way.
