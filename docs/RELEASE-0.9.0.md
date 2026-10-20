# Hermes Vox 0.9.0 — "the HTTPS release"

Version code **143** · `versionName` 0.9.0 · prepared on `testing`; the tag is cut from
`main` by Christopher, and only after the device pass in `docs/TEST-PLAN-0.9.md`.

0.9 is the last series before 1.0 (D3). This first release carries the one breaking
change of the series, the Kotlin voice core, and the barge-in work that came out of
field-testing it.

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

## Barge-in: measured against the room, not a constant

Interrupting the entity stopped working reliably somewhere between 0.7 and 0.8, and
the cause was not the barge code — that path had not changed. **The voice changed.**
0.8.0 replaced Piper with Supertonic, chosen by a bake-off that measured speed and
intelligibility but not loudness. Supertonic is about 6.5 dB quieter at the same
settings, and the barge floor had been derived in 0.4.0.3 from the echo *Piper* left
at the microphone (0.081–0.095). The floor sat just above that echo, so a person
talking over the reply only had to add a little to it. With Supertonic the echo bed
almost vanished, and the caller's own voice had to clear the floor unaided — which,
with the platform echo canceller ducking it during playback, it usually could not.

The floor is now **measured from the reply's own echo**: during playback the app
watches what its own voice actually leaves at the microphone, ignores anything the
VAD calls speech, and puts the floor 1.5× above that — clamped between 0.030 and your
configured value, and never allowed above it. A quieter voice or a headset produces
almost no echo, so the floor drops; a loud speaker raises it. Nothing is tuned to one
device or one voice.

Three related fixes came out of the same field logs:

- **The barge check no longer gives up 60 seconds into a turn.** It used to stop
  watching whether or not the reply was still speaking, so a long answer was
  uninterruptible for its last minute or two — silently. It now counts from the turn's
  last sign of life, with a ten-minute backstop.
- **The utterance clock starts when you start speaking, not when the microphone
  opened.** After fifteen seconds of an open mic, the old rule closed your sentence at
  your first breath; the fragments it produced are what the entity was apologising for.
- **The entity no longer transcribes its own greeting.** Enhanced Realtime opens the
  call with the soul speaking, which happens before any turn exists, so the microphone
  was still live and at high volume Silero called the entity's own voice yours.
  Greetings and fillers are now excluded from capture, with a short echo tail.

## The voice core is Kotlin now

The Go core, `gomobile`, the NDK pin and `mobile.aar` are gone. The turn engine, the
SSE reader and the session live in Kotlin, and streaming behaviour is pinned by the
fixture corpus in `testdata/stream/` — ten fixtures replayed through the app's own
wait→poll loop and compared against goldens. The gate is Gradle-only.

Fixtures prove the parser, not the wire, so this was field-tested before release: a
complete turn end to end against a live gateway over HTTPS, tool progress rendering,
barge-in and cancellation, and a clean session teardown. **Two timing bugs the port did
not cause but did inherit** are fixed here: the 60-second barge ceiling and the
mic-open utterance clock, both present since at least 0.8.1.

## Also new in 0.9

- **Sound cues.** The being's states have a voice of their own, generated from its
  motion vocabulary rather than taken from a library: thinking, listening, error.
  Settings → Sound cues, with a preview and a level. It respects ringer mode, DND,
  media volume and haptics, and never plays over the reply (D7).
- **The what's-new banner.** Once per upgrade, never on a fresh install, never twice
  for the same version, never on a downgrade (D10). The notes ship inside the APK,
  because a banner that needs the network to explain itself is useless on the launch
  where the network is what broke.
- **The connection test tells the truth.** It reported FAILED against a gateway that
  was working perfectly: the probe deliberately sends no `input`, the gateway answers
  400, and the app read that as a broken connection. A 400 from that probe now means
  what it is — the route exists and the key was accepted.
- **The spoken transcript keeps its place.** The reveal is paced by the playback head;
  a synthesis gap used to look like "everything has been said" and pinned the crawl to
  the end of the reply, killing the tracer. It now holds its last honest position
  through a gap.
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

**Also not yet:** the presence layer holds only about five seconds of a long mind run.
In a field test of a 76-second tool call the entity said one generated line, one canned
"let me think", and then nothing for seventy-one seconds. Holding that gap in the
entity's own voice — with a user-facing Quiet / Conversational / Chatty setting — is in
build and does not block this release.

## Verification status, stated rather than implied

**Proven on hardware.** Pixel 7 and Pixel 9, Android 17:

- cold launch, the HTTPS/cleartext pair against a reachable gateway, the connection
  test, one complete turn end to end through the Kotlin core, Whisper and Supertonic
  loading and warming, Silero VAD, cues firing with the full policy line;
- **barge-in, across four sessions and both devices** — the audio stops 7–22 ms after
  the decision, with the interrupted sentence carried into the next turn. On the Pixel 9
  the measured echo was 0.001–0.002 and the floor settled at its 0.030 minimum; on a
  louder line it settled at 0.071–0.085. Same code, two different rooms;
- Enhanced Realtime with Gemma on the GPU: greeting, seven turns, eight soul decisions,
  clean teardown — and, separately, one start that hung during initialisation on the
  same build (#191), recorded as intermittent rather than fixed;
- the headset route (confirmed on 0.8.1).

**Not yet proven on hardware:** the cue matrix across ringer/DND/haptics states, a
twenty-minute call (thermal and RSS), layout at large font, TalkBack, and presence
across a long run. `docs/TEST-PLAN-0.9.md` is the pass that settles them.

## Upgrading

Install over 0.8.1 without uninstalling: settings, downloaded models and sessions are
kept, and the model set is unchanged, so nothing re-downloads. The APK is signed with
the same key as every previous release — a debug build will not install over it, and
neither will a locally built one.
