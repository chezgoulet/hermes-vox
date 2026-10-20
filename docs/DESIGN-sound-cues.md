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

## Device matrix (Pixel 7 (the test device), Android 17, debug build 0.8.1)

Method: `adb shell am start -n com.hermesvox/.MainActivity --es cue <family>`, with the
phone's state set beforehand (ringer through the debug `--es ringer` harness, DND through
`cmd notification set_dnd`, media volume through the volume keys, haptics through
`settings put system haptic_feedback_enabled`). "Heard/felt" is read back from
`dumpsys audio` (the AudioTrack's usage, format and `event:started`) and
`dumpsys vibrator_manager` (the effect's duration and amplitude steps); nobody was in
the room to listen, so the evidence is the system's own record of the output.

| state | expected | result (evidence) | log line |
|---|---|---|---|
| normal ringer, media 12/25 | audio | AudioTrack, `usage=USAGE_ASSISTANCE_SONIFICATION`, 22.05 kHz mono, `event:started`, stream 12/25 | `action=audio reason=heard ringer=normal dnd=false vol=10/25` |
| vibrate ringer | haptic | 167 ms effect, `usage: TOUCH`, steps 9/55/9 ms | `action=haptic reason=vibrate-haptic ringer=vibrate` |
| silent ringer, opt-in off | silent | nothing | `action=silent reason=dnd ringer=silent dnd=true` ¹ |
| silent ringer, opt-in on | silent under DND | nothing (`sound_cues_silent=true` in prefs) | `action=silent reason=dnd preview=false` ¹ |
| DND on | silent | nothing | `action=silent reason=dnd ringer=silent vol=0/25` |
| media volume 0, haptics on | haptic | 203 ms effect, `usage: TOUCH` | `action=haptic reason=inaudible-haptic vol=0/25` |
| vibrate ringer, system haptics off | silent | nothing | `action=silent reason=vibrate haptics=false` |
| TTS speaking | silent | nothing | `action=silent reason=tts` |
| cues off, preview tapped | audio | AudioTrack started; Settings toast "Previewing the reply-ready cue" | `action=audio reason=preview preview=true` |
| cues off + TTS speaking, preview tapped | silent | nothing | `action=silent reason=tts preview=true` |
| headphones | — | **not verified**: Pixel 7 has no jack and no USB-C adapter was attached | — |
| Bluetooth | — | **not verified**: BT off, no device paired | — |

¹ On this build a SILENT ringer and DND are the same state: an app with DND access that
sets `RINGER_MODE_SILENT` turns the interruption filter to NONE, and turning DND off
restores the ringer to NORMAL. So on-device the DND rule (3, "not now") is what the
phone actually exercises; the silent-rigner rule (4) and its opt-in are covered by
`CuePolicyTest`, not reachable separately on this handset.

Real transitions, with the configured endpoint (`127.0.0.1:9`) unreachable:

```
13:34:43.454 event=cue cue=listening action=audio reason=heard       <- call start
13:34:27.785 event=cue cue=thinking  action=audio reason=heard       <- text turn
13:34:27.813 event=cue cue=error     action=audio reason=heard       <- reply-error
```

`REPLY_READY` is only played when the turn will not be voiced, so with a live gateway it
would be suppressed by design (the voice is the signal) and it was observed through the
Settings preview instead.

## What this deliberately does not do

- No audio focus request: a < 300 ms sonification is a UI sound, and ducking the
  user's music for it would be the rudeness the policy exists to avoid. (A cue is
  suppressed anyway while the being's own TTS is speaking.)
- Cues are not queued or replayed: if the policy says silent, that moment is gone. A
  cue reports the present, it is not a notification.
- No cue for "warming" or for a barge: a barge is the user's own action, and a cue
  under it would be noise on top of noise.
