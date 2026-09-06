# SPEC — Voice-call echo routing toggle + barge tuning UI (0.3.32.1)

## Why
B1 added `tts_voice_usage` (SherpaTts.kt:40-46, default true) — it routes TTS playback
as USAGE_VOICE_COMMUNICATION. On Pixel 9 this dropped output to the incall-volume bus
(field report: "extremely quiet, even at max volume"). The field log also shows barge-in
NEVER fired in the new session (`event=barge-in` zero; user resorted to the hush button):
`barge_rms_min` default 0.15 is calibrated against echo levels (0.153–0.172) which are
also the user's own speech levels — the gate is too tight. Christopher must be able to
A/B both from Settings. The August lesson (hardcoded settings are invisible) applies.

## Commits (single PR, three commits; gate: `cd android && $GR :app:testDebugUnitTest` exit 0)

### C1 — Settings UI for the voice-call echo routing toggle
1. `res/layout/activity_settings.xml`: in the Mic/Speech section, after the
   `@+id/row_mic_partial` LinearLayout (the "Streaming partial transcripts" row)
   and BEFORE `@+id/row_mic_vad`, insert a row cloned from `row_mic_ns`'s shape:
   - id `@+id/row_mic_voicecall`
   - TextView text: "Voice-call echo routing"
   - SwitchCompat id `@+id/set_tts_voice_usage`
2. `SettingsActivity.kt` `bindMicSettings()`: add
   `bindMicToggle(R.id.set_tts_voice_usage, "tts_voice_usage", true)` after the
   partial_stt line. bindMicToggle writes the SAME "hv" SharedPreferences SherpaTts
   reads — zero plumbing.
3. `restoreDefaults(GROUP_MIC)` case: add `.putBoolean("tts_voice_usage", true)`.

### C2 — Barge tuning: rms floor + grace (seek bars)
1. Layout: after the `@+id/row_mic_max` ("Max utterance length") row and BEFORE
   `@+id/row_mic_reset`, insert two rows cloned from `row_mic_max`'s seek-row shape:
   - `@+id/row_mic_barge_rms`: label "Barge sensitivity", SeekBar `@+id/set_seek_barge_rms`,
     value TextView `@+id/set_mic_barge_rms_val`
   - `@+id/row_mic_barge_grace`: label "Barge grace", SeekBar `@+id/set_seek_barge_grace`,
     value TextView `@+id/set_mic_barge_grace_val`
2. `bindMicSettings()`:
   - `bindFloatSeekBar(R.id.set_seek_barge_rms, R.id.set_mic_barge_rms_val, "barge_rms_min", 0.04f, 0.30f, 0.01f, 0.10f) { "%.2f".format(it) }`
   - `bindIntSeekBar(R.id.set_seek_barge_grace, R.id.set_mic_barge_grace_val, "barge_grace_ms", 0, 2000, 50, 500) { "${it} ms" }`
   (DEFAULT 0.10, NOT 0.15: field log proves user speech lands 0.15-0.17 — the 0.15
   floor ate it. 0.10 sits below real speech; the VAD agreement + sustain 200ms remain
   the echo defense. Grace 500 unchanged as default.)
3. `VoiceController.kt`: where `bargeRmsMin` is read, change default 0.15f→0.10f ONLY
   if it hardcodes `BargeGate.DEFAULT_RMS_MIN` — instead override the call to
   `micFloat("barge_rms_min", BargeGate.DEFAULT_RMS_MIN)` to `0.10f` as the literal
   default there, AND change `BargeGate.DEFAULT_RMS_MIN` to `0.10f` so unit tests and
   docs agree (BargeGateTest rows that assumed 0.15: keep existing rows valid by
   parameterizing the threshold in the test or updating expectations — never delete
   assertions wholesale; adjust values and re-justify each in a comment).
4. `restoreDefaults(GROUP_MIC)`: add `.putFloat("barge_rms_min", 0.10f)` and
   `.putInt("barge_grace_ms", 500)`.

### C3 — Version bump 0.3.32.1 (versionCode 74) + release notes docs/RELEASE-0.3.32.1.md
Notes: (a) NEW Settings toggle "Voice-call echo routing" (default ON; OFF restores
loud media-volume playback if the incall bus is quiet on your device — test both
positions); (b) barge sensitivity now a slider, default lowered 0.15→0.10 after the
0.3.32.0 field log proved real speech sits above 0.15; (c) device-test checklist:
1) volume ON vs OFF comparison turn, 2) talk over a long reply with toggle OFF —
expect `event=barge-in source=single-capture` ONLY on your voice, no `aec` regression
(self-cut at rms<0.15 with hardware AEC on the main capture = acceptable finding, log it),
3) restore defaults works, 4) export log.

## Verification (Torc, after loop)
- Diff review: layout ids unique; bind lines match ids exactly; prefs keys match
  SherpaTts/VoiceController read sites byte-for-byte ("tts_voice_usage",
  "barge_rms_min", "barge_grace_ms").
- testDebugUnitTest green incl. adjusted BargeGateTest.
- Build 0.3.32.1, verify artifact, GitHub release.
