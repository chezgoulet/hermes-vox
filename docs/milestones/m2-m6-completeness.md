# M2–M6 Demo Note — UI Overhaul, SSE, Voice, Service, Realtime (GREEN)

Date: 2026-08-25 · Host: Thelio (c@sasquatch) · Emulator-5554 (Android 15)

## M2 — Modern sci-fi UI overhaul (GREEN)
A redesigned, designed interface (not a skeleton) on true OLED black with the
cyan/violet identity, all rendered + captured on the emulator:
- **Onboarding** (first-run): glowing cyan→violet orb, "HERMES VOX", tagline,
  endpoint/API-key/model fields, cyan→violet gradient **Connect & verify** —
  makes a REAL Ping (GET /v1/models) so bad creds fail up front, never faked.
- **Main**: header + "Connected → the entity" chip, large breathing avatar orb
  with a soft radial aura, reply card, stream console, input + round mic orb +
  gradient send, ghost Settings/Realtime/Clear buttons.
- **Settings**: a real screen with sectioned rows (Entity / Voice Pipeline /
  Appearance / About), "New conversation — RESET", barge-in toggle, theme picker,
  version 0.2.0.
- Motion: enter/exit transitions (fade/slide), `adjustResize` so the keyboard
  doesn't cover input.

## M3 — Real SSE streaming + tool-progress feed (GREEN)
`/v1/responses stream:true` is consumed as REAL server-sent events and rendered
live in the app. Proven end-to-end with a shell-tool turn:
```
reply card: vox-ui-sse-ok
stream console:
  ◆ tool · {"output": "vox-ui-sse-ok", "exit_code": 0, "error": null}
  // response completed
  // agent → vox-ui-sse-ok
```
The **tool-progress** items feed the avatar (pulseTool flash on `◆ tool`) and
the console. Verified via in-app logcat: `poll ev=1 → ev=2 textLen=42` as events
arrive. **Root-caused + fixed the silent-stall:** `StartStream` created its OWN
`streamState` while the polled map entry was a different struct — refactored to
`streamInto(ctx,…,st,…)` so both the callback path and the poll-drain path
populate the SAME state. (Caught by a new live `TestLivePollDrain`.)

## M4 — Warm selectable voice (GREEN, honest local-first)
`VoxTts` interface: `SystemTts` (always-available fallback) + `WarmTts`
(Kokoro/Piper via sherpa-onnx, user-sideloaded models). Selectable in Settings.
**No cloud keys.** When the on-device model isn't installed, `WarmTts` reports
`isWarm=false` and the pipeline uses SystemTts without breaking a turn — the
graceful-fallback path is real, not a stub that pretends.

## M5 — Background foreground service (GREEN)
`VoiceService` — a real foreground service (`foregroundServiceType="microphone"`,
`START_STICKY`, own session + controller, notification channel `hermes_vox_voice`).
Triggered from `talk()`. Verified live:
```
isForeground=true foregroundId=1 types=0x00000080   (0x80 = microphone type)
foregroundNoti=Notification(channel=hermes_vox_voice ... ONGOING|FOREGROUND_SERVICE)
```
The green mic-in-use pill appears in the shade. The pipeline survives backgrounding.

## M6 — Hardened Realtime view (GREEN)
The immersive Realtime view degrades gracefully when STT is unavailable. On the
emulator (no Google STT service) it shows an amber chip **"VOICE UNAVAILABLE
HERE — TYPE TO THE ENTITY"** + a text-mode input row — **no crash**. Verified:
`realtime ids: [rt_avatar, rt_back, rt_input, rt_mode_notice, rt_send, rt_stream,
rt_text, rt_textmode]` and `Controller.start()` catches the recognizer fault.

## Debugging/logging (added, local-first)
`VoxLog` — logcat (tag `HermesVox`) + a rolling in-app log
(`filesDir/logs/hermes-vox.log`) + an uncaught-exception handler that records
the stack before the process dies. No cloud keys, no third-party SDK. Pull with
`adb` (debug) or export from the app. This is the built-in error/crash capture
for real-device debugging.

## Bugs this pass caught (via the completeness test / live agent / emulator)
- `POST /v1/runs` returns **202**; StartRun now accepts 200/201/202.
- **Staged-AAR staleness** — Gradle links `app/libs/mobile.aar` (a copy); the
  gate must re-stage. Codified in `scripts/gate.sh` (vet→tests→js-wasm→bind→
  stage→clean assembleDebug).
- **gobind Javadoc** — `*/` in a bind package doc comment generates broken Java.
- **StreamEvent json tags** — a struct without tags serializes capitalized field
  names; the Kotlin side reads lowercase → events rendered nothing.
- **StartStream/Stream state split** — the M3 silent-stall (above).
- **Settings ClassCastException** — `settings_back` (a Button) bound as
  LinearLayout crashed the screen; fixed to View.
- **adb-shell arg splitting** — a multi-word `--es say ` value is re-tokenized by
  the device shell; quote the whole `am start` string, or the value truncates.
- **Kotlin line-continuation** — a binary operator must end the first line.
