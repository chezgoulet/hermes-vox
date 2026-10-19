# Hermes Vox 0.9.0 — "the HTTPS release"

Version code **143** · `versionName` 0.9.0 · prepared on `testing`; the tag is cut from
`main` by Christopher, and only after the device pass in `docs/TEST-PLAN-0.9.md`.

0.9 is the last series before 1.0 (D3). This first release carries the one breaking
change of the series and the tail of the work that had been sitting on `testing`.

## Your gateway must use HTTPS

This is the only change in 0.9 that can stop a working install from working.

Turn traffic now goes through Android's own HTTP stack, which enforces the network
security policy the app had always declared: **plain HTTP is refused for every host
except a `*.ts.net` name**. A gateway addressed as `http://192.168.x.x:8080` stops
answering; a tailnet MagicDNS name over plain HTTP still works, because the tailnet
already encrypts it.

Two ways to keep a bare-IP gateway working:

1. **Serve it over HTTPS with the tailnet's own certificate.** On the gateway host:

   ```sh
   tailscale serve --bg 8080
   ```

   then enter `https://<machine>.<tailnet>.ts.net` as the entity endpoint. The app
   trusts user-installed CAs for entity endpoints (Settings → Security → Trust device
   certificates), so no rebuild and no cleartext.
2. **Put any HTTPS terminator in front of it** — Caddy, Traefik with a real
   certificate, or Cloudflare Tunnel — and enter that HTTPS URL.

A refused endpoint fails in tens of milliseconds with copy that names this fix, and
the what's-new banner carries a button straight to Entity & Connection. Model
downloads were always HTTPS and are unaffected.

## The voice core is Kotlin now

The Go core, `gomobile`, the NDK pin and `mobile.aar` are gone. The turn engine, the
SSE reader and the session live in Kotlin, and streaming behaviour is pinned by the
fixture corpus in `testdata/stream/` — ten fixtures replayed through the app's own
wait→poll loop and compared against goldens. The gate is Gradle-only.

This is a re-implementation of the path every turn takes, so it is the part of 0.9
that deserves the least trust until it has been used: fixtures prove the parser, not
the wire.

## Also new in 0.9

- **Sound cues.** The being's states have a voice of their own, generated from its
  motion vocabulary rather than taken from a library: thinking, listening, error.
  Settings → Sound cues, with a preview and a level. It respects ringer mode, DND,
  media volume and haptics, and never plays over the reply (D7).
- **The what's-new banner.** Once per upgrade, never on a fresh install, never twice
  for the same version, never on a downgrade (D10). The notes ship inside the APK,
  because a banner that needs the network to explain itself is useless on the launch
  where the network is what broke.
- **Wake word, as a preview.** An engine seam, an enrolment screen that records the
  owner's takes, and an encrypted store — the training data, and nothing that listens.
  The Settings row says so.
- **GPL-3.0.** The app is GPL-3.0 because sherpa-onnx's TTS statically links
  espeak-ng. This was true of every release we shipped; compiling sherpa from source
  made it visible (D11).

## Not in 0.9.0, and not expected to be

Streaming STT adoption (measured — `docs/STT-AB.md` — and gated on a co-residency run,
#179), the continuous listening half, semantic endpointing, mid-speech backchannels,
a trained "Hey Hermes" model, F-Droid packaging (#165), notifications (#D12), and
speaker ID (1.1). By D3 these land during 0.9.x, each behind its own setting, or wait
for 1.0.

## Verification status, stated rather than implied

**Proven on hardware (Pixel 7, Android 17):** cold launch; the HTTPS/cleartext pair
against a gateway that is genuinely reachable — `https://mikoa.leopard-vibes.ts.net`
answers, `http://<tailnet-ip>` is refused with the expected error; the Settings
connection test; one complete turn end to end through the Kotlin core over HTTPS;
Whisper and Supertonic loading and warming; Silero VAD present; cues firing with the
full policy line. The headset route was field-confirmed on 0.8.1.

**Not yet proven on hardware:** a microphone-driven turn, barge-in, a tool-using turn,
Enhanced Realtime with Gemma on the GPU, the cue matrix across ringer/DND/haptics
states, a twenty-minute call (thermal and RSS behaviour), layout at large font, and
TalkBack. `docs/TEST-PLAN-0.9.md` is the pass that settles them.

## Upgrading

Install over 0.8.1 without uninstalling: settings, downloaded models and sessions are
kept, and the model set is unchanged, so nothing re-downloads. The APK is signed with
the same key as every previous release — a debug build will not install over it, and
neither will a locally built one.
