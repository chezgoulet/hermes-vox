# Plan — from 0.9.0 to 1.0

1.0 is a declaration, not a feature drop (D3). Six gates decide it, and this plan is
ordered by **dependency**, not by date: every sprint lists its scope and the evidence
that closes it. Nothing here carries a duration, because a duration is a wish with a
number attached.

Current state: 0.9.0 shipped. Reusable-build gate closed (byte-identical across two
clean builds). Open: F-Droid, the live-gateway suite, the behaviour matrix, no open
severe bugs, performance baseline.

Two tracks run in parallel for the whole plan, because neither can be compressed by
working harder on it:

- **F-Droid** — reviewer latency is external. Submit early, iterate in the background.
- **The truncation hunt** — needs field reports from real phones. Open the channel now
  (#202, discussion #203) and let reports accumulate while the code work proceeds.

---

## Sprint 1 — Proof, and a decision about the listening half

Nothing new is built in this sprint. It exists because five of the six gates are
*evidence* gates, and because the next sprint depends on a measurement, not on code.

**Already in hand, and not to be rebuilt:** `LiveGatewayTest.kt` is a real gated live
suite that has simply never run in CI; `StreamParityTest` plus the fence, poll-gate and
retirement tests already pin the parser; and the STT A/B harness (`android/bench`,
`tools/sttbench-stream`) is the instrument Sprint 2 needs. The work here is **wiring and
recording**, not writing test frameworks from scratch.

- [ ] **Live-gateway suite** (#167). Scripted protocol tests over a fake gateway
      (ordering, keepalive, mid-stream failure, cancel, compress chaining, auth), plus a
      nightly run against a real gateway with a small local model. The repo already has a
      gated live suite that has never run in CI; wire it before writing anything new.
- [ ] **Behaviour matrix** on the two devices we actually have (Pixel 7, Pixel 9):
      a recorded row per mode × endpoint state, not prose.
- [ ] **Performance baseline**: cold start, first token, first audio, full reply, and
      presence render, measured on the release APK and recorded. This is the number the
      1.0 gate is judged against.
- [ ] **Truncation**: hold the channel open; triage every report against the three log
      lines. Exit is not "fixed" — it is **the cause named**, or narrowed to one of the
      two candidates with the evidence that did it.
- [ ] **#191** ER init hang: one attempt to reproduce with logcat on a cold device. If it
      will not reproduce, record that and close it as unreproducible rather than leaving
      a sev/high open against the release.

**Exit:** a green live suite, a written matrix, a baseline, and the truncation either
diagnosed or reduced to a single candidate.

---

## Sprint 2 — The keystone: co-residency

The whole listening half is queued behind one measurement.

**What must come first, and why the order was wrong:** the arm is not in the app.
`nemotron` appears nowhere under `android/app/src/main` — it lives only in the bench
module (`android/bench`, merged with #177). Co-residency cannot be measured until the
app can load the arm, so **the wiring half of Sprint 3 moves ahead of this run**: catalog
entry, download, licence acceptance, a setting, and the Zipformer fallback. The run then
answers the only question wiring cannot — whether it fits beside everything else.

**What the owner's field logs already prove, and what they do not.** Every Enhanced
Realtime session is VAD + STT + TTS + presence running together, so the *configuration*
co-exists in daily use; that is not in doubt. Two things are still missing: the new arm
inside that stack, and the **numbers** — the logs record per-turn timings, not RTF, peak
RSS or memory headroom, which is what the decision turns on.

- [ ] **Co-residency run** (#179): VAD + streaming STT + TTS + presence resident in one
      live call. Record RTF, peak RSS, `MemAvailable` at worst, presence first token,
      and whether playback stutters.
- [ ] **Decide the STT default** from the numbers: Nemotron at 160 ms if RTF holds under
      1.0 with the rest resident; the Apache-2.0 Zipformer stays the low-end arm.

**Exit:** a recorded run and a written decision amending D5's gate. No adoption yet.

---

## Sprint 3 — Adopt the streaming arm

- [ ] Wire the chosen arm into the shipped path, behind a setting, with the Zipformer as
      the fallback for devices that cannot afford it.
- [ ] Model catalogue: download, licence acceptance on the NVIDIA arm, uninstall path.
- [ ] The listening half becomes continuous for the whole call (VAD + streaming ASR),
      with an explicit CPU/GPU and battery budget documented.
- [ ] Barge-in re-tuned against the new partials — the echo floor is unchanged, but the
      capture lane is not.

**Exit:** a live call that shows partial transcripts, the fallback proven by forcing it,
and a battery figure per audio-hour.

---

## Sprint 4 — Turn-taking

Only now are these buildable; they need the partials that Sprint 3 created.

- [ ] Semantic endpointing: probability plus a timeout, with an `eagerness` preset,
      replacing the hand-tuned silence constants.
- [ ] Intent-dependent re-entry delay.
- [ ] Mid-speech backchannels — presence triggering on speech onset and partials rather
      than on turn boundaries. The most human item on the list.
- [ ] Presence-line refinements: the supplied elapsed time as the one number a mid-run
      line may carry, now that the skip path logs what was refused.

**Exit:** the constants are gone, the corpus shows the improvement, and a caller
interrupting mid-sentence no longer waits for a turn boundary.

---

## Sprint 5 — The store

**Already in hand:** draft PR #154 carries the store metadata (title, short and full
description, an F-Droid readiness doc) and the unsigned-build config. It targets `main`,
has never been rebased onto 0.9, and its changelog still says 142 — so it is a starting
point, not a submission. What is genuinely missing is the recipe itself.

- [ ] **F-Droid path B** (#165): sherpa-onnx and onnxruntime built from source in the
      recipe; `Binaries:` + `AllowedAPKSigningKeys`; submission, then review cycles.
- [ ] Confirm F-Droid's rebuild reproduces our APK at the released tag. A mismatch means
      they skip that version — so the reproducible gate and this one are the same gate.

**Exit:** accepted, visible in a real F-Droid index.

---

## Sprint 6 — The declaration

- [ ] Multi-profile (#174) if it is in 1.0 rather than 1.x — client-side registry and
      switcher; discovery remains a gateway contract.
- [ ] Close every severe bug; the two accepted at 0.9.0 must be closed or re-classified
      with a reason, not carried silently.
- [ ] Re-run the whole gate set on the release candidate: reproducible, live suite,
      matrix, baseline, F-Droid.
- [ ] Cut 1.0 — same process as 0.9.0, from `main`, tagged, reproducible.

---

## Not in 1.0

Speaker ID (1.1, and it builds on the wake-word embedding front-end). Google Play,
desktop and iOS (D1). Cloud voice modes. Notification routing and the BYO push endpoint
(2.x, D12) — though the **armed session** is a 1.x item and can land during 0.9.x if the
foreground-service work is taken up.

## Open questions this plan does not answer

- **Wake word**: D6 step one is "train Hey Hermes in our own pipeline". The House has no
  ML-training GPU, so this means rented compute or a decision to defer. It is not on the
  critical path to 1.0 and should be treated as its own decision.
- **Multi-profile**: 1.0 or 1.x? It is scoped in #174 but not scheduled here.
- **Presence density default**: Conversational is right for the field test so far; one
  more long run decides whether it is right for release.
