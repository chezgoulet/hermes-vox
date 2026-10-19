# Sound cues — the being's procedural voice (#156, D7)

Sound cues are the fourth way the being is legible (voice, body, transcript, cue): a
few hundred milliseconds of tone that says *what just happened* without a glance at the
screen. They are generated on the phone, not sampled, and they are governed by one
pure policy so the phone can never be rude.

## Why procedural

D7: "procedurally generated from the being's own motion vocabulary, unique to us."
Three reasons this is not a stock-sound pack:

1. **No assets.** `CueSynth` renders every cue from a motive (notes) × a timbre
   (additive partials) at 22.05 kHz. Nothing ships in the APK, nothing is downloaded,
   nothing is copied into the cache directory. F-Droid's source-build promise stays
   true by construction.
2. **It belongs to this being.** The four families are the audible counterpart of the
   `MotionState` vocabulary the avatar already moves in, and the timbre is chosen from
   the being's idle archetype (`particles_theme`), so the sound and the body are the
   same identity.
3. **It is testable.** A cue is a pure function `(cue, timbre) -> ShortArray`. The
   envelope, the normalisation, the pitch direction, the click-freeness and the haptic
   pattern are all unit tests, not listening sessions.

## The four families

| family | motive | direction | character |
|---|---|---|---|
| `LISTENING` | E5 → A5, soft | up | the ear opens — your turn |
| `THINKING` | B3 → A3, G3 → F#3, dark, two ticks | down | work began, nothing is expected of you |
| `REPLY_READY` | G5 → C6 → E6, bright | up | the only glittering cue — "here it comes" |
| `ERROR` | A#4 → A4, D4 → C4, detuned | down | that failed, without a klaxon |

Timbre variants (`SOFT`, `BRIGHT`, `GLASS`, `DEEP`) are transposition + partial weights
only, never a different motive, so the family stays recognisable while the archetype
colours it. `DEEP` is an octave down, `GLASS` uses inharmonic bell partials.

The families are deliberately *short* (140–280 ms) and normalised to 0.85 peak: a cue
is punctuation, not an interruption.

## The policy (D7)

`CuePolicy.decide(World) -> Decision` is pure JVM. The order is the policy, and every
cue logs `event=cue ... reason=<token>` so a field report says why it was quiet:

1. **TTS speaking → silent.** Never overlap the being's voice. The reply *is* the
   signal; the voice witness is the same real-audio-on-the-track signal the avatar
   uses, with the inter-phrase hold.
2. **Toggle off → silent.** A Settings *preview* bypasses only this rule.
3. **DND → silent.** Strictly: not "quieter", but "not now".
4. **Ringer silent → silent** unless the user opts in ("Cues when the ringer is
   silent"). Opted in, the cue is heard iff media volume and the level allow it.
5. **Ringer vibrate → haptic** (or silent when system haptics are off). Vibrate mode is
   the phone saying "sound off, feel it" — the being answers in the same language.
6. **Normal ringer → audio** at the user's level, riding `STREAM_MUSIC` through
   `USAGE_ASSISTANCE_SONIFICATION` (a UI sound, never an alert). Media volume and the
   level are both masters; if either is zero the cue would be inaudible, so it falls
   back to haptics when the phone can feel — the being stays legible instead of
   silently absent.

`REPLY_READY` is only played when this turn will **not** be voiced
(`VoiceController.voiceWillSpeak()`), so it can never run into TTS.

Haptics: `VibratorManager` on API 31+, the legacy `Vibrator` below, gated by
`Settings.System.HAPTIC_FEEDBACK_ENABLED`. One pattern per family (tick / double tick /
strong two-beat / two heavier beats).

## Settings

`Settings → Spoken replies`:

- **Sound cues** (default **on** for a new install) with a **preview** button. The
  preview reports what the policy decided, because on a vibrate-only, DND or muted
  phone the honest result may be a tap or nothing.
- **Cue level** (10% steps, default 70%) — relative to media volume.
- **Cues when the ringer is silent (opt-in)** — default off, per D7.

"Restore defaults (Spoken replies)" resets all three.

## Files

- `android/app/src/main/java/com/hermesvox/CueSynth.kt` — the vocabulary + haptics (pure JVM)
- `android/app/src/main/java/com/hermesvox/CuePolicy.kt` — the policy (pure JVM)
- `android/app/src/main/java/com/hermesvox/SoundCuePlayer.kt` — AudioTrack/Vibrator transport
- `android/app/src/test/java/com/hermesvox/CuePolicyTest.kt` — the matrix
- `android/app/src/test/java/com/hermesvox/CueSynthTest.kt` — the sound design
- `android/app/src/main/res/layout/activity_settings.xml` — three rows
- `android/app/src/main/java/com/hermesvox/MainActivity.kt` — wiring to the state edges
- `android/app/src/main/java/com/hermesvox/VoiceController.kt` — `voiceWillSpeak()`

## Device matrix (Pixel 7, Android 17)

Filled in by the verification run; see the PR/report for the evidence lines. Method:
`adb shell am start -n com.hermesvox/.MainActivity --es cue <family>`, with the ringer /
DND / media-volume / haptics state set beforehand; the expectation column is
`CuePolicy.decide`, the result column is what the phone actually did, read from
`event=cue` lines and `adb shell dumpsys audio`.

| state | expected | heard/felt | log |
|---|---|---|---|
| normal ringer, media 50% | audio | | |
| vibrate ringer | haptic | | |
| silent ringer (opt-in off) | nothing | | |
| silent ringer (opt-in on) | audio | | |
| DND on | nothing | | |
| media volume 0 | haptic | | |
| system haptics off (vibrate ringer) | nothing | | |
| TTS speaking | nothing | | |
| headphones | audio in the headset | | |
| Bluetooth | audio on the speaker | | |

## What this deliberately does not do

- No audio focus request: a < 300 ms sonification is a UI sound, and ducking the
  user's music for it would be the rudeness the policy exists to avoid. (A cue is
  suppressed anyway while the being's own TTS is speaking.)
- No background cues: a cue fires only from a turn edge reached by the activity.
- No cue for "warming" or for a barge: a barge is the user's own action, and a cue
  under it would be noise on top of noise.
