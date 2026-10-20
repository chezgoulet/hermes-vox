# Why barge-in worked in 0.7.x and not now

Branch `fix/barge-why-090`, on top of `fdf05c1` (the 0.9.1 barge fix that did not fix
barge). Sources:
- the 0.7.3 tree (`36418d9`, the release merge; there are no 0.7.x tags)
- the 0.8.1 tree (`9058fa8`)
- the 0.9.0 tree (`testing`)
- five field logs (L1–L5, Pixel 7, 2026-10-20)
- an offline synthesis of both on-device voices with the app's engine settings

Line numbers refer to `android/app/src/main/java/com/hermesvox/` in each tree.

## The answer

**The owner is right. Barge-in did get worse after 0.7.x, and none of the barge code
changed.** The gate, the drain, the VAD, the mic source, the noise suppressor, the
playback usage and the audio focus are the same in 0.7.3 and 0.8.1. What changed is the
reply's voice. 0.8.0 replaced Piper with **Supertonic** as the default
(`e1eb036`, 2026-09-29, "chosen by a measured bake-off"). The bake-off measured speed and
intelligibility. It did not measure loudness or what the voice leaves at the microphone.

The barge floor (0.10) and the level bar (0.130) were derived in 0.4.0.3 from a residual
echo of **0.081–0.095** at the mic, measured while Piper was speaking (`BargeGate.kt`
class doc, item 1). The floor sat just above that echo. So when the owner talked over the
reply, his voice only had to *add* to an echo that was already nearly at the floor. In
power terms, an echo of 0.088 plus a voice of 0.05 reads 0.10.

Supertonic synthesizes about **6.5 dB quieter** than Piper. The 0.9.0
`event=barge-stat` lines show what is left at the mic during playback now:

- **peak 0.000–0.008, VAD 0%**, for 110 of 121 one-second windows (L2);
- the owner's voice, while the reply plays, at 0.04–0.17;
- the same voice, while the mind is generating and nothing plays, at 0.109–0.294 (L1's
  three generation barges).

The echo bed the floor was built to clear has gone. His voice, ducked during playback,
now has to clear 0.10 on its own. The off-device replay shows the effect directly. Through
**0.7.3's own gate constants**, the same voice fires about twice as often over the
Piper-era echo as over today's. Crest-shaped speech at 0.12–0.14 fires 6 and 18 times out
of 40 over the Piper echo, and 0 out of 40 over today's.

So 0.7.3's *code*, fed today's voice, fails exactly the way 0.8.1 and 0.9.0 do. 0.7.3 *as
shipped* had Piper by default, and Piper's echo carried the gate.

## How I measured the voices

- Same text, sherpa-onnx 1.13.8.
- The app's models: `vits-piper-en_US-libritts_r-medium` and
  `sherpa-onnx-supertonic-tts-int8-2026-03-06`.
- The app's settings: Piper sid 0, noise 0.667/0.8, length 1.0; Supertonic sid 1 (the
  default F2), speed 1.0, two threads.
- Levels are RMS over 64 ms frames (the drain's read size).

| text | voice | rate | RMS (all) | RMS (frames > 0.01) | p90 frame | peak |
|---|---|---|---|---|---|---|
| 21-word reply sentence | Piper | 22.05 kHz | 0.108 | 0.119 | 0.173 | 0.697 |
| | Supertonic | 44.1 kHz | 0.057 | 0.068 | 0.101 | 0.560 |
| 17-word reply sentence | Piper | 22.05 kHz | 0.122 | 0.131 | 0.183 | 0.777 |
| | Supertonic | 44.1 kHz | 0.053 | 0.062 | 0.101 | 0.371 |
| "Okay." | Piper | 22.05 kHz | 0.152 | 0.165 | 0.242 | 0.902 |
| | Supertonic | 44.1 kHz | 0.017 | 0.032 | 0.033 | 0.216 |

Supertonic's active RMS is about half of Piper's: −6.0 dB to −6.9 dB on the sentences.

This is a host-side measurement of the synthesized samples, not a measurement in a room.
The drop at the mic (≈0.09 → ≤0.008) is much larger than 6.5 dB. My explanation, which I
have **not** proven: a quieter signal keeps the phone's small speaker in its linear range,
where the platform AEC cancels it almost completely. A louder Piper drove the speaker into
distortion that a linear canceller leaves behind. The fact the fix relies on is the field
measurement itself: today's echo at the mic is ≤0.008.

## Archaeology: the audio path, 0.7.3 → 0.8.1 → now

`git log --oneline 36418d9..9058fa8` contains 121 commits. Below is every behaviour that
touches capture, playback, or the barge decision. Verdicts:

- **preserved**: no change;
- **changed deliberately**: a change someone meant to make;
- **changed by accident**: a change to barge behaviour that nobody intended;
- **missing**: present before and gone now.

| behaviour | 0.7.3 | 0.8.1 | now (0.9.0 + this branch) | verdict |
|---|---|---|---|---|
| Capture source `VOICE_COMMUNICATION` (platform AEC/NS) | VoiceController.kt:291 | VoiceController.kt:382 | VoiceController.kt:382 | preserved |
| Extra `NoiseSuppressor` on the record session (`ns_extra`, default on) | VoiceController.kt:299–302 | :390–393 | :390–393 | preserved |
| Audio mode | never set (MODE_NORMAL) | never set | never set | preserved |
| Audio focus `USAGE_VOICE_COMMUNICATION`, `AUDIOFOCUS_GAIN` | MainActivity.kt:843–854 | :928–939 | :1074–1085 | preserved |
| Reply playback usage: `USAGE_MEDIA` unless `tts_voice_usage` (default false) | SherpaTts.kt:108–111 | :151–154 | :170–173 | preserved |
| Communication device (`setCommunicationDevice` / SCO) | none | CommRoute.kt, MainActivity.kt:444 (`7a1af4b`, #149) | CommRoute.kt, MainActivity.kt:534 | changed deliberately. On the speaker it does nothing: every log says `comm-route action=none available=TYPE_1+BUILTIN_SPEAKER` |
| **Default reply voice** | Piper if installed, else System (VoiceController.kt:191) | `ModelCatalog.defaultTts`: **Supertonic**, then Piper (VoiceController.kt:282, ModelCatalog.kt:96) | same | **changed deliberately (the voice); changed by accident (its effect on barge)** |
| TTS synthesis threads | 1 | 2 (`ff86375`) | 2 | changed deliberately; it shortens gaps between phrases and does not affect barge |
| Silero VAD gate (`SileroVadGate`, threshold 0.5) | OfflineStt.kt | byte-identical | byte-identical | preserved |
| `BargeGate.kt` (every constant, `decide`, `accumulate`) | 164 lines | **byte-identical to 0.7.3** | leak 96 → 48 (`fdf05c1`); floor/bar now parameters (this branch) | preserved through 0.8.1; changed deliberately since |
| `barge_rms_min` default 0.10, grace 500 ms, level-only 200 ms | VoiceController.kt:464–468 | :613–617 | :622–626 | preserved |
| Single-capture drain: same recorder; RMS → VAD → leaky sustains → decide | VoiceController.kt ~520–640 | ~680–810, plus carry | `BargeAudioPath` (#42, pure extraction) | preserved |
| Playback grace window | VoiceController.kt:559 | :722 | :736 | preserved |
| Barge carry: the interrupting words become the next turn | **absent** (the words were discarded, then `r.stop()` + 450 ms cooldown) | `BargeCarry` (`58abcbf`, #12) | 4 s ring (`fdf05c1`) | changed deliberately (a fix) |
| Post-turn `r.stop()` + 450 ms deaf cooldown | VoiceController.kt:650–652 | removed; mic reopens live, `EchoTailRule` (`13d78c0`) | same | changed deliberately. Not a barge factor: L2's reply was the call's first turn, so the drain read a recorder in the same state as in 0.7.3 |
| Turn-gate ceiling 60 s from gate close | VoiceController.kt:131 | :153 | from last sign of life (`fdf05c1`) | preserved through 0.8.1; changed deliberately since |
| Endpoint clock | from mic open | from mic open | from speech onset (`fdf05c1`) | preserved through 0.8.1; changed deliberately since |
| Go core (`voice/*.go`) | n/a | gateway and stream only; no audio | Kotlin `core/` | not on the audio path |

Nothing on the capture side attenuates, gates or replaces the microphone signal
differently during playback in 0.8.1 than in 0.7.3. The ducking of the owner's voice
during playback, 0.04–0.17 against 0.11–0.29 during generation, happens on the platform
side (AEC and noise suppression on the `VOICE_COMMUNICATION` path), and it happened in
0.7.3 too. In 0.7.3 it did not matter, because Piper's echo bed made up the difference.

### What the 0.7.x release notes claim

[RELEASE-0.7.0.md](RELEASE-0.7.0.md), "The barge escape", is the only barge claim in the
0.7.x notes:

> A soft, short interrupt (one word, barely over the floor) peaked in a single frame and
> was refused by both accumulators. The level-only escape now credits the run's peak; the
> net-occupancy sustain still holds, so residual echo (0.081-0.095 measured) cannot fire
> it. Peak tracking now runs in playback mode too.

That is the peak escape in `BargeGate.decide`. It is unchanged since. The note restates
the measurement everything depends on: **residual echo 0.081–0.095**. 0.7.2 and 0.7.3
make no barge claims. 0.7.2 is the aura fix and 0.7.3 is the first-run scroll fix.

## Corrections to the brief

1. **L1 does contain `event=barge-in`.** There are four: one in playback at rms 0.208,
   `vad=true` (00:15:12, after three near-misses over 15 s), and three in generation at
   0.294, 0.109 and 0.156. Playback barge did fire once in 0.9.0; generation barge works.
   The other four logs contain none.
2. **The L2 second with `vadPct=100 peak=0.074` is almost certainly the owner, not the
   echo.** The chunk playing then was logged at 10:03:57.188: 334,381 samples at 44.1 kHz,
   which is 7.58 s, playing until about 10:04:04.8, and the next chunk was logged at
   10:04:05.452. So the drop to 0.004 at 10:04:03 happens *mid-chunk*, not at a chunk
   end. Every window of pure playback in L2 reads VAD 0% and peak ≤0.008. The three
   windows with signal (10:03:59–10:04:02, 10:04:30–32, 10:04:55–56) are followed by his
   next turn: *"Barge Inn doesn't seem to work at all."*
3. **The 0.067–0.095 "residual echo band" is the Piper-era number.** The echo on today's
   path is an order of magnitude lower. The 0.9.0 near-miss probe agrees. It logs any
   read ≥ 0.07 with VAD, at most once every 2 s. Across L1's 292 s reply it fired twice.
   An echo that regularly reached 0.07 would have fired it every 2 s.

## The fix: measure the echo, don't assume it

`EchoFloor` (new) sets the barge floor during playback from the echo this line actually
produces:

- **Reference.** `SherpaTts.playbackRms()` is the reply's raw RMS over the 64 ms before
  the playback head. It reads across the previous phrase too, because the track buffer
  still holds that phrase's tail when the next phrase is handed over.
- **Learn.** On every playback read where the reply is audible (the reference was ≥ 0.03
  at any point in the last 384 ms), including the grace window, the measurement keeps the
  mic level. It ignores the VAD: Silero can mark exactly the loud echo reads as speech,
  and leaving them out under-measures the echo. Each block of 16 reads keeps its
  second-highest level. The echo level is the median of the last five blocks, or the max
  while there are fewer than three. A person talking through one or two blocks cannot
  move it.
- **Floor.** The floor is 1.5 × the echo level, clamped to
  [0.3, 1.0] × `barge_rms_min`, which is [0.030, 0.100] at the default. It is one number
  for every playback read, gaps included. The level-only escape's bar is
  max(1.3 × floor, 0.8 × `barge_rms_min`), because it ignores the VAD and must not fire on
  clatter.

Why a constant floor and not a per-read echo prediction: a per-read draft of this class
self-cut in the replay whenever the echo arrived 128 ms before the playback head said the
audio had played, at the start of a phrase after a gap. A floor set above the measured
echo cannot be misaligned in time.

What it cannot do, by construction:
- **It cannot raise the floor above `barge_rms_min`.** It only lowers it where the
  measurement says the echo is lower. A setup whose echo beats the fixed floor behaves
  exactly as before.
- **Before the first block** (about 1 s of audible reply), **without a reference** (System
  TTS, the soul's clips, generation mode) and **without VAD**, the floor is
  `barge_rms_min`, as before. Generation-time barge is untouched, and L1 shows it works.
- **Talking long enough to fill three of the five blocks** raises the measured level, and
  the floor with it, up to `barge_rms_min` at most. The failure mode is the old behaviour,
  not a self-cut.

The route-aware threshold falls out of the measurement. On a headset there is no acoustic
path, the echo measures near zero, and the floor goes to its minimum. Nothing reads the
route.

## What the replay proves (`BargeReplayTest` §4, `EchoFloorTest`)

The model:
- **Reply level at the head:** 6 s phrases with 0.7 s gaps, syllables at 4.3 Hz, and a
  128 ms word pause every 0.9 s. Peak 0.10 for Supertonic, 0.18 for Piper.
- **Echo:** the reply, delayed by −2 to +4 reads. Negative means the echo arrives *before*
  the head reports the audio. Linear, compressive (^0.7) or expansive (^1.4).
- **Silero on the echo:** never speech, speech on a third of reads, or speech on every
  loud echo read.
- **Noise bed:** 0.004, the gap level the field shows.
- **The voice:** plateau and crest envelopes at 3–6 Hz with troughs at 0.02, 10 phases
  each. VAD follows the voice, or is held false.

| case | result |
|---|---|
| Owner's voice at 0.07 / 0.08 / 0.12 / 0.17 over today's reply (Supertonic, echo ≤ 0.008, Silero silent on it as in L2) and on a headset, VAD following the voice | **40/40 fire** in every case, mean 302–404 ms from onset, worst ≤ 832 ms, onset always in the carry |
| Same inputs through the fixed floor | 0/40 at 0.07 and 0.08 (both shapes), 0/40 crest-shaped at 0.12 |
| Silero deaf (VAD false on everything): only the level escape | plateau 0.12 / 0.17 and crest 0.17: 40/40; plateau 0.08: 35/40; crest 0.12: 20/40. The fixed floor gives 0, 40, 8, 0 and 0 |
| Echo only, 120 s: Supertonic, headset, and Piper-era peaks 0.067 / 0.081 / 0.095 × 4 delays × 3 shapes × 3 VAD modes × 3 phases | **never fires** |
| Piper-era echo, 15 s stall mid-reply, then the echo arriving 128 ms early | never fires |
| Silero marks every echo read (or every loud one) as speech | never fires; the floor stays at 0.100 |
| The user talks through the first 3 s with VAD false | floor ≤ 0.100; it falls back to 0.030 once the reply has played alone for a few seconds |
| Over a Piper-era echo | fires exactly as often as the fixed floor, never less |
| 0.7.3's gate (96 ms leak), fixed floor: the same voice over the Piper echo vs over today's | ≥ 2× the fires over the Piper echo (crest 0.12/0.14: 6 and 18 vs 0; plateau 0.10–0.14: 17/30/37 vs 5/20/24) |

**The ~300 ms target is met on average, not on every read.** The VAD path needs 200 ms of
net occupancy, so at 64 ms reads the earliest possible fire is the 4th read (256 ms). The
mean is 302–404 ms. Some phases take up to 832 ms, when the onset lands on a syllable
trough. Without the VAD, the level escape alone takes 0.5–1.2 s on average.

**What the replay is not.** Envelopes, not recordings. Real Silero verdicts under echo are
not modelled; the VAD modes above bracket them. The two voices' levels are measured. The
level of the room's echo is taken from the field logs.

## What I could not test

- **No device run and no live gateway turn.** I did not install this build. The phone has
  0.9.0 vc143 on it, and without a live turn the barge path cannot be exercised.
- Whether today's 0.000–0.008 echo holds at other volumes and on other phones. The
  measurement adapts to whatever it finds, but only downward from `barge_rms_min`.
- Side speech: a TV or another person talking during playback at ≥ 0.03 with VAD true
  will now barge where it did not before. The mitigation is `barge_rms_min`, which scales
  the minimum: 0.14 gives a minimum of 0.042.
- Why the echo at the mic fell by far more than the voice's 6.5 dB. The fix does not
  depend on the answer.

## What to test on the phone

Every line below is in the exported log (`VoxLog.d`).

1. **Speaker, Supertonic, Realtime.** Ask for a long answer. After about 1 s of the first
   reply, `event=barge-stat` should show `floor=0.030` and `echo=0.00x`. Talk over it at
   your normal level from arm's length. Expect `event=barge-in … mode=playback` within
   about half a second, the audio cut, and your whole sentence as the next turn
   (`event=barge-carry seeded=true`, then a `realtime:` line with your words).
2. **Silence soak.** Let a 2-minute reply play with nobody talking. Expect no
   `barge-in`, and `floorPct=0` on every `barge-stat` line.
3. **Piper A/B.** Settings → TTS → Piper, then repeat 1 and 2. If the echo-bed account is
   right, `echo=` reads ≥ 0.067 and the floor stays at 0.100: barge behaves as in 0.9.0,
   and you are standing on the echo again. This is the experiment that confirms or
   refutes the archaeology.
4. **Headsets.** Bluetooth headphones on A2DP (phone mic) and a hands-free headset (HFP):
   `floor=0.030`, and barge fires.
5. **Enhanced Realtime.** Repeat 1. The ER cancel/hold verdict follows a barge as before.
6. **Generation-time barge.** Speak while it is thinking, before any audio. It fires as
   in L1 (floor 0.100, unchanged).
7. **Side talk.** TV on at a normal volume during a reply. If it barges, raise
   `barge_rms_min` and report the `barge-stat` lines.
