# Hermes Vox 0.9.1 — Presence

## The call
- Runs route by default
- 404 fallback, sticky
- Speech-end to first-sound measured
- 60-second turn ceiling
- Wait card, not silence
- Approvals by tap or voice
- Silence is never a yes
- Approval bound to its arguments
- Card dead on a locked phone
- One re-warm, then a working voice
- Missing voice named or refused
- Streaming TTS: bigger buffer
- Chunking at clause seams
- Chunking independent of arrival
- No stale audio after a cut
- /new mid-call fixed
- Refusal names its reason
- Dial: placed, not placed, unknown

## Profiles and sessions
- multiple profiles now supported
- Voice, model, look per profile
- Sessions: browse, adopt, resume now supported.

## Commands and settings
- Massive visual and functional upgrades to Commands
- Five selectable glyphs, Steve default
- Animation from six verbs
- Settings grouped by question
- One alpha block at the end
- Switch above the block
- Diagnostics command removed
- Native dialogs on app ground

## Look and sound
- Authored light ground
- Light-mode visual overhaul
- Look controls, live preview
- Scrollback through speech
- Picture-in-picture call
- Ambient cues

## Hearing and voice
- Multilingual Whisper models
- French voices: Siwis, Tom, UPMC
- UPMC's second speaker
- True licence labels
- Measured download sizes
- Honest language row
- System TTS fixed
- Cold-start eye earned by audio

## Reliability
- Deeper testing coverage
- Doc-truth invariants in the gate
- Skill claims anchored to symbols
- Status lines name causes

## Distribution
- sherpa-onnx from source
- Reproducible builds
- F-Droid metadata
- Per-origin mTLS

## Alpha — off until you turn it on
One switch, above the block in Settings

### Presence
- Layer, not a mode
- Selector retired
- Spoken asides
- Every line transcribed
- Backchannels
- Resume after interruption
- Overlap classified
- Continuous capture
- Semantic endpointing
- Early commit, walkable back
- Repair, never silence
- No invented numbers
- Tuning instrument and bar
- Session report per turn

### Phone and agent
- Thirteen capability groups
- Off / Ask / Allow, uniformly
- Schema from live grants
- Real MCP session, keep-alive, ping
- Tool always in hand
- One-button setup
- Setup window, no deadline
- Setup shows the agent's work
- Setup survives a dead session honestly
- Files: list, window-read, offered not uploaded
- Calendar, contacts, messages, calls
- Notifications: counts first, content by id
- Dismissal and app listing
- Media keys and now-playing
- Clipboard both ways
- Timers, alarms, DND, volume
- Notes, reported next turn
- Uninstall behind a card
- Undo and Recent actions
- Audit tells the truth
- Wake word
- Digital assistant

## Contributors
- PierreCsn — French voices and multilingual hearing
- PierreCsn — per-origin mTLS
- PierreCsn — the streaming TTS diagnosis
- blueorignal — issues that shaped the release: audio cues, quiet calls, scrollback, /new mid-call, Bluetooth mic

## The shipped artifact

- **Tag:** `v0.9.1`, cut from `main` at `585c430`
- **APK:** `hermes-vox-0.9.1.apk`, 81,344,620 bytes
- **SHA-256:** `344c94cdc17cce906c940bf7e3cc8a98e41d74c83a4445ba880fd15973ebc88f`
- **Stamp:** `versionCode 147`, `versionName 0.9.1` (read from the APK with `aapt2 dump badging`)
- **Signer:** `CN=Hermes Vox, OU=ChezGoulet, O=ChezGoulet, L=Windsor, ST=VT, C=US`, certificate
  SHA-256 `8be7feed8a6135d3d2df8fd51598517dabd4b14ea16a1a02b00c10ea4dcbbb19` — the same key
  0.9.0.2 was signed with, so it installs over an existing copy as an upgrade
- **Reproducible:** two clean checkouts on separate runners produced identical payloads
  (`release-build` run 42392078374); the verdict is that run's own report, not a claim in prose
- **Installed on a device:** over the previous build, launched, `MainActivity` resumed, nothing
  fatal in the launch window
