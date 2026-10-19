# Hermes Vox — sideload & on-device verify (the "make it work on the phone" guide)

The APK is a thin voice client whose mind is your Hermes agent. The phone must be able
to reach **your Hermes gateway** (tailnet or LAN, over HTTPS — see the README's
troubleshooting) and, once, the model hosts for the in-app downloads. **Realtime works
on-device** with the three required models; the on-device **Gemma model** is the
optional Enhanced Realtime (alpha) layer.

## Install

- **Obtainium** — add `https://github.com/chezgoulet/hermes-vox` as a GitHub source; it
  installs and updates the signed release APK.
- **Release APK** — `hermes-vox-<version>.apk` from
  [Releases](https://github.com/chezgoulet/hermes-vox/releases) (~96 MB; it bundles the
  on-device speech native libraries). Nightly pre-releases come from `testing`.
- **Your own build** — `bash scripts/gate.sh`, then
  `adb install -r android/app/build/outputs/apk/debug/hermes-vox-*.apk`.

## First run (onboarding)

1. **Entity endpoint** — your gateway, e.g. `https://<machine>.<tailnet>.ts.net`.
2. **API key** — the gateway's `API_SERVER_KEY` (Keystore-encrypted, never committed).
   The connector does a real ping (never a fake success).
3. **Model** — `hermes-agent`, or your Hermes profile name on a non-default profile.
   The agent's name shows centre-top once connected.

## Download the models (on-device)

**Settings → Models → Voice models.** Downloads run in the background with a progress
notification, pause and resume, wait out network loss, and are sha256-verified before
install. Required (about 290 MB):

- **Silero VAD** — hears when you start and stop speaking (barge-in).
- **Supertonic** — the recommended on-device voice (ten speakers, 44.1 kHz).
- **Whisper base.en** — on-device speech-to-text (int8).

Optional: Whisper tiny/small, **Parakeet-TDT 0.6B** (most accurate, 482 MB), **Piper**
(a lighter voice), and **Gemma 4 E2B (presence)** for Enhanced Realtime (2.6 GB).

## Which mode does what

- **Realtime** — a hands-free open line: talk, the being listens (VAD), barge-in to
  interrupt, Hermes answers, Supertonic speaks. There is no push-to-talk.
- **Enhanced Realtime (alpha)** — the same, plus the on-device Gemma 4 E2B soul: it
  greets you, speaks the first beat of each turn while Hermes works, and answers small
  talk itself. Without the presence model the call still opens and says what is missing,
  with one tap to the download.

## Verify on the device (the emulator is function-only; real mic and GPU here)

1. **Voice turn** — tap the call button, talk. The being turns to its thinking shape,
   Hermes answers, Supertonic speaks, and the reply scrolls locked to the voice. A dim
   "heard" line shows what was sent.
2. **Barge-in** — talk over a reply. It stops, and your words become the next turn
   (log: `event=barge-carry`).
3. **The being** — reacts to real tool calls: a command → the terminal, web → the radar,
   memory → the constellation, a long wait → the hourglass. It should hold 60 fps.
4. **Gemma (Enhanced Realtime) on the GPU** — download Gemma 4 E2B, switch the mode, and
   **read the log line `GemmaExpress loaded: … backend=gpu`.** `backend=cpu` means the
   accelerator did not come up: the app still works (the CPU fallback is deliberate),
   but the fast path is not live. The Pixel 9's Tensor NPU is not usable (no model is
   compiled for it), and the x86_64 emulator cannot load the model at all.
5. **The soul's numbers** — `express-probe … warm=`, `soul(beat= … mind-skip=)`,
   `hears=true`, `event=soul-heard ms=`.
6. **Voice CPU threads** — Settings → STT → "Voice CPU threads". `Auto` derives Whisper
   = half the cores (2–4) and the voice = 2. The load logs prove what ran:
   `OfflineWhisperStt loaded: … threads=N cores=M` and `SherpaTts loaded: … threads=N`.
   The change lands on the next voice-model load.
7. **Keep-awake** — the screen stays on during a call and times out normally after it
   (Settings → Appearance & Presence).

## Troubleshooting

- **"Can't reach the gateway"** — check the network/tailnet and the endpoint;
  `/v1/models` must answer. Plain `http://` is refused except for `*.ts.net` names.
- **"Preparing your voice…" stays up** — a required model is missing or still
  downloading (Settings → Models).
- **A model download stalls** — it resumes by itself when the network returns; the
  notification has Pause and Resume.
- **No natural voice** — Supertonic isn't installed, so the system TTS is speaking.
