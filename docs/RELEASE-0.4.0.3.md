# Hermes Vox 0.4.0.3 — release notes

> The reachability release. 0.4.0.2's level-only escape shipped and then never
> fired once in a whole field session. This one proves why from the log, and
> makes the escape a duty-cycle test at a bar the microphone can actually reach.
> Zero behaviour change to silenceAll, the fence, StreamRetirementState, focus,
> route, endpointing, retirement, or the 0.4.0.1 gap/skipcheck probes.

## Why — the 0.4.0.2 escape was arithmetically dead

Field log: `docs/evidence-0403-failure.log` (0.4.0.1 build, escape active).
Seven barges fired via the VAD path in 2-12ms; five attempts failed, including
the two the user called out at ~21:18:28 ("spoke over most of what you just said
and it didn't work") and ~21:21 ("tried to interrupt and it did not work").
Not one `event=barge-in` in the session came from the escape.

Two independent reasons, both provable from the log against the code:

**1. The bar sat above the signal it was meant to detect.** The escape bar was
`activeFloor * 1.6` = **0.160** at the shipped 0.10 floor. During turn 10 the
near-miss probe reports `peakRms` — the running MAX of the current above-0.07 run
— as **0.172** and **0.182** while the user was talking over the reply
(`21:20:33.611`, `21:20:40.270`, both `vad=false`). The bar was inside the top
decile of the user's own dynamic range, so the level only crossed it at crests.
Meanwhile the residual echo on this single-capture / VOICE_COMMUNICATION AEC path
measures **0.081-0.095** mid-reply with Piper chunks actively flowing
(`21:20:22.036`, `21:20:48.153`, `21:18:17.179`). The 1.6x bar was inherited from
the AEC-less *second-recorder* era documented in `BargeGate.kt` — an architecture
that no longer ships — and was never re-derived for the recorder we actually use.

**2. The accumulator could not compound.** Both accumulators reset to 0 on ANY
sub-bar read (`VoiceController.kt` 0.4.0.2 lines 487/492) at a **64ms** read
granularity (1024 shorts @ 16kHz — which is why every logged `sustainedMs` is an
exact multiple of 64). Firing therefore required **seven consecutive** reads above
0.160. All 15 near-miss lines in the session report `sustainedMs` as 0 or exactly
64 — never 128 — so the strict form was not compounding past a single frame even
at the *lower* 0.10 floor.

Honest caveat on that last number: `barge-nearmiss` is rate-limited to one line
per 2s, so each sample is biased toward the first qualifying frame after a quiet
gap, i.e. toward run *starts*. It bounds the strict accumulator's behaviour at
the sampled instants; it is not a clean duty-cycle measurement. The reachability
argument in (1) does not depend on it.

Ruled out with evidence, not assumption:
- **Mic starvation** — zero `barge-gap` lines. Dead (confirmed from 0.4.0.2).
- **Deaf `!speaking && !turnInFlight` window** — zero `barge-skipcheck` lines.
- **Per-chunk grace re-arming** — `speaking` is set true once per chunk but only
  cleared in the worker's `finally` (`VoiceController.kt:853` / `:876`), so
  `playbackSince` is seeded once per reply and the 500ms grace runs once, not per
  chunk. Not a factor.
- **`floor * 1.6` applied twice** (caller bar AND inside `decide`) — checked and
  **destroyed**: both sites computed the identical 0.160 bar from the identical
  `activeFloor`, so the second test was redundant, never doubled.

## The fix

**Leaky sustain accumulators.** `BargeGate.accumulate()` replaces reset-to-0:
`+frameMs` above the bar, `-SUSTAIN_LEAK_MS` (96ms) below it, clamped to [0, cap].
Against the drain's 64ms frames the break-even duty cycle is `96/(64+96)` = **0.6**
— above 60% of reads over the bar the accumulator charges, below it drains. Both
accumulators use it; an 800ms cap stops a long loud stretch banking credit that
outlives it. **This is the anti-self-cut defence now**: the discriminator moved
from amplitude headroom to duty cycle at the bar.

**Bar re-derived for the shipped architecture.** `LEVEL_ONLY_BOOST` 1.6 → **1.3**
(a **0.130** bar at the default floor): ~37% above the measured residual-echo band,
~24% below the measured barge band.

**Escape sustain re-based.** `barge_level_only_ms` default 400 → **200**. The unit
changed meaning — contiguous time (unreachable) became leaky net occupancy — and
200ms lines the escape up with `VAD_SUSTAIN_MS`: the same sustain, with a raised
level bar standing in for the VAD agreement the echo denies us. Slider unchanged
(0-800, step 50, 0 = off).

**VAD agreement may be recent, not same-frame.** The VAD path required `vad==true`
on the current read. The log shows agreement landing on neighbouring frames
instead (turn 3: `vad=false` at peak 0.114, `vad=true` at 0.147; turn 10:
`vad=true` at 0.135 sitting between the `vad=false` 0.172/0.182 peaks), so the
conjunction can miss by a frame. `VAD_RECENT_MS` (250ms) accepts recent agreement.
The level and sustain requirements are untouched, so this only widens *when* an
already-qualifying level is allowed to fire. Evidence here is suggestive rather
than conclusive — the probe cannot show the two conditions' exact phase — so it is
deliberately the smallest of the three changes.

**Probe blind spot closed.** `event=barge-nearmiss` now also prints
`levelSustain=` (the escape's own accumulator), `levelBar=` and `need=`, and the
line now triggers on `sustainedLevelMs > 0` too. 0.4.0.2 printed only the VAD
sustain, which is precisely why the escape's silence was invisible in the field.

## Honest risk

**The self-cut band is no longer defended by amplitude.** The 0.130 bar sits
*inside* the 0.15-0.17 band that caused the historical self-cut era. What stops
echo firing now is duty cycle: TTS echo arrives as an amplitude-modulated envelope
whose troughs fall back into the residual band, so it drains the accumulator every
trough. A **sustained, unmodulated** level in that band would fire. That is a real
regression path if a device's AEC fails to converge, on an AEC-less audio route, or
at extreme speaker volume. The test suite pins this trade explicitly rather than
hiding it: `raising_the_rms_floor_restores_a_bar_above_the_self_cut_band` asserts
that a steady 0.17 **does** fire at the shipped floor.

Mitigations, in order of bluntness:
- **Raise `barge_rms_min` 0.10 → 0.14** (Settings → Speech & Mic). That puts the
  escape bar back at 0.182, above the self-cut band, and is pinned by a test.
- **Raise `barge_level_only_ms`** toward 800 if TV/noise barges through.
- **Set `barge_level_only_ms` to 0** to disable the escape entirely and return to
  the VAD-only double gate.
- If the entity cuts itself off while nobody spoke, that is this band firing:
  report the `event=barge-in source=single-capture ... vad=false` lines together
  with the surrounding `barge-nearmiss` lines — `levelSustain=`/`levelBar=` now
  make the cause readable.

Secondary risk: the VAD recency window makes the primary path fire up to 250ms
after the VAD's last agreement. Set `barge_rms_min` higher if late fires appear.

## Spec reversals (assertions changed, not weakened)

Three existing assertions changed, each a deliberate reversal:

1. `LEVEL_ONLY_BOOST` 1.6f → 1.3f — the bar was unreachable (see Why §1).
2. `DEFAULT_LEVEL_ONLY_MS` 400L → 200L — the unit's meaning changed.
3. `level_only_escape_does_not_fire_below_the_raised_bar` asserted 0.155 could
   never fire. 0.155 is now above the bar and *must* be able to fire — it is
   inside the band the log records for a real barge. The "echo must not fire"
   guarantee that row carried was **relocated, not dropped**, to
   `echo_shaped_series_never_fires_the_escape` (+ the shallow-trough variant),
   which proves it against a modulated series rather than a single frame. The row
   still asserts a no-fire, at 0.129 (under the new bar).

## Tests

`BargeGateTest` 10 → 19 cases, all passing (91 total in the module, 0 failures).
New: the leaky-accumulator primitive (charge/leak/clamp/cap), the 0.6 break-even
duty invariant, the VAD-recency rows, and four **series** rows that replay the
real drain-loop math (64ms frames, both accumulators, `decide` per frame):

- `barge_over_tts_fires_within_400ms_even_with_vad_false` — the turn-10 shape
  (0.11-0.18 riding with brief dips across the bar, `vad=false` throughout) fires
  at 384ms.
- `barge_over_tts_could_not_fire_under_the_0402_rule` — the same series against
  0.4.0.2's rule never fires, and neither half of the change is sufficient alone.
- `echo_shaped_series_never_fires_the_escape` (+ `..._even_with_shallow_troughs`)
  — amplitude-modulated echo at 0.15-0.17 (and 0.17-0.19), deep and shallow
  troughs, 3-on/3-off bursts: all drain, none fire.
- `raising_the_rms_floor_restores_a_bar_above_the_self_cut_band` — pins both the
  stated risk and its mitigation.

## Version

- `versionCode 82` · `versionName 0.4.0.3`.

## Device test

Talk over the long replies at conversational volume and confirm the cut lands.
Then play loud media over a reply with nobody speaking and confirm no
`event=barge-in` fires. Export the log either way: the `barge-nearmiss` lines now
carry `levelSustain=`/`levelBar=`/`need=`, so a miss (or a false fire) says which
of the bar and the sustain was responsible — that is the number 0.4.0.4 needs.
