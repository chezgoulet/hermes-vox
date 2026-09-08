# Hermes Vox 0.5.4 — reliability batch part 2 + Kotlin 2.2 toolchain

## The six review findings (deepseek-v4-flash sub-agents, the mechanical half)
Built from the Opus review sprint map. Each from an isolated clone, KEEP-list verified.

- **M2** — conn-test probe no longer POSTs a real generation to the entity. The
  stream leg was `{"model":"","input":"hello","stream":true}` (a real turn with an
  empty model route) that was then abandoned. Now a stream-off, no-input probe
  (`{"model":"","stream":false}`) with disconnect — reachability + auth only.
- **M3** — ModelDownloader failure-cleanup de-shadowed. `var tmpDir` was never
  assigned (a `val tmpDir` shadowed it), so the catch/finally `deleteRecursively()`
  always no-op'd and a failed unpack leaked the staging dir. Now the outer var is
  used, and the connection disconnects on ALL paths (was success-only).
- **M4** — deleted dead `RealtimeActivity` + its manifest entry. It read a plaintext
  key from an intent extra (no GatewayKey.resolve) and was never launched — an
  exported bearer-credential trap.
- **M5** — sprite cache full `clear()` -> LRU evict-one. Overflow discarded every
  resident sprite, forcing a re-bake burst (up to 3 bitmaps/frame) under
  `visual_cycle_all`. Now Access-ordered LinkedHashMap with removeEldestEntry.
- **L1** — Conversation.History capped (historyCap=20). The legacy client-side chat
  history re-POSTed the whole array every turn (O(n²)); now bounded. Chose cap over
  delete after confirming TurnText is still referenced (mobile/session.go, game/).
- **L2** — VoxLog single BufferedWriter under the existing rotationLock instead of
  reopening the file per log line (open/write/close per line, unsynchronized across
  4 threads).

L3 (sendText turnGen) deliberately HELD — the review flagged it "consider" with an
invariant risk; not a clean mechanical fix, kept off the cheap model.

## Kotlin 2.2.21 toolchain bump (unblocks the release build)
`litertlm-android:0.16.1` ships Kotlin 2.3.0 metadata, which the app's Kotlin 1.9.24
compiler could not read (the `packageRelease` failure: "metadata is 2.3.0, expected
2.0.0"). This is a LATENT dependency/toolchain mismatch in the Enhanced Realtime
(alpha) path — litertlm is the on-device Gemma expression layer, `GemmaExpress` is
instantiated in MainActivity. Bumped the Kotlin plugin + stdlib to **2.2.21** (the
first line that reads 2.3.0 metadata), which aligns the compiler with the dependency.
This was the correct fix over excluding the dependency (which would break
MainActivity's `GemmaExpress(this)` construction).

## KEEP-list — verified
No motion-state/visual/barge/escape/reveal/crash-guard regressions. Each of the 6
fixes is a contained, per-file change with an isolated-clone diff; re-gate
`assembleRelease + testReleaseUnitTest` + `go test ./voice/...` confirms the whole
tree compiles and passes together.

## Notes
- Version 0.5.4 (versionCode 93). The 6 reliability fixes + the Kotlin 2.2.21 bump
  together. 0.5.3 (versionCode 92) remains the previous release.
- The remaining review finding L3 is held; the barge-in interrupt that was queried
  turned out to be a log-reading artifact (the user confirmed it felt natural) — not
  a defect. The visual "shapes read too samey" feedback is a separate design item.
- 0.6 release gates tracked as GitHub issues #91-100.
