# BUILD SPEC — Enhanced Realtime (ER) mode. The full implementation plan.

This codifies every decision we've locked. It is the BUILD READY plan: what runs on
the phone, what runs on the gateway, and in what order. It is NOT a feature list —
each item maps to a concrete, reviewable change.

## Architecture recap (agree-the-design is done)
TWO MODEL SPLIT:
- **Gemma 4 2B (phone)** = the SOUL. Presence layer: backchannel, fillers, mirroring
  affect, smalltalk glue, low-stakes paraphrase, latency presence. NEVER a source of
  truth. It is the voice of the entity.
- **Hermes agent (gateway, deepseek)** = the MIND. Facts, tools, planning, memory,
  real work. The substantive reply.
- **VOX.md** = the bridge. Gateway-authoritative; device is a read-only mirror. Contract
  (sacrosanct rules) + Soul (agent-authored distilled identity). Authoring happens on
  the gateway; the app pulls it and mirrors it. `$HERMES_HOME/VOX.md` beside SOUL.md.

## The invariant (the "sacrosanct" of ER)
The soul must NEVER originate a fact, a tool action, or a plan. If a response would
change external state or teach a concrete fact, it comes from the mind. The soul only
expresses, holds presence, and voices the mind's reply in the agent's register.

---

## PHASE 1 — Gateway: voice-mode signal (the "the agent knows it's on a call" mechanism)
**This is the piece that fixes the field multi-turn bug.** The gateway already has the
mechanism (`cli.py:13902` voice prefix, gated on `voice_input`, applied as a transient
per-turn message override — "CLI voice mode adds a temporary prefix for the live call
only", `agent_init.py:998`, via `_persist_user_message_override`). Extend it to the
api_server channel Vox uses.

1. **ApiServer request schema** (`gateway/platforms/api_server.py`): accept an optional
   `voice: true` field on `/v1/responses` (and `/v1/chat/completions` if the path shares
   it). Validate: boolean, default false. Do NOT let it be confused with output format.
2. **Translate to the voice prefix**: when `voice: true`, apply the same transient
   prefix mechanism the CLI uses. STRENGTHEN it for the field bug:
   ```
   "[Voice input — respond conversationally, 2-3 sentences. Speak plainly, no code or
   markdown. Answer from what you know; do NOT do exhaustive tool work or re-audit your
   own docs/state unless the caller explicitly asks.] "
   ```
   The second half is the fix for the agentic-turn bug (the agent loaded 100KB skills +
   ran session_search because it didn't know to just answer).
3. **Cache-safe**: apply it the SAME way the CLI does — as a per-user-message override on
   the live turn only, NOT a system-prompt mutation, NOT persisted. It must not break the
   per-conversation prompt cache (AGENTS.md: "prompt caching is sacred").
4. **Test**: gateway-side — a `voice: true` request produces a concise spoken-style reply
   with no tool spiral; same request without `voice` is unchanged. Verify the conversation
   cache is not invalidated (a second same-prefix turn reuses the cached prefix).

## PHASE 2 — Gateway: VOX.md authoring + serving (the identity bridge)
1. **Authoring contract** (a skill or prompt, gateway-side): the agent generates
   `$HERMES_HOME/VOX.md` from its own SOUL.md + memory + the Contract. This is the ONLY
   place VOX.md is ever authored.
2. **Validator** (gateway-side): checks Contract bytes identical to canonical, Soul fields
   non-empty + length-bounded, no secrets/PII/family data (House hard limit) caught at
   AUTHORING, so a bad VOX.md is never mirrored.
3. **Serve it**: an endpoint (or include in the responses payload) the app can GET VOX.md
   from the gateway. Gateway-authoritative, read-only. The app never writes it.
4. **Soul file format**: the Contract (fixed rules 1-6) + Soul (name, essence, register,
   relationship, distilled memory facts, humor). Keys for app display (name, theme) that
   the motion system can read (see Phase 4).

## PHASE 3 — App: ER mode toggle + onboarding bootstrap
1. **Enable ER** in Settings (a toggle). First-enable triggers the bootstrap:
   the app asks the gateway (already authenticated) to generate VOX.md — the gateway
   agent runs the authoring contract, writes `$HERMES_HOME/VOX.md` (or serves an existing
   one), and the app PULLS + mirrors it locally.
2. **"Resync VOX.md"** button in Settings → re-PULLs from gateway, overwrites local mirror.
   It is a PULL; the device never pushes. Pull-only invariant.
3. **On first ER enable**: confirm VOX.md exists; if missing/failed, surface a clear state
   (the entity can't find its voice) rather than silently proceeding.
4. **Onboarding order**: user already enters key + base URL (0.4.0/C0-C1). ER bootstrap is
   a follow-on, gated on key present.

## PHASE 4 — App: ER runtime — the soul's voice + presence
1. **The soul prompt** (Gemma 2B): VOX.md contract+soul + role:
   "You are the VOICE of this agent. You are not the mind." + presence duties (backchannel,
   fillers, latency presence, voice the mind's reply in-register). Fed from the local
   VOX.md mirror + a phrase for the current state.
2. **Intent classifier (soul-side, local/low-latency)**: 4 classes — emotion | smalltalk |
   information | action. Smalltalk/emotion → soul converses directly. Information/action →
   the soul acknowledges ("let me think…") and yields content authority to the mind.
   Backchannel ("take your time", "okay", "mmhmm", "go on") → a 5th class, no escalation,
   soul acknowledges and holds. This is the semantic barge gate (Phase 5) input.
   GUARANTEE: on ambiguity, default to NON-escalation + let the mind work (the "don't cut
   the mind on 'take your time'" asymmetry).
3. **Fillers + fail-soft (Miles rule #3)**: a small filler inventory keyed to sentiment
   + task type (hmm, okay…, right…, breath, mouth-noise). State machine keyed to
   time-since-user-stopped, time-since-mind-requested, emotional tone. HARD CAP ≤1-2
   fillers/3s, no full sentences until the mind returns. After 3-5s, in-character lag
   acknowledgment ("my mind's a bit slow, one sec") — NOT a generic "thinking…".
4. **Presence**: the waiting-constellation motion (0.5.0-B) driven by the real stall
   signal; the soul's fillers + the motion together make a provider wait feel like the
   entity is working, not frozen.

## PHASE 5 — App: ER barge-in scope (the semantic gate — the "take your time" answer)
**This is already designed (Miles + Christopher agreed).** In ER, barge-in grows a scope:
the physical trigger ALWAYS cuts the P3 filler (user may always interrupt the soul's
presence), but whether it cancels the MIND is semantic.
1. Two-stage "fire-and-hold": audio barge fires (3ms, unchanged) → P3 filler cuts instantly
   → mind NOT cancelled yet. The utterance is STT'd on-device and read before deciding.
2. Classify: GENUINE BARGE (new instruction, redirect, "stop", "actually do X", "what?")
   → cancel the mind, utterance is the new context. BACKCHANNEL ("take your time", "okay",
   "go on") → do NOT cancel the mind; soul acknowledges and holds.
3. SAFETY ASYMMETRY: default to NOT-cancelling the mind on ambiguity. Missed-cancel = user
   repeats (cheap). False-cancel = mind regenerates 15s of work (expensive). Bias toward
   not-cancelling the mind; the P3 filler always cuts.
4. Measure: log every mind-cancel + whether it followed a backchannel-classified utterance
   → field miss-rate → tune the classifier against real data.

## PHASE 6 — App: the priority audio arbiter (Miles rule #4 — the double-talk foot-gun)
One output arbiter, strict priority:
  P0 system stop/cancel · P1 mind content · P2 critical soul (safety/clarify) ·
  P3 non-critical fillers.
Any P0-2 interrupts all P3 immediately. Split speech into 150-300ms cancellable chunks;
snap cuts at phoneme/word boundaries. Log interruptions/overlaps aggressively.
(0.4.0.4's silenceAll/fence/pause-first teardown already gives the audio primitives; this
composes on top so the soul and mind never talk over each other.)

## PHASE 7 — App: context drift sync (Miles rule #5)
- **Shared rolling state**: before the mind responds, it sees the raw user text + a compact
  log of soul actions since the query (which fillers/paraphrases/smalltalk already covered)
  so it doesn't re-state confirmations.
- **Vibe vector**: the soul periodically emits {mood, energy, trust, last_ack}; the mind
  conditions on it (softer if frustration high, more direct if energy high).
- **Output alignment**: the soul does a light post-processing pass to harmonize tone
  without changing facts; guardrails prevent it contradicting the mind's answer.

## PHASE 8 — Telemetry + release
- ER mode logs: soul-classifier decisions, escalation events, barge-in scope decisions,
  arbiter interrupts. Measure: first-token (mind), first-audio, soul-first-word, miss-rate
  of the classifier, miss-rate of the barge gate. These are the honest numbers that tell us
  if ER feels alive.
- Version: 0.6.0 (after the animation 0.5.0.2). Release notes with the honest "device-
  verified?" frame. KEEP-list: all 0.4.x-0.5.x invariants.

## Sequencing & gating
- Phase 1 (gateway voice signal) is the highest-value FIRST item (fixes the field
  multi-turn bug). It's small, gateway-side, cache-safe-per-design.
- Phase 2 (VOX.md) is the identity bridge — must be done before the soul prompt exists.
- Phase 3 (authored/bootstrapped) needs Phase 2.
- Phases 4-7 are app runtime (soul prompt, classifier, barge-scope, arbiter, drift-sync).
- Each phase: gate `:app:testDebugUnitTest` + `assembleRelease` (compile) + independent
  review (anchor rule: verify the ARTIFACT, not the plan). NEVER claim "done" until the
  real build is green.
- Order within: gateway phases before app phases (so the app has a real signal + document
  to consume). Phase 1 next; phases 2-3 together; then 4-7.
