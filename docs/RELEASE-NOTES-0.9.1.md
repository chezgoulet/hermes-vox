# Hermes Vox 0.9.1 — Presence

The published release note for 0.9.1. The scope record is
[`RELEASE-0.9.1.md`](RELEASE-0.9.1.md); this file is what a reader is shown.

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
- Multiple profiles now supported
- Voice, model, look per profile
- Sessions: browse, adopt, resume now supported

## Commands and settings
- Massive visual and functional upgrades to Commands
- Five selectable glyphs, Clanker default
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
- **PierreCsn** — French voices and multilingual hearing
- **PierreCsn** — per-origin mTLS
- **PierreCsn** — the streaming TTS diagnosis
- **blueorignal** — issues that shaped the release: audio cues, quiet calls, scrollback, /new mid-call, Bluetooth mic
