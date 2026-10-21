# Plan — 0.9.1, the mechanics release

0.9.1 is the release where the app stops being impressive and starts being reliable. It is judged
with Presence **off**: a call has to be fast, correct and navigable without the layer. Every
primitive the layer needs has to exist, be measured, and carry a bar — that is what makes 0.9.2's
claim possible rather than hopeful (D21).

Milestone label: `milestone/0.9.1`. Roadmap: [`../ROADMAP.md`](../ROADMAP.md).

## What is already in hand, not to be rebuilt

- **The streaming arm is wired** (#207, closed 2026-10-20): `StreamingStt.kt` and
  `OnlineNemotronStt`, a catalogue entry at the 160 ms chunk, and a Settings chooser that keeps the
  Apache-2.0 Zipformer as the fallback while the streaming model is absent, downloading, or will
  not load. The A/B that chose the arm — 11.9 % WER on accented speech against 38.8 %, at 3.1× the
  CPU and memory — is `docs/STT-AB.md` and D5.
- **The live gateway suite** (#167, closed 2026-10-21) runs in CI and fails the build if any of its
  six cases is skipped.
- **The fixture corpus** in `testdata/stream/` pins streaming behaviour across the Kotlin core, and
  the STT bench (`android/bench`, `tools/sttbench-stream`) is the instrument the measurement below
  needs.

## 1. Co-residency (#179) — the gate

One live call with VAD + the streaming arm + TTS + presence resident, on the 8 GB-class device.
Record RTF, peak RSS, worst `MemAvailable`, presence first token, whether playback stutters, and the
thermal trace over the run. Then **decide and write it down** in `DECISIONS.md` with the numbers:
the arm as default on 8 GB-class devices, the Zipformer, or the arm on request only.

Nothing downstream is measurable until this passes; a runtime that fits alone is not evidence that
it fits beside the rest.

## 2. The listening half, continuous (#228, #235, #229)

The mic stays open for the whole call, VAD and streaming recognition run continuously, and presence
can trigger on speech onset and partials rather than on turn boundaries. That is the substrate
0.9.2's behaviours are built on, and it is 0.9.1's work because a call is better with it whether or
not Presence is on.

- **#235** — the partial worker re-decodes snapshots from scratch; it must consume the new frames.
- **#229** — semantic endpointing: a probability plus a timeout, with an `eagerness` preset,
  replacing the hand-tuned silence constants. Commit early and stay walkable back.
- An explicit CPU/GPU and battery budget in the docs, because the continuous lane collides with the
  GPU-contention and thermal findings (F4, F5).

## 3. The reply lifecycle under interruption (#240, #202)

- **#240** — one end event per request id, and a PCM frame that can be attributed to the reply it
  belongs to, so audio already in flight cannot play into the next turn.
- **#202** — truncation: hold the field channel open and triage every report against the three log
  lines. The exit is **the cause named**, or the field narrowed to a single candidate with the
  evidence that did it. Not "fixed" by assertion.

## 4. Profiles (#174)

A registry of saved connections and a switcher: endpoint, key, agent name, session scope. Client-side
only — `GET /v1/models` returns a single entity name, not a profile list, so discovery from the
gateway is a separate Hermes-side contract. **Switching ends any live call**; one line at a time is
an invariant, not a limitation to be engineered around.

## 5. The commands surface (#226, #227, #252)

- **#226** — a themed sub-UI instead of the stock list.
- **#227** — command animations from a six-verb grammar rather than per-command art.
- **#252** — the open design question, settled rather than deferred: does the app keep full command
  parity in-app, or lean into the phone-call identity and make hanging up and calling back the
  normal way to start a new session? The bug in #252 is fixed and released; what remains is the
  conversation, and it decides the shape of the two items above.

## 6. First run (#230)

Install to first call without help: model download, the one licence acknowledgement, permissions,
gateway connection, and a successful call. Onboarding is not decoration — it is the difference
between an install and a user.

## 7. Look, and the instruments (#225, #209, #208, F4, F5, F8)

- **#225** — fine-grained particle controls with a live preview.
- **#209 / #208** — the performance baseline and the behaviour matrix, recorded per release on both
  devices; these are standing requirements, and 0.9.1 is where they get written down again.
- **F4 / F5** — GPU contention measured idle versus during a render, and a thirty-minute soak
  watching RSS after the express engine is released on teardown.
- **F8** — a fixed ten-utterance verdict script, so "this build is better" stops being an
  impression.

## Exit gates for the cut

- a stranger installs, onboards, places a call, switches profile and reads the commands screen
  without help;
- speech-end to first audio holds D17's budget on the release APK;
- the streaming arm is co-resident, or the default is the fallback — with the numbers that decided
  it written into `DECISIONS.md`;
- no truncation report without a named cause;
- matrix and baseline recorded on both devices;
- the standing cut checklist passes: live suite green, reproducible build, no open severe bug
  without a written reason.

Cut it the way 0.9.0 was cut: bump on `testing` first, notes in `docs/RELEASE-0.9.1.md`, banner
entry, merge to `main`, tag, reproducible build, release, site dispatch. `docs/RELEASE-PROCESS.md`
carries the process and its gotchas.

## Not in 0.9.1, and where each went

- **Presence as the product**: the mode selector, the settings split, asides, backchannels, resume,
  overlap classification, the render budget, the tuning bar — **0.9.2**.
- **Continuity, the household, the store rail** — **1.0**.
- **Entity calls, remote control of the interface, wake word** — unnumbered and community-led
  (#268).
