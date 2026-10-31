# Notes — what VoiceManager teaches us

Read 2026-10-20 against `TacoTakumi/VoiceManager`, MIT. A local speech **server**: one
WebSocket carries streaming STT and streaming TTS at once, tokens in, audio out, barge-in by
cancellation. ~38k lines of Python with a heavy test suite, and a 1,400-line client
integration reference that is better than most specifications.

It is not adoptable as a dependency — Python 3.12 on a GPU host, against our on-device
Kotlin. But three of its ideas are directly useful, and one of them is the fix for a bug we
have already shipped.

## 1. The three-frame utterance, and upsert-by-index

This is the single most useful thing in the repo, and it is a **contract**, not code.

One utterance arrives as several frames sharing a `segment_index`:

| frame | `is_final` | `endpoint` | meaning |
|---|---|---|---|
| interim partial | false | false | live hypothesis, still growing |
| provisional endpoint | false | **true** | "they probably stopped" — a latency hint, revisable |
| confirmed final | **true** | false | the commit; this text will not change |

The client must treat `segment_index` as a **stable key into a revisable slot** — replace the
entry at that index — not as an append cursor. If speech resumes during the hold window the
server walks the provisional frame back and re-confirms later, under the same index.

Two consequences we have been getting wrong:

- **`endpoint` is a hint; `is_final` is a commitment.** Only act irreversibly on `is_final`.
  Acting on `endpoint` buys latency and accepts that you may have to supersede your own run.
- **Never scope turns with a `segment_index` watermark.** Because a revision reuses an index
  that is no longer above the mark, a watermark filters out the very correction it exists to
  allow. (Their docs make this argument explicitly; it is a trap we would have fallen into.)

Vox today has no revision model at all: `SpeechCursor` assumes revealed text only ever grows,
which is why a gap in TTS pinned the crawl and why a corrected transcript has nowhere to go.
Upsert-by-index is the shape that fixes it.

**Lands in:** #229 (semantic endpointing), and the reveal path.

## 2. Turns are stamped with the turn their *audio* belonged to

Every transcription frame carries `turn_id`, and — the detail that matters — it is the turn
open **when the audio was received**, not when the frame was sent. `turn_start` opens a turn
and the server acks `turn_started` with the id, because the server owns the counter.

That single rule fences out the exact failure we have fought repeatedly: an utterance that
began before a barge-in commits after it, and gets treated as the next turn's speech.

**Lands in:** #228 (continuous capture), and the barge-in path.

## 3. Cancel is fire-and-forget, with four rules

- The server acks **immediately** with exactly one `audio_end reason="cancelled"`; it cannot
  stop the synthesis task, so that task's later frames are silently dropped.
- **Exactly one `audio_end` per request id, and no PCM after it.**
- A cancelled request id is **permanently dead for the life of the connection** — a bounded
  ring of recent ids, so a frame already on the wire cannot resurrect it.
- Client side: **discard PCM for that id until its `audio_end` arrives, but stop local
  playback the instant you send the cancel.** `audio_end` is cleanup, not the stop signal —
  the ack can lag by about a second.

We hand-rolled two of these after the fact: `genCancelled` exists because our poller kept
polling a cancelled stream (~200 `no such stream` errors in one field log), and the
"1.77 s carried into the next turn" report is the client-side discard rule, missing.

**In the client (landed, #240):** `ReplyLedger` is the per-id lifecycle — `begin`/`end`
with a bounded window of ended ids, and `accepts(id)` is the one gate every frame passes
before it can be queued or played. A frame is stamped with the id of the turn that produced
it, never with "whatever reply is current", so a late frame from a cut reply is dropped
and logged (`event=tts-drop reason=reply-ended source=delta|queue|finish|begin`) instead of
starting a stream. `StreamFence.stopEpoch` is the second witness for a chunk that was
already synthesizing. The end event (`event=reply-end`) is once per id on every terminal
path: `done`, `cancelled cause=…`, or `failed cause=…`. `silenceAll` measures
`cancelToSilenceMs` from the instant the cancel is issued (entry to `silenceAll`) to the
instant the track is paused, and logs it next to `msSinceBarge`.

**Lands in:** the barge-in and stop paths, now.

## 4. `flush` and `done` are different verbs

`tts_stream` carries `flush`: synthesize what you have **now**, leave the request open.
`done`: close it and emit the trailing `audio_end`. And the counterintuitive rule that
follows — **forward every token verbatim; do not batch into sentences** — because the server
holds back its final sentence until more text arrives, so a client that pre-assembles
sentences has only moved the wait to its own side.

Our `chunkSeam` splits at sentence boundaries client-side. That is the right shape for a
one-shot TTS call and the wrong shape for a streaming one; if we ever stream tokens, the
seam logic should become a `flush` decision, not a buffer.

**Lands in:** nothing yet. Recorded because it constrains any future token streaming.

## 5. Smaller things worth copying outright

- **Sample rate is declared per reply**, never assumed: OpenVoice 22 050, VibeVoice 24 000,
  dots/voxcpm2 48 000, breeze/qwen3 24 000. We already carry per-voice rates; the principle
  ("the rate lives on the turn") is the part to keep.
- **Unknown frame types and unknown fields are ignorable** by contract. That is how their
  entire turn-taking surface landed additively. Any protocol we invent for ourselves should
  promise the same, and our parser should be built to drop what it does not know.
- **A bad token is an HTTP 403 at the handshake, not a WebSocket close code** — because auth
  runs before `accept()`. Ours is a one-shot HTTPS probe rather than a socket, but the
  lesson is identical: classify auth at the handshake, and do not expect the close-code path
  to fire for a connection that was never accepted.

## What we should not take

- **The server itself.** Speech on a GPU over the LAN is the opposite of our local-first
  design. It remains a plausible *optional* remote-speech backend for users with hardware —
  that is what #238 tracks — not a replacement for the on-device path.
- **Their engine abstraction.** `audio.cpp` sidecars exist to isolate crashes per session;
  we do not have that problem and the complexity would be dead weight on a phone.
- **Their token/voice auth model.** Per-token voices assume a server holding the keys; ours
  holds the gateway key on the device and nothing else.

## Licence

MIT. If we copy code rather than reimplement a contract, the file keeps its copyright line
and `NOTICE` gains a line crediting TacoTakumi/VoiceManager — MIT is compatible with GPL-3,
but the attribution is not optional. What is written above is a design note, not copied
code.
