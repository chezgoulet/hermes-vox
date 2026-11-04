# Presence review — Hermes Vox

Reviewed: `chezgoulet/hermes-vox` branch `testing` at `a63e85d` (2026-11-04, includes #394 handoff receipt and #406/D29), cloned fresh to `/tmp/vox-review-src/repo` because neither local checkout had it. Upstream gateway: `~/.hermes/hermes-agent` at `f0d94882e8`. Read-only. No builds, no device, no tests run. Every line reference below is to that tree.

Legend: **Verified** means I read the code path end to end. **Suspected** means the code allows it but I have no log or device run to prove it; each one says what would confirm it.

---

## 0. Summary

`blueorignal`'s "faster, but sometimes no response" is **not one bug**. It comes from four code paths, and **only Presence has them**. Realtime has none of the four, which is why it is "slower, but reliable". In order of likely weight:

1. **The mind is told to skip.** When Gemma's reply has no `<<ESCALATE>>` token, the turn counts as *answered by the soul*. A line like "Ooh, let me look that up for you" counts the same way. The mind is then told *"the voice already answered the caller; if you have nothing substantive to add, reply with exactly `<<SKIP>>`"*. A fast, literal model like Qwen3-30B-A3B does exactly that. The caller hears a promise and then nothing. *Verified as a path. The rate is unmeasured.*
2. **The skip can be offered when the soul's line was never played.** The turn is marked `answered` before playback is attempted. Several later guards can drop the line silently: the P3-vs-P3 arbiter reject, `speaking`, `firstTextLatch`, `streaming-reply-live`, and no-engine. The mind is still told the voice answered, so the result is total silence. The receipt logs this as `ok=true` plus `er-swallow mind=skipped`, which the docs define as "not a failure". *Verified.*
3. **Presence throws away what the caller says while the mind is working.** During generation, any speech that does not contain one of 15 "barge" phrases ("stop", "wait", "actually"…) gets the verdict `HOLD_MIND`. The words are deliberately not carried into a turn (`seeded=false reason=hold`). After a hold the gate is open again, so the *next* utterance hits `turnInFlight` and is dropped as `suppressed-inflight`. A re-ask, a correction or a follow-up during a 20-second web search never reaches the mind. *Verified.*
4. **Presence voice turns tell the mind not to use tools.** Every voice turn carries a prefix ending *"Answer from what you know; do NOT do exhaustive tool work … unless the caller explicitly asks."* Realtime sends the bare text. "Current price of a second-hand 4090" is exactly the kind of question that prefix steers to a stale answer or a refusal. *Verified as a path. Its effect on the reporter's model is suspected.*

The handoff receipt (#394) **cannot prove** the reporter's failure, and **nothing acts on it**:
- it treats "a stream id came back" as proof the turn got through;
- it never sees path 3;
- it cannot tell path 2 (the caller heard nothing) from path 1 (the caller heard the soul);
- the tuning-bar script does not read it.

The biggest gains in conversation quality are:
- make the soul a **companion, not a gatekeeper**;
- **never discard the caller's words**;
- **talk to the mind through the API's ephemeral `instructions`** rather than by rewriting the user's words.

All three mostly delete code.

---

## 1. The experience as it is

### 1.1 What "a good conversation" means, in numbers

D17/D18 already set the cadence target. The rest below are what blueorignal's account implies, made measurable.

| # | Metric | Target | Why |
|---|---|---|---|
| E1 | caller stops speaking → **first audible sound of any kind** (beat, cue or reply) | p50 ≤ 700 ms, p95 ≤ 1.4 s (D18) | the moment the caller decides whether they were heard |
| E2 | caller stops speaking → **first audible words of the mind's answer** | p50 ≤ 2.5 s on a fast mind; must not grow with how long the caller spoke | blueorignal: "replies come back fast enough that the conversation flows" |
| E3 | reply ends → mic open and ready (the gap before the next turn) | ≤ 300 ms, with no cue overlapping speech | turn-yield |
| E4 | longest stretch of dead air inside a turn (no sound while the mind works) | ≤ 4 s, then something informative | "holds the line while the mind works" |
| E5 | **turns lost**: the caller spoke words and heard neither an answer nor an "I missed that" | **0** | the reporter's failure |
| E6 | turns answered twice | ≤ 1 % | double-talk |
| E7 | soul answers ÷ voice turns, and the **override rate**: soul answered, then the mind said something substantive anyway | override ≤ 10 % | if the mind keeps overriding, the soul is answering the wrong turns |

### 1.2 What we have, measured or estimated

There are **no field numbers in the repo** for E1–E7: no session log and no `event=lat … metric=speech-to-audio` sample is checked in. What exists:

- **Bench** (`docs/PRESENCE-BENCH.md`): Gemma on GPU gives first token in 0.29 s and a 12-token line in about 1.1 s. CPU is 4–5× slower.
- **Field, 2026-10-20** (`docs/PRESENCE-LATE-DIAGNOSIS.md`): 17 `er-soul-drop reason=late`, meaning turn renders that lost to the mind's first token.
- **Design doc**: the mind's first token lands in about 1.8 s on a healthy gateway; the soul's render takes about 2.5 s.
- **Cost** (`docs/PRESENCE-COST.md`): the resident model is about **2.6 GB PSS at idle**, and a cold load takes 5.6 s (27.5 s for the first warm call after launch).

**Estimated E1/E2 for an escalated Presence voice turn**, built up from the code (GPU phone, healthy gateway):

| Stage | Source | ms |
|---|---|---|
| end-of-turn silence | `vad_silence_ms` default 800 (`VoiceController.kt:810`); early start at 600 | 600–800 |
| speech-to-text decode | offline or remote STT | 200–600 |
| **mind submit held for the soul's decision** | `SoulGate.waitMs`: render p50 + 200, default 700, cap 900 (`SoulGate.kt:19-21`, `VoiceController.kt:2159`) | **700–900** |
| gateway first text | design doc | ~1,800 |
| first TTS chunk ready | sentence end, or 500 ms flush (`VoiceController.kt:3726`) | 0–500 |
| first chunk synthesis | whole-chunk synth before the first write (`SherpaTts.streamChunk`) | 300–800 |
| **E2: mind's first words** | | **≈ 3.6–5.4 s** |
| **E1: soul beat** (if it lands) | endpoint + STT + full render (not streamed, `GemmaExpress.kt:628`) + beat synth | **≈ 1.8–2.6 s** |

So E1 is about **3× the D18 target** even when the beat works. On every escalated turn, the soul also *adds* 700–900 ms to the mind's answer. That is the turn type where blueorignal said speed mattered most.

**Answer vs escalate split (E7)** cannot be computed. The counters exist (`ErTelemetry.soulDecision`, `mindSkipped`), and the per-turn lines exist (`event=er-soul … decision=`, `er-handoff`, `er-swallow`, `er-mind-skip`). Nothing aggregates them into a rate.

**What is needed to get real numbers**, with no new code beyond one script change:
1. One 20-minute Presence session, with logging and transcripts on, on a GPU phone. Include about 10 factual or tool questions ("price of…", "weather in…"), about 5 smalltalk turns, 3 re-asks spoken while the mind is working, and 2 "yes" confirmations.
2. The same script with Presence off. That gives the A/B blueorignal ran by feel.
3. Extend `scripts/presence-session-report.py` with rows for `er-handoff ok=false`, `er-swallow`, `er-mind-skip`, `barge-carry seeded=false reason=hold`, `event=lat metric=speech-to-audio`, and the soul decision mix. **Today it reads none of them** (`grep -c 'er-handoff\|er-swallow' scripts/presence-session-report.py` = 0).
4. Add one metric that does not exist: **speech-end → first audible anything (E1)**. Today `speech-to-audio` measures only the mind's reply (`VoiceController.kt:2712-2715`), and the beat's timing is measured from `mindStartedAt`, not from speech end.

---

## 2. Findings, ranked by what the user would feel

### F1 — "Answer-then-skip" turns a soul guess into total silence (Critical)
`ErSoulTurn.kt:563`, `VoiceController.kt:2063`, `:2168-2172`, `ErDrift.kt:47-52`, `SoulGate.kt` (`MindSkip`)

**Now:**
- `parse()` returns `Spoken` for **any** render without the token. A 2B model that "helpfully" replies *"Sure, let me check current prices for you"* has, as far as the client is concerned, *answered*.
- The mind is then told the voice already answered and invited to reply `<<SKIP>>`. A fast instruction-literal MoE skips, and the caller hears nothing more.
- Since D29 there is no numbers rule on any lane. The soul's `Spoken` turn answer also gets no length cut (only the claim guard in `speakGlue`). So *"Used 4090s go for about twelve hundred these days"* is spoken, and the mind is invited to stay silent about a made-up price.

**Should:** a soul guess must never stop the mind from answering a substantive question.

**Smallest change:** stop offering `<<SKIP>>`. Pass `soulAnswered=false` always, so the epilogue becomes "do not repeat or contradict the soul's lines; answer the question itself". On real smalltalk the mind will then reply as well. Accept that, or keep SKIP **only** for the greeting turn. Cost: a few lines. What the caller feels: occasionally two lines on "how are you" (soul then mind), and **never** a swallowed question. See also §5.1.

### F2 — The soul is recorded as "answered" before its line is played (Critical)
`VoiceController.kt:2060-2066` sets `answered=true`. Then:
- `:2072` drops on `soulSpokeThisTurn || firstTextLatch || speaking`.
- `speakGlue` drops on claim (`:3085`), repeat (`:3096`), `!shouldSpeak` (`:3102`), `speaking` (`:3103`) and `streaming-reply-live` (`:3113`).
- The arbiter **rejects P3 over P3** (`ErArbiter.kt:58`) whenever any earlier aside, hold line or narration is still playing (`:3127`).
- `canDeliverSoulLine` (`:498`) checks `glueBlocked()`, which does not include `glueSpeaking`, so it misses the arbiter case.

**Now:** the mind is told "the voice answered", but the voice did not, so the caller gets silence. The receipt says `ok=true`, and `er-swallow mind=skipped` is described in `docs/PRESENCE.md` as "not a failure".

**Should:** "answered" means *played*.

**Smallest change:** set `answered` only from the TTS start callback. Or, simpler, with F1 applied the mind is never told to skip, and this whole class disappears. If SKIP is kept for anything, check `glueSpeaking` in `canDeliverSoulLine` and make the soul's turn answer priority P2 (it should pre-empt a leftover filler).

*Suspected and worth confirming:* a `stopTts()` that cuts a glue whose completion callback never fires leaves `glueSpeaking` stuck `true`. Every later P3 line is then rejected until a mind reply resets it. Confirm with `grep 'er-arbiter current=P3_FILLER .*reject'` and no `er-glue` played in between.

### F3 — Presence discards what the caller says while the mind works (Critical)
`VoiceController.kt:3273-3283` (HOLD), `:1432` (`BargeCarry.shouldSeed(..., held)` → no carry), `:1991-1996` (`suppressed-inflight`)

**Now:**
- During generation, a barge is transcribed and classified by `ErIntent.isGenuineBarge`: 15 substrings.
- Everything else is `HOLD_MIND`. The words go to `erPresence.onUserUtterance`, which only restarts the filler window, and are then discarded.
- The gate is released, so the caller's **next** sentence becomes a turn that hits `turnInFlight` and is dropped as `suppressed-inflight`.
- While the soul's own line is playing, frames are skipped (`:1370`, the echo fix). So even "stop" can arrive as an empty segment, which becomes `said=null` and therefore HOLD.
- In Realtime the same speech cancels and carries into the next turn.

**Should:** the caller's words are never thrown away. Backchannels ("mm-hm", "take your time") hold. Anything with content is either queued for after the current reply or cancels and merges with the turn in progress.

**Smallest change:** in `HOLD_MIND`, when `said` is not a backchannel (`ErIntent.classify(said).route == ACK_AND_YIELD`), treat it as a barge-carry (`seed`). Or queue it as `pendingTurn` and submit it when the current turn settles. Replace the `suppressed-inflight` drop with a one-slot queue. Cost: about 30 lines. What the caller feels: a re-ask works, like in Realtime.

### F4 — Presence voice turns tell the mind not to use tools (High)
`core/ResponsesClient.kt:124-127`, used only when `voiceTurn = fromVoice && presenceActive` (`VoiceController.kt:1998`, `:2181`).

**Now:** *"Answer from what you know; do NOT do exhaustive tool work or re-audit your own docs/state unless the caller explicitly asks."* The same prefix also says "the app adds its own spoken acknowledgments like 'let me think'", which since 0.6.7 is mostly untrue (the ack is silence-first, `ErPresence.kt:114-118`). It tells the mind to "greet the caller naturally first if they greet you", while the soul has already greeted.

The prefix is concatenated into the **user's message**, so it is:
- stored in the agent's session history every turn;
- replayed through `previous_response_id`;
- visible to memory and session search;
- about 100 tokens per turn of context growth.

**Should:** the mind keeps its full abilities on voice. The voice register ("speak plainly, 2–4 sentences, no markdown") goes in the request's **ephemeral `instructions`**, which the gateway supports on `/v1/responses` and `/v1/runs`. It maps to `ephemeral_system_prompt` (`hermes-agent/gateway/platforms/api_server_response_admissions.py:186-216`) and is not written into the transcript.

**Smallest change:**
- delete the anti-tool sentence;
- move the prefix and the `[soul-sync: …]` epilogue into `"instructions"` in `buildBody` (`ResponsesClient.kt:49`);
- keep the user's words byte-exact.

Cost: small. Risk: the gateway inherits stored `instructions` on chained turns when none are sent (`api_server_response_admissions.py:197-198`), so a typed turn after a voice turn must send `"instructions": ""` (or the typed register) explicitly. What the caller feels: price, weather and news questions get looked up, as in Realtime. The agent's session history reads like a conversation, not a protocol.

### F5 — Every confirmation gets a canned non-sequitur (High, and constant)
`ErPresence.kt:105-110`

**Now:** any *new turn* whose text is in the backchannel list gets `"okay — take the time you need"` spoken by the phone. The list includes "yes", "yeah", "sure", "okay", "right", "go ahead" and "go on". So:

> *"Want me to write those points to a markdown file?"*
> *"Yes."*
> *"okay — take the time you need"*

blueorignal's best moment ("write the points to an MD file") gets this line. The list was built for the patient caller *during* the mind's work, but it is applied at turn start. The soul is also not consulted (`VoiceController.kt:2038` requires `ACK_AND_YIELD`), so there is no beat either.

**Should:** a "yes" at turn start is an answer and gets a beat ("On it—"), or silence followed by the mind.

**Smallest change:** only speak the hold line when a mind turn is already in flight. At turn start, route backchannel-class text through the soul like any other turn. Cost: about 5 lines.

### F6 — The soul's decision waits for the whole render, and the mind waits for the soul (High, latency)
`GemmaExpress.kt:588-633` (collects to `onDone`, `maxOutputToken = 256`), `VoiceController.kt:2159-2160`

**Now:** the decision (token, or not) is known by the first token or two (~300 ms on GPU). The client waits for the whole output, up to 256 tokens and a 20 s timeout. Meanwhile the mind's submit is held for up to 900 ms. A rambling `Spoken` turn costs the full decode.

**Should:**
- submit the mind **immediately** on every voice turn (with F1 it needs nothing from the soul);
- parse the soul's stream incrementally: on the token, stop waiting; on a beat, stop decoding at the first sentence end or 7 words (`conv.cancelProcess()`).

**Smallest change:** `waitMs = 0` (one line), plus a streaming early-stop in `send()` (about 20 lines). What the caller feels: about **0.7–0.9 s off every substantive answer**, and the beat about 0.3–0.6 s sooner.

### F7 — The soul never hears the mind (High, quality)
`GemmaExpress.kt:465-524`

**Now:** the soul's rolling conversation contains its directives and its own lines. It **never** contains what the mind said. "Thanks, that's really helpful" or "and the second point?" is judged by a soul that does not know what was helpful or what the points were. That is the "would a person feel understood" failure in its purest form: the voice that answers smalltalk has missed the conversation.

**Should:** after each mind reply, append a compact note to the soul conversation, for example `(The mind told the caller: "<first ~200 chars>")`, as a no-reply turn. Or fold it into the next directive's moment line.

Cost: about 200 tokens per turn of soul KV, already budgeted by `SoulBudget` rotation, and a few ms of prefill, done off the hot path right after the reply.

### F8 — The mind's own progress narration is thrown away; the soul invents one instead (Medium-high)
`VoiceController.kt:2474-2479`

**Now:** the gateway streams `phase=commentary` / `message.interim` text, which is the mind saying in its own words what it is doing ("Let me search for recent listings"). Vox logs it to the dev console. Meanwhile a 2B renders guesses about what the mind is doing, from a tool name and 60 characters of args (`:2444`).

**Should:** commentary is the best working line there is: grounded, in the agent's voice, free.

**Smallest change:** when commentary arrives during the working window, pass it to `speakGlue` (source `mind-commentary`, trimmed by the same `fitToBound` and claim guard). Failing that, feed it to the soul's working directive as `toolContext`.

### F9 — Errors and empty replies are silent in a car (Medium-high)
`VoiceController.kt:2333-2336`, `MainActivity.kt:2137`

**Now:**
- An empty mind reply that was not a sanctioned skip ends with `onState("idle")`: no sound, no words. For example, a run that ends after tool calls, or an answer that went out only as commentary.
- Failures play a 2-tone `Cue.ERROR` and set a pill nobody driving can read.

**Should:** in a voice call, every turn ends audibly. "Sorry — I didn't get an answer back on that. Want me to try again?" is about 1 s of speech and is the repair blueorignal asked for.

**Smallest change:** one `speakGlue(..., critical=true)` with a fixed line on `empty-reply` (when not skipped), `no-stream`, `suppressed-inflight` and `stream-lost`. Or let the soul render it.

### F10 — The first TTS audio waits for a whole synthesised sentence (Medium, latency)
`SherpaTts.kt:403-411`, `VoiceController.kt:2779-2802`

**Now:** chunks are full sentences, or a 500 ms flush, and each is synthesised whole before the first write. A 20-word first sentence costs its full synthesis before any sound.

**Should:** make the first chunk of a reply short: cut at the first clause boundary (`,` `;` `—`) after 4 or more words, or about 40 characters. Later chunks can stay sentence-sized for prosody. *Suspected* 200–500 ms off E2. Confirm with `first-text` vs `first-audio` p50 in one session.

### F11 — Smaller quality items
- **The beat trails off with "..."** (`ErSoulTurn.kt:520`) on every opener, so the same prosody repeats each turn. Vary it, or drop it when the opener ends in a word that already trails.
- **The soul-sync epilogue truncates each soul line to 60 characters** (`ErDrift.kt:41`). The mind may be told half of what the caller heard.
- **`ErIntent.BARGE_PATTERNS` uses `contains`** (`ErIntent.kt`). "I **actually** wanted to add…" and "…**instead** of…" read as a cancel. This is the safe direction, but it costs the in-flight answer.
- **ASR: correct or confirm?** Neither. Confirmation costs a turn, and silent correction by a 2B is a lie risk. Instead, when the validator or STT confidence is low, prepend `(heard, uncertain): ` to the user text so the *mind* can ask "did you say X?" when it matters. Cost: tiny.

---

## 3. The escalation question — blueorignal's lost requests

### 3.1 The path, end to end
1. **Endpoint and STT** → `runStreamedTurn(text, gen, fromVoice=true)` (`VoiceController.kt:1989`).
2. If a turn is in flight, the new one is **dropped**: `er-handoff ok=false reason=suppressed-inflight` (`:1991`).
3. `ErIntent.classify`: backchannel → canned hold line, and **no soul decision** (F5). Otherwise the soul render starts (`:2038`).
4. Soul callback: `Spoken` → `answered=true`, then the line is *posted*, with several silent drop points (F2). `Beat` → spoken. `Escalate` or `Nothing` → silence.
5. Submit waits ≤ 900 ms for the decision (`:2159`). The epilogue tells the mind "the voice already answered … `<<SKIP>>`" if `answered` (`:2168-2172`). The text is wrapped in the anti-tool voice prefix (`:2181`, F4).
6. A stream id comes back → `er-handoff ok=true reason=voice-turn` (`:2185-2188`).
7. During generation, the caller's further speech is classified, and anything non-barge is **discarded** (F3). No receipt line exists for it.
8. Reply: a `<<SKIP>>` prefix is filtered; an empty final text gives `er-mind-skip`, then **silent** `empty-reply` (`:2298-2336`). Any other empty text is also silent (F9).
9. `finally`: `er-swallow` if `answered` and (`!submitted` or skipped) (`:2389-2405`).

### 3.2 Which failure is it?

| Hypothesis | Mechanism | Presence-only? | Receipt shows it? | Fits the report? |
|---|---|---|---|---|
| **Classification error** | soul emits a line without the token for a factual question → mind told to skip (F1) | yes | `soul=answer ok=true` + `er-swallow mind=skipped`, filed as "not a failure" | **Yes**: "faster" (the soul spoke instantly), then "no response" |
| **Phantom answer** | soul marked answered but its line dropped → mind skips (F2) | yes | identical to the row above; **indistinguishable** | yes, total silence |
| **Swallowed re-ask** | speech during generation → HOLD → discarded; next utterance → `suppressed-inflight` (F3) | yes | first: **nothing**; second: `ok=false` | **Yes**: "if I ask again…" during a long lookup |
| **Steered answer** | anti-tool prefix → stale or declining answer (F4) | yes | `ok=true`, normal | explains "acknowledge that it didn't process the original query": the mind did not look it up |
| **Silent empty reply** | run ends with no final text (F9) | no, but more likely after the prefix discourages a full tool loop | `ok=true` then `turn outcome=empty-reply` | partly |
| **Transport drop** | submit throws, no stream id | no | `ok=false reason=no-stream` | unlikely: Realtime is reliable on the same gateway |

**Verdict:** this is **mostly a classification and contract error, compounded by swallowed turns.** It is not transport. The pieces:
- The soul decides by *omission*: no token means "mine".
- The client converts that guess into an instruction for the mind to stay silent.
- Presence's barge semantics discard exactly the re-asks a confused caller makes.

The reporter's own suggestion, *"a heuristic where the local model checks whether a query was successfully passed on"*, is half right. The check has to be **in the client**, on what the caller actually *heard*, not on whether a stream id came back.

### 3.3 Does #394 make it provable? Does anything act on it?

**Partly provable; nothing acts.**
- `ok=true` means "the gateway returned a stream id" (`:2185`). It does not mean the mind answered, or that the caller heard anything.
- Path F3 never reaches `runStreamedTurn`, so it produces **no receipt at all**. The only witness is `barge-carry seeded=false reason=hold`, and nothing counts it.
- `er-swallow mind=skipped` cannot tell "the caller heard the soul, and the mind rightly stayed quiet" (fine) from "the caller heard nothing" (F2). The doc instructs readers to treat both as not-a-failure.
- **Consumers:** none. No code reads the receipt, no UI surfaces it, and `presence-session-report.py` does not parse it.

**To make it provable** (small):
1. Add `heard=soul|mind|both|none` to the end-of-turn line, from what actually played. That means a `soulPlayed` flag set in the `speakGlue` TTS-start path, plus `firstAudioPushed`. `heard=none` with words spoken is the one number that matters (E5).
2. Log `event=er-handoff ok=false reason=held-discarded` on HOLD with content.
3. Add rows for `heard=none`, `er-swallow` and `reason=hold` to the tuning bar.

**To make it act:** on `heard=none`, speak the F9 repair line, or automatically resubmit without the skip offer if under ~10 s. The receipt then stops being a log line and becomes the recovery mechanism.

---

## 4. Performance, ranked by user-visible effect

1. **SoulGate holds every escalated submit for 700–900 ms** (`SoulGate.kt:19-21`, `VoiceController.kt:2159`). It is the largest single latency item the app controls. Fix: F6.
2. **The soul render is not streamed and not stopped early** (`GemmaExpress.kt:588-633`, `maxOutputToken=256`, 20 s timeout). The beat lands at full-render time (~1.1 s for 12 tokens on GPU, ~2.9 s on CPU) instead of first-sentence time. And because `VoxExpress` allows one render at a time, a rambling render delays the next aside. Fix: incremental parse plus `cancelProcess()` at the first sentence end or 7 words.
3. **First TTS chunk** (F10): an estimated 200–500 ms on E2.
4. **Resident model: 2.6 GB PSS at idle** (`docs/PRESENCE-COST.md`). The user feels it as other apps (nav, music) being evicted on 8 GB phones, plus battery drain, which blueorignal asked about directly. Load at call start costs 5.6 s. The middle path: load on *call placement*, and let the greeting be a recorded clip (or silence) while it loads. The "idle-open" unload already exists; make it the default once outside a call.
5. **The crawl redraws forever.** `CrawlView.onDraw` ends with an unconditional `postInvalidateOnAnimation()` (`CrawlView.kt:189`, also `:134-135` for an *empty* document). That is 60–120 Hz redraws while the screen is on, even when nothing changes. Each frame re-measures split lines (`measureText`) and walks every layout line (it only skips lines above the top, not below the bottom). `refreshDocument()` rebuilds the whole `StaticLayout` (history + live, up to the 4,000-character cap) on **every delta**. Fix: invalidate only while `spoken` advances, a return animation runs, or a delta arrives; rebuild the layout at most per frame. *Suspected* measurable battery gain on a long drive with the screen on. Confirm with `dumpsys gfxinfo` frame counts on an idle transcript.
6. **The stream poll path is O(n²) in reply length** (`core/Stream.kt:275-280`, `:681-864`):
   - every poll serialises the **full** transcript twice (`text` and `transcript`);
   - every *event* in the batch carries the full accumulated `text`;
   - the controller then parses that with `org.json` and runs `MindSkip.strip` and `terminal.assembled` on the full text each poll (`VoiceController.kt:2260-2265`).

   For a 2,000-character reply in about 500 deltas, that is roughly 1–2 MB of JSON encoded and parsed per turn on the poll thread, plus GC churn while TTS synthesises. *Suspected* tens of ms of CPU per turn, and jitter rather than latency. Fix: carry `text` only on the done poll, or carry a length/offset.
7. **Thread churn:** `VoxExpress.launch` creates a new `Thread` per render (`VoxExpress.kt:126`); `SherpaTts` creates a teardown thread per stop. Small, but these are on the barge path. Use one single-thread executor each.
8. **The presence tick** (`ErPresence.kt:152-238`) posts to main every 1 s for the whole call, allocating `Out` objects and taking several locks. It is negligible; listed only because it runs for hours.

*Not found:* the network transport is SSE-push with a 100 ms idle floor (#39), which is good. The being renderer is off the main thread with GPU back-pressure, which is good.

---

## 5. The three changes that would most improve the conversation

### 5.1 The soul becomes a companion, not a gatekeeper
- Every voice turn goes to the mind **immediately** (`waitMs=0`), with no skip offer.
- The soul's job on a turn is the **beat**: two to six words, started the moment it is decoded.
- Its full answers are kept for the greeting and, optionally, farewells, where there is no mind turn to compete with.
- Feed the mind's replies back into the soul's context (F7).

| | |
|---|---|
| **Cost** | net deletion: `MindSkip` use, `SoulGate` wait, the `answered` bookkeeping, half of `ErHandoff.swallow`; plus ~40 lines for streamed parse and early stop. 1–2 days with tests. |
| **What the caller feels** | every question answered by the agent; about 0.8 s faster on every substantive turn; an instant, varied opener that sounds like *their* agent; smalltalk answered by a mind that remembers the conversation. |
| **Risk** | smalltalk gets beat + mind line instead of one soul line, which costs the mind a turn of tokens. Mitigation: the voice register in `instructions` asks for one sentence on smalltalk. This retires the D16-era claim that the soul "answers the short turns". The owner should decide that explicitly. Evidence for the change is §3. |

### 5.2 No caller words are ever lost, and every turn ends audibly
- HOLD with content queues or merges instead of discarding (F3).
- `suppressed-inflight` queues.
- `heard=none` triggers a spoken repair (F9, §3.3).
- Backchannel words at turn start are answers, not "take your time" (F5).

| | |
|---|---|
| **Cost** | ~60 lines, plus tests on `BargeCarry`/`ErBargeGate` and a session-report row. |
| **What the caller feels** | re-asking works; "yes" is heard as yes; the phone never just goes quiet. |
| **Risk** | queued turns could pile up in a noisy car. Cap at one pending turn, newest wins. |

### 5.3 Talk to the mind through the API, not by rewriting the user's words
- Voice register and soul-sync go in `instructions` (ephemeral, not persisted).
- Delete "do NOT do exhaustive tool work".
- Send `model_options.reasoning` (`{"effort":"low"}`, or `enabled:false`) on voice turns, as a setting defaulting to *low*. The gateway supports it per request (`api_server.py:1839-1858`; docs `api-server.md` "Per-request model selection"). It is exactly blueorignal's discovery — "a zippier model beats a smarter one by voice" — without changing models.
- Speak the mind's `commentary` as the working line (F8).

| | |
|---|---|
| **Cost** | about half a day. `buildBody` and `runBody` gain two fields; the controller passes the voice flag. |
| **What the caller feels** | lookups happen; thinking-heavy models answer seconds sooner by voice; the waiting lines are the agent's own words about what it is actually doing. |
| **Risk** | `instructions` inheritance on chained turns (send an explicit value every turn); providers that ignore `reasoning` just behave as today. |

---

## 6. What we should stop doing

1. **Stop offering the mind `<<SKIP>>`.**
   - It is the mechanism that turns a 2B guess into silence.
   - It exists to avoid a double answer on smalltalk, which is a small cost.
   - The receipt was built to measure it and then labels its failure "not a failure".
   - Retire `MindSkip` from the turn path.
2. **Stop holding the mind's submit for the soul** (`SoulGate`). It was built so the mind could be *told* about a soul answer. Without (1) it buys nothing and costs ~0.8 s per answer.
3. **Stop discarding non-backchannel speech on `HOLD_MIND`.** The semantic barge was meant to protect a long answer from "mm-hm". In practice it protects it from the caller.
4. **Stop the canned `"okay — take the time you need"` at turn start.** It answers "yes" with a non-sequitur.
5. **Stop putting protocol text in the user's message.** The voice prefix and `[soul-sync: …]` pollute the agent's own memory of the conversation, and the anti-tool clause cripples Presence relative to Realtime. Use `instructions`.
6. **Stop treating `ok=true` (a stream id) as "the turn got through".** Measure what the caller heard. Until then, the receipt reassures more than it proves.
7. **Stop spending guard effort on bounds no one has hit.** Bracket refusal, `MAX_OPENER_CHARS`, the spacing rail on non-turn renders, and a 1,000-character render cap on a lane that is cut to 7 words are all labelled `CITATION: none` in the code. They are harmless but cost reading time in every review. Retire them when the field shows none, as the code's own comments already say. The **false-action-claim guard stays**: it is cheap and asymmetric.
8. **Stop documenting line numbers in prose.** `docs/PRESENCE.md` cites `VoiceController.kt:2102` for the empty-reply branch, which is now `:2333`, and `:1837` for the turn lane. The doc-claims gate (#412) checks claims, not anchors. Cite function names.
9. **Reconsider keeping the model resident for the life of the process.** 2.6 GB idle PSS is the battery and eviction cost blueorignal asked about. The first-run hardware check *advises*, but the shipped default still keeps the model loaded. Load it per call. Keep it resident only while a call is live and for a few minutes after (D27 already says one minute after hangup; make that the only residency).

---

### Unverified items and how to confirm them
- **F1 and F2 rates:** one session following §1.2, then `grep -E 'er-soul kind=turn decision=answer|er-swallow|er-mind-skip|er-arbiter .*reject'`.
- **F3:** `grep 'barge-carry .*reason=hold'` and `er-handoff .*suppressed-inflight` in the same session. Expect them on every re-ask spoken during a lookup.
- **F4:** ask the same price question with Presence on and off, and compare `◆ tool:` lines. Expect none with Presence on.
- **Stuck `glueSpeaking`:** look for runs of `er-arbiter current=P3_FILLER … reject` with no glue completion between them.
- **Crawl redraw cost:** `adb shell dumpsys gfxinfo com.hermesvox` with an idle transcript on screen.
- **E1/E2 numbers:** add the speech-end → first-audible-anything metric, then read `event=lat`.
