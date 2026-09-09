# Hermes Vox 0.7.0 — the naturalness release

The first release of the new git flow (`testing` → nightly → `main`), and the
one where Enhanced Realtime stops being a mode that's merely wired and starts
being a mode that *sounds alive*. Everything below rode the nightly track
first — five green nightlies, field-tested on a Pixel 9 in the car and on foot.

## The naturalness ladder (ER)

The 0.5.6-era ER promise — "the soul speaks with the agent's voice" — now has
an audible implementation:

- **Silence-first (Tier 0):** the first ~4 seconds of mind-work are motion
  only. The waiting-constellation *is* the acknowledgment. The robotic
  instant-ack is gone, and with it the double-ack class.
- **Nonverbal clips (Tier 1):** 13 natural sounds (mm, mm-hm, breath, soft
  let-me-see) rendered once with the same lessac voice as replies, shipped as
  raw PCM, played on a private audio track that structurally cannot disturb
  the reply's playback. Piper never reads a two-character interjection again.
- **Sentence fillers only on the fail-soft slot** (4s+, once per mind-work
  window, monotonic — the repeating-filler bug is dead).
- **Settings → Presence voice:** Silent / Sounds / Spoken. Default Sounds.

## VOX.md authoring — working end-to-end

The authoring directive names the entity's own `vox-authoring` skill, declares
itself an authoring task, and — the diagnostic upgrade — **the failure dialog
now shows what the entity actually said**. Field-verified: after the gateway
update, the sync landed first-try (`doc=2093 result=ok`).

## Field fixes (from your drive-test log)

- **The poll-loop storm** (46,712 spins on a dead stream) — capped; a lost
  stream now aborts the turn with a spoken error inside ~20s.
- **The gateway-slow silence** — a 5s+ stall now says "still working on it —
  the connection's a little slow" instead of dying by barge into nothing.
- **Noise-hallucination transcripts** (`[SOUND]`, `(buzzing)`) are stripped
  from both STT paths before they can poison the server chain (the "replies
  with a previous message" cause).
- **Reply text in the `turn done` log line** — replay vs. drift is provable
  from a field log.

## The barge escape

A soft, short interrupt (one word, barely over the floor) peaked in a single
frame and was refused by both accumulators. The level-only escape now credits
the run's peak; the net-occupancy sustain still holds, so residual echo
(0.081-0.095 measured) cannot fire it. Peak tracking now runs in playback
mode too.

## Verbose logging rule

**Verbose file log ON = the log never truncates or prunes.** A dev session
captures everything, unambiguously — the 0.6.9 export failure (early-turn
evidence rotated out) cannot recur under the toggle. Rotation exists only for
the default mode.

## Tailscale-serve HTTPS (community contribution)

First merged external contribution: `network_security_config` now trusts
user-installed CAs (tailscale serve HTTPS works with the tailnet CA on the
phone), `*.ts.net` MagicDNS names are reachable as a tailnet-private cleartext
fallback, and the gradle wrapper jar ships in-repo (external contributors can
build from a fresh clone). README FAQ has the recipe. Thank you,
@juanmackie.

## Honest limits

- ER stays **alpha** — keyword-level classifier, no device soak yet.
- Gemma 4's voice surface is still mostly unrealized (canned fillers + clips
  carry the ladder); Gemma-rendered presence is the next arc.
- Car-noise endpointer tuning (endpoint-extended, slow STT in-cabin) is a
  known open item.

## Verification

Every change rode the nightly CI: five green nightlies, each gated on a clean
Thelio build (go vet/test → gomobile bind → assembleRelease +
testReleaseUnitTest), final gate 34 suites / 258 tests / 0 failures. APK
versionCode 112 / 0.7.0, signer CN=Hermes Vox.
