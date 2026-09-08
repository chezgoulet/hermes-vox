# Hermes Vox 0.6.1 — Enhanced Realtime, wired (alpha)

The ER (Enhanced Realtime) build: all seven runtime phases of
`docs/BUILD-ER-enhanced-realtime.md` land in one release, phase-gated and
Thelio-verified at every step. **ER is still alpha and labeled alpha** — the
wire-in is honest, the classifier is keyword-level (deterministic), and the
soul still never originates a fact.

## What ER now does (Enhanced Realtime mode only)

- **Phase 1 — voice-mode signal.** Voice turns carry the phone-call prefix
  ("respond conversationally, 2-3 sentences… answer from what you know; do
  NOT do exhaustive tool work"). Client-side, per-turn, cache-safe — the same
  live-call-local mechanism the Hermes CLI uses. Works on any stock gateway
  (BYOG: we do not patch the gateway). Fixes the field multi-turn bug (the
  agent loading 100KB of skills mid-call because nothing told it to just
  answer).
- **Phases 2+3 — the VOX.md bridge.** The gateway agent authors its own
  voice-export (Contract + Soul) over the same directive turn /compress uses;
  the app pulls it, validates it (Contract bytes identical, Soul fields
  bounded, no secrets), and mirrors it read-only. Pull-only invariant: the
  device NEVER pushes identity. Settings: "Resync VOX.md" + the first-enable
  bootstrap + honest fail states.
- **Phase 4 — the soul's presence.** VOX.md is the Gemma persona (the voice,
  never the mind). The 5-class intent classifier (backchannel | emotion |
  smalltalk | information | action): real questions and actions escalate to
  the mind ("let me think…"), smalltalk and emotion stay with the soul,
  backchannels just hold. Fillers: bounded (≤2 per 3s), fail-soft (an
  in-character lag line after 4s, never a generic "thinking…").
- **Phase 5 — the semantic barge gate.** The physical trigger always cuts
  the soul's filler; whether the MIND cancels is semantic. "Take your time"
  / "no rush" / "go on" HOLD the mind (the sacrosanct asymmetry: a missed
  cancel is cheap, a false cancel regenerates 15s of work); "stop" / "wait" /
  "actually" cancel it. Ambiguity defaults to hold.
- **Phase 6 — the priority audio arbiter.** P0 stop > P1 mind > P2 critical
  soul > P3 fillers. The mind is NEVER cut by a soul utterance; fillers never
  talk over anything. Composed on the existing cut paths (no new teardown).
- **Phase 7 — context-drift sync.** The mind sees what the soul already said
  this turn (a per-turn epilogue riding the user text, cache-safe) so it
  doesn't re-state confirmations; a vibe vector conditions its tone.
- **Phase 8 — telemetry.** Classifier decisions, barge verdicts, arbiter
  outcomes, soul first-word latency — `er:` log lines, the honest numbers.

## KEEP-list

Everything from 0.4.x-0.6.0 stands: per-conversation prompt caching (the
prefix + epilogue ride per-turn user text only), the narrow-waist core,
barge-in/reveal-freeze/escape-rule/reply-settle/crash-guard, the 20-archetype
particle being. Realtime mode is byte-identical to 0.6.0 — every ER behavior
gates on `voice_mode == enhanced`.

## Honest limits (alpha)

- The classifier is keyword-level, not model-level — deterministic and
  testable, but it will miss idioms. The telemetry counts the misses.
- The VOX.md authoring depends on the agent following the directive; a
  non-canonical Contract is rejected at the door (never mirrored).
- The barge-gate read depends on partial-STT capture during generation;
  on devices where that capture is starved, the verdict defaults to hold.
- No device soak yet — field logs drive the next tuning pass.

## Verification

Every phase: `go vet` → `go test ./voice/...` → `gomobile bind` →
`assembleRelease` + `testReleaseUnitTest` on the Thelio, independent clean
clone each time. Final: 30 suites / 240 tests / 0 failures.
