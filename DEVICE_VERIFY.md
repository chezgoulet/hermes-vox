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
  [Releases](https://github.com/chezgoulet/hermes-vox/releases) (~96 MB through 0.8.1). The
  Kotlin core (#126) removes the gomobile payload: measured 40.3 MB smaller on the debug
  build (four ABIs), and roughly 18 MB on the release (two ABIs — an estimate until a
  release APK is measured). What remains is the on-device speech runtime. Nightly
  pre-releases come from `testing`.
- **Your own build** — `bash scripts/gate.sh`, then
  `adb install -r android/app/build/outputs/apk/debug/hermes-vox-*.apk`.

## Upgrading to 0.9 — HTTPS for entity endpoints

0.9 moves the turn traffic onto Android's own HTTP stack, which enforces the
network security policy the app has always declared: **plain HTTP is refused for
every host except a tailnet MagicDNS name**. This is deliberate, and it is the
one breaking change in 0.9.

If your gateway was `http://192.168.x.x:8080` (or any bare IP), you have two ways
to keep it working:

1. **Serve it over HTTPS with the tailnet's own certificate** — on the gateway
   host:

   ```sh
   tailscale serve --bg 8080
   ```

   then enter `https://<machine>.<tailnet>.ts.net` as the entity endpoint. The
   certificate is issued by the tailnet CA; the app trusts user-installed CAs for
   entity endpoints (Settings → Security → Trust device certificates), so no
   rebuild and no cleartext.
2. **Put any HTTPS terminator in front of it** — Caddy, Traefik with a real
   certificate, or Cloudflare Tunnel — and enter that HTTPS URL.

A plain-HTTP gateway now fails with an error that says exactly this, including
the `tailscale serve` command. Model downloads were always HTTPS and are
unaffected.

## First run (onboarding)

A true first run opens on a **HOW THIS WORKS** step, not a form: it says what the
being is, compares the voice modes (Realtime hands-free vs Enhanced Realtime
alpha), and notes that Vox is hands-free everywhere — there is no push-to-talk
button to hold. **GOT IT — CONNECT** continues. **SKIP FOR NOW** explains what it
skips and drops to the main screen with nothing configured, which is the state the
"voice model not installed" banner exists for. A returning install with an endpoint
already stored goes straight to the form below.

The form has four fields:

1. **Entity endpoint** — your gateway, e.g. `https://<machine>.<tailnet>.ts.net`.
2. **API key** — the gateway's `API_SERVER_KEY` (Keystore-encrypted, never sent
   anywhere but the gateway). The connector does a real ping, never a fake success.
3. **Agent name** — `hermes-agent`, or your Hermes profile name on a non-default
   profile. The agent's name shows centre-top once connected.
4. **Entity scope** *(optional)* — the `X-Hermes-Session-Key`, shaped
   `agent:vox:tablet:name` (validated before connect). Blank is a valid answer and
   leaves the gateway's per-transcript default; setting it gives this install its
   own memory, which is what keeps two people on one gateway apart.

Then **CONNECT & VERIFY**. **I ALREADY HAVE A SESSION** is the path for a device
that has connected before.

## Download the models (on-device)

**Settings → Voice models → Open the model store.** Downloads run in the background with a progress
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

**Cold launch first.** Force-stop the app, open it, and leave it alone: the being appears
and the screen stays usable. It must **not** sit on "Preparing your voice…" with no call
running — that splash belongs to a voice line opening, and one that never clears also
covers the endpoint error underneath it. Tap the call button and the splash may appear for
a second or two while the models warm, then the line opens and it goes.

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
6. **Voice CPU threads** — Settings → Transcription (STT) → "Voice CPU threads". `Auto` derives Whisper
   = half the cores (2–4) and the voice = 2. The load logs prove what ran:
   `OfflineWhisperStt loaded: … threads=N cores=M` and `SherpaTts loaded: … threads=N`.
   The change lands on the next voice-model load.
7. **Keep-awake** — the screen stays on during a call and times out normally after it
   (Settings → Appearance & Layout).
8. **A headset becomes the route (#149)** — pair a Bluetooth headset, open a call, and read
   the log: `event=comm-route action=set ok=true type=BT_SCO` (or `BLE_HEADSET`) listing the
   devices it could see, and `event=comm-route action=cleared` when you hang up. Talk with
   the phone's own mic covered — what Hermes reports has to come from the headset. A USB or
   wired headset should take the same route.
   Two honest limits, both field-visible rather than hidden: the route follows the
   `VOICE_COMMUNICATION` capture source, so with Settings → Mic & Barge-in → hardware AEC/NS **off** the
   app records a raw phone mic and stays on the phone; and that path is the platform's
   communication route, which **Android 12+** selects via `setCommunicationDevice()`. Below
   Android 12 the app starts the legacy SCO link, which the platform confirms later — the
   line rebuilds when the SCO device appears, so the first turn there is the one to doubt.
   **Field-confirmed on 0.8.1** with a Bluetooth headset (maintainer, 2026-10-19): the
   headset became the route and carried the call. Keep the check in the pass — the 0.9
   core changed underneath it — but it is no longer an unproven claim.

## Troubleshooting

- **"Can't reach the gateway"** — check the network/tailnet and the endpoint;
  `/v1/models` must answer. Plain `http://` is refused except for `*.ts.net` names.
- **"Preparing your voice…" stays up** — a required model is missing or still
  downloading (Settings → Voice models).
- **A model download stalls** — it resumes by itself when the network returns; the
  notification has Pause and Resume.
- **No natural voice** — Supertonic isn't installed, so the system TTS is speaking.
- **A headset is attached but the phone's own mic is used** — read `event=comm-route` at line
  open. `action=none` alongside the device list means the platform offered nothing routable
  (a headset connected for media only is not a microphone), and `action=set ok=false` means
  the route was refused. Check that Android's own dialer uses the headset mic — if it does
  not, the pairing is the problem, not the app. With hardware AEC/NS off, capture is a raw
  phone mic by design (verify step 8).
