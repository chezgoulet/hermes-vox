# PRESENCE — what it is, where it came from, and what it is for

Presence is the layer that makes Hermes Vox a being you are talking to rather than a phone that
answers. It is the on-device voice that hears you, keeps the line alive while the agent thinks,
yields when you interrupt, and sounds like *your* agent rather than a generic assistant.

It is on by default. It is not a second agent, and it does not know anything the agent does not know.

---

**Availability (0.9.1).** Presence is alpha: it runs only when **Alpha features** is on in
Settings and the Presence set is selected — the gate is `AlphaGate.allows(prefs, AlphaGate.FeatureSet.PRESENCE)` in `PresenceGate`. With the gate off, everything Presence
owns is dimmed and unreachable.

## The problem it answers

The question that started it, in the maintainer's words on 2026-09-07: a user builds an agent with
its own soul and personality on their Hermes instance. The on-phone model is meant to be an extension
of that personality — what the design doc calls "the Sesame experience". How do you bridge the two,
and what does the phone model get fed so that it both carries the identity and behaves like a live
conversation?

**The constraint is arithmetic.** A 2B model cannot carry a 100k-token agent — memory, tools, skills,
long reasoning. It can carry a tightly-authored *voice*. So the split is not a compromise; it is the
only arrangement that keeps both halves honest:

- **the mind** — the Hermes agent on the gateway: tools, memory, facts, hard reasoning, the
  substantive reply. It has authority over everything that is true or that changes the world.
- **the soul** — Gemma 4 E2B on the phone: the presence layer. Small talk, acknowledgements,
  backchannels, holding the wait, and the opening beat of every turn. It has authority over *how the
  agent sounds*, and none over what it knows.

That split already existed half-built in the app. What did not exist was a way to make the small model
sound like a specific agent, and a way to keep the line alive while the mind worked.

---

## How it carries the identity: VOX.md

The agent authors its own distilled voice into a file called **VOX.md**, generated from its own
SOUL.md, memory and tics, guided by a contract we host. It lives at `$HERMES_HOME/VOX.md`, beside
`SOUL.md`, so identity and voice are versioned together by the same entity.

VOX.md has two halves, and the distinction is the safety model:

- **The Contract** — fixed, byte-identical every time, dictated by us. Never invent a fact; never
  claim a result that does not exist; never commit to a real-world action; never claim a capability
  the mind does not have; always interruptible; never fabricate shared history.
- **The Soul** — the open fields the agent fills: name, self-image, register, how it addresses the
  user, the handful of distilled memories that give warmth, its humour.

**Direction of truth is one-way.** The gateway is authoritative; the device holds a read-only mirror.
The phone never pushes identity back, because a phone should not be able to silently rewrite your
agent's soul. A bad VOX.md is caught at generation, on the gateway, before it is ever mirrored.

**It is one document per profile, and the workflow only exists while Presence is on.** The phone keeps
`filesDir/vox/<profile>/VOX.md`, keyed by the active profile's name; the warm model reads the active
profile's file on every render and is re-primed when you switch, so the being that greets you is the
one you switched to. The pre-profile `filesDir/vox/VOX.md` is migrated into the active profile's slot
once. With Presence off, nothing fetches, mirrors, prompts for or reports on VOX.md.

Why the agent writes it rather than the user: it knows its own memory and relationship better than
any generic template, and the distillation is the one task only it is qualified to do.

---

## Where the idea came from

It was not one insight. The design accumulated against the field and against the app's own failures,
and the chronology matters because each step is evidence for the next.

**2026-09-07 — the question and the two-model split.** The maintainer framed the problem above. A
conversational-TTS specialist reviewed the design the same day and turned it into five engineering
rules, all adopted: the soul is *pure expression* and never a source of truth; the mind barges in
mid-phrase like a human recollection; personality-driven fillers with a density cap and a fail-soft
after a few seconds; a single priority audio arbiter so the two layers never talk over each other; and
shared state — a "vibe vector" — so the mind never answers from a stale picture of what the voice has
already said.

**2026-09-10 — the keyword lists fail, and the soul decides.** The first build routed turns to soul
or mind with an intent classifier: **174 literal substrings across seven lists**. In one afternoon it
produced three misses — *"So how's it going?"* read as information, *"hey, what's the weather"* as
smalltalk, *"how are ya?"* as a greeting it did not contain. Each fix bred the next, because language
does not enumerate. The cost was not cosmetic: a turn misrouted to the soul lane skipped the presence
ladder entirely, buying 10.5 s of silence in a normal turn and 79 s in a stalled one.

The decision was to stop routing by category and let the model decide by output: one render per turn,
with the Contract in front of it, returning either a line to speak or `<<ESCALATE>>`. The keyword
lists survive only as a safety object — backchannel-never-escalates, barge-cancels — because a safety
rule that stops growing is correct, not limited.

**2026-09-10 — the beat.** The soul still lost the mid-turn race: the mind's first token lands in
~1.8 s, the soul's render takes ~2.5 s, and the delivery guard correctly drops the soul line once the
reply has begun. On a healthy connection Presence looked inert, for a new reason.

The field research settled the shape. Sesame's model handles content and prosody but explicitly *not*
"turn taking, pauses, pacing". OpenAI's turn detection is a semantic classifier with a probability, a
timeout and an `eagerness` preset. ElevenLabs, the most deployed conversational stack, is cascaded
with a dedicated turn-taking model. **Nobody settles who owns a turn by routing it to one of two
generators. The floor is taken in beats.** So the soul takes the *opening beat of every turn* — three
to six words, instantly — and the mind preempts whenever its answer is ready, exactly as ordinary
barge-in already worked.

**2026-09-29 — the warm soul and the generated beat.** Reuse never needed a reset; the render was slow
because every call rebuilt a conversation and re-prefilled a ~600-token persona to produce ten tokens.
The soul now keeps one rolling conversation, primed at load, so each render prefills only its own
directive. The beat became generated rather than canned: the same render returns the opener, and
conversation history keeps it from repeating. The mind is *told* what the voice said, through a
soul-sync note. *(The `<<SKIP>>` reply this step added was retired by D30; the sync moved out of the
user's message into the request's `instructions` by D31.)*

**2026-11-04 — the companion, the words that are kept, and the words that are said.** The Presence review (`docs/reviews/PRESENCE-REVIEW-2026-11-04.md`)
traced the field report *"faster, but sometimes no response"* to four Presence-only paths, and three
changes came out of it. D30 retired the skip offer and the gate that held the mind's submit: the soul
is a companion that opens a turn, never a gatekeeper that answers it. The same week, two mechanical
faults were fixed: **the caller's words are never discarded** (a hold with content, and a turn that
arrives while another is in flight, both queue into the next turn instead of vanishing), and **the
mind is talked to through the API** — the voice register and the soul-sync ride the request's
ephemeral `instructions` instead of being glued onto the caller's message, with the anti-tool clause
deleted and a per-request reasoning effort (default low). The agent's own transcript now reads like a
conversation, and a price question gets looked up. Three smaller quality findings went with them:
a confirmation is answered as a turn rather than acknowledged with a non-sequitur; the mind's own
`commentary` is the working line instead of a 2B's guess about it; and no turn ends in silence.

**October 2026 — the name, and the guard's premise questioned.** D16 retired the two-mode framing:
"Enhanced Realtime" is not a mode, it is Presence, a layer on normal operation, on by default. D17
put latency on a conversational footing. D19 recorded that the guard's central premise — *a 2B will
invent numbers and facts, so ban them lexically* — is an **assumption**, and nothing measured it. D20
inverted the guard's job: **content is policed, format is repaired** — an eleven-word line is cut and
spoken rather than refused, markdown is stripped rather than refused, and only a claim that is false
is refused.

---

## What it does, mechanically

- **It renders one line per moment.** The soul is asked one question in front of VOX.md: who you are,
  what this moment is, and whether to speak. Its output *is* the decision.
- **It takes the beat.** On the mind's turns it speaks two to six words as it starts thinking, so the
  line is never silent while work happens.
- **It never makes the mind wait.** The turn is submitted the moment the caller stops speaking; the
  beat renders beside it and is dropped if the mind's first token gets there first. That is D30: the
  soul no longer holds the turn it used to hold for 700-900 ms.
- **A confirmation is an answer, not a backchannel.** "Want me to write those points to a markdown
  file?" → "Yes." used to be met with *"okay — take the time you need"* — the patient-caller line,
  spoken at turn start, where "yes" is the turn. Now the phone says nothing of its own at turn
  start (the soul may open with a beat); the hold line survives only for a backchannel spoken
  *while* the mind works, which is what it was written for (F5).
- **The mind narrates its own work.** When the gateway streams `phase=commentary` — "Let me search
  for recent listings" — that line is spoken as the working line, through the same guards as any
  other aside. It used to go to the dev console while a 2B guessed at the same tool from its name
  (F8).
- **No turn ends in silence.** A lost stream, a gateway error or an empty reply speaks one short
  repair — *"Sorry — I didn't get an answer back on that. Want me to try again?"* — instead of a cue
  and a pill nobody driving can read. A turn the caller cut is not repaired: they know they cut it
  (F9, `event=repair`).
- **It never loses what the caller says.** A backchannel spoken while the mind works ("mm-hm", "take
  your time") is absorbed — that is what a hold is for. Anything with content is *queued*: one pending
  slot, newest wins, submitted as the next turn when the current one settles, and dropped only when it
  has gone stale (60 s) or the caller has started a newer turn themselves. `event=er-pending` is the
  witness. A turn that arrives while another is still in flight takes the same queue instead of being
  reported as `suppressed-inflight` and forgotten.
- **It talks to the mind through the API.** A voice turn sends the mind its register ("speaking out
  loud on a phone call: two to four sentences, plain speech") and Presence's own context in the
  request's **ephemeral `instructions`**, plus a per-request reasoning effort (default *low*). The
  caller's words go on the wire untouched, so the agent's stored history and memory hold the
  conversation and not the protocol. The mind keeps every tool it has: the old anti-tool clause —
  *"do NOT do exhaustive tool work"* — is why a price question got a guess, and it is gone (D31).
- **It is gated in code, not trusted.** No digits the client did not supply. No claim of a result
  that does not exist. No second answer in a turn. No soul line after the mind's reply has begun. A
  line that is too long is repaired; a line that lies is refused.
- **It degrades honestly.** If the model is missing or will not load, the app is simply normal
  operation: no mode label, no degraded-mode pill. The Settings row says what is missing, because that
  is the only place a user can act on it.

---

## What it is not

- **Not a second agent.** The soul has no tools, no memory of its own, and no authority over facts.
- **Not a source of truth.** Anything that changes the world or teaches a concrete fact comes from
  the mind or does not come at all.
- **Not a cloud service.** The voice runs on the phone; only the mind is remote, and only because it
  is the user's own gateway.
- **Not always listening.** Presence runs inside a call the user placed. The wake word is a separate,
  unnumbered idea.
- **Not free.** The 0.9.1 soak measured a live call with Presence at about 3.1 GB resident;
  continuous capture, a separate alpha set, adds roughly 300 MB. The Alpha panel states both on
  the sets themselves ("about 3 GB of memory", "about 300 MB more memory"), because that is the
  trade the user is asked to make.
- **Not a personality generator.** It projects an identity the agent authored; it does not invent one.

---

## What we are trying to achieve

**Experientially:** a conversation that does not go dead while the agent works, that can be
interrupted without losing the thread, that acknowledges you while you are still speaking, and that
sounds the same whether it is saying hello or reporting a tool result — because it is one being, not a
chatbot with a voice bolted on.

**Measurably**, in the terms the design has committed to:

- **Cadence.** Speech-end to first audio inside conversational cadence. D17 set the working numbers
  at p50 ≤ 1.5 s / p95 ≤ 2.5 s; D18 amended them to **p50 ≤ 700 ms / p95 ≤ 1.4 s**, after the
  research checkpoint showed that a 1.37 s mean floor-transfer offset — roughly what the first
  generation of voice agents does — sits in the range of *meaningfully marked* gaps rather than
  natural ones. The more important half is the constant tail: the wait must not grow with how long
  you spoke.
- **Presence that speaks.** At the Conversational register, over a long run: at least two thirds of
  offered slots speak, nothing repeats, nothing lands after the reply's first text, render p95 under
  three seconds.
- **Conversation that recovers.** After an interruption: resume at the right step, address the
  interjection, and do not re-deliver what was already heard.
- **A guard that earns its bounds.** Every remaining bound in code cites a documented failure, and
  the measured ones retire.

**What is still unproven**, stated plainly because the design depends on it: the beat's own latency
(~1.8-2.6 s on a GPU phone end to end) is not solved — the beat stops the race *mattering*, it does
not win it, and D30 removed the cost of losing; the guard's premise is being measured rather than
assumed (#256); and whether a user wants ambient cues *and* presence audio at once is an open design
question (#251), with the owner's read being one or the other.

---

## The handoff: how ER decides, and how to read a session for swallowed turns

0.9.1 shipped Presence/ER with a release-gating report: *"it swallowed my question — the soul
answered something, and Hermes never did."* The layer is supposed to answer (that is the point), so
the question is narrow: **can a substantive turn end with neither voice answering, and can that be
seen afterwards?**

### The decision rule, in code

One render per turn. `ErSoulTurn.directive(callerText)` (`android/app/src/main/java/com/hermesvox/ErSoulTurn.kt`)
asks the soul one question — *the escalate token plus two to six words you would say as you start to
think, or the token alone for silence* — and **its output is the decision**:

| output | `Outcome` | what happens |
|---|---|---|
| `<<ESCALATE>>` + words | `Beat` | the mind's turn; the soul opens it, and the beat is dropped if the mind's first token beats it |
| `<<ESCALATE>>` alone | `Escalate` | the mind's turn, silently |
| a line with no token | `Spoken` | the model ignored the format. On a turn that is an answer the soul was not asked for, so it is not spoken (`er-soul-drop reason=not-a-beat`) — the mind answers |
| blank / unavailable | `Nothing` | the mind's turn |

Every voice turn is submitted to the mind at the same moment, whatever this render does. That is
D30: a soul render can no longer decide whether the mind answers, so a 2B guess cannot swallow a
question, and nothing waits for the render.

**What is enforced in code, and what is only asked.** The beat is guarded (`ErSoulTurn.opener`: no
markup, no claimed result, ≤ 7 words → otherwise escalate silently), and the working lane is guarded
(`ErSoulTurn.judge`: no unbacked result claim, shape repaired rather than refused). D29/#406 dropped
the no-new-numbers rule from both: a number is spoken, and an unvouched one is only logged
(`numbers-unvouched=`) for #256. D29's false-action-claim guard runs on **every** lane, once, inside
`speakGlue` (`event=er-glue-reject reason=result-unbacked source=…`): the greeting, the tool
narration, the beat, the working lane and the mind's own spoken progress line.

### What a voice turn tells the mind

The register and Presence's own context ride the request's **ephemeral `instructions`**, which the
gateway appends to the system prompt for that request only and never persists
(`gateway/platforms/api_server_response_admissions.py`). The caller's words are sent byte-exact as
`input`.

| field | what it carries | why it is there |
|---|---|---|
| `instructions` | the voice register: *"You are speaking out loud on a phone call. Reply in two to four sentences of plain speech — no code, no markdown, no lists."* | the mind knows how it is being heard. It says nothing about what it may do, so every tool stays available (D31) |
| `instructions` | the soul-sync (`ErDrift.sync`): what the phone's voice has already said this turn, and the caller's measured vibe | the mind does not repeat or contradict the beat |
| `instructions` | the presence context (`PresenceContext`): the soul's last line, whether the phone is speaking, the local actions the phone is running, and the time, battery, connectivity and Do Not Disturb state | the mind can answer "how long is left?" without asking, and knows what the phone has already done |
| `model_options.reasoning` | `{"enabled":true,"effort":"low"}` by default (`voice_reasoning_effort`: off / low / medium / high) | a spoken turn wants the fast answer; a provider that ignores it behaves as before |

Every request sends `instructions`, **including an empty one on typed turns**: the gateway inherits
the previous chained turn's value when the field is absent, so a typed turn after a voice turn must
clear it explicitly.

**The presence context** (`PresenceContext`, #421 step 1) is the block that tells the mind what the
phone is doing, because the mind is remote and none of this is visible to it:

```
presence: soul=beat | speaking=false | last_line="On it."
local_actions: timer(remaining=3m12s)
device: 18:42, battery 62%, online, dnd=off
```

It is capped at **200 characters** and trimmed deterministically (the quoted line first, then the
oldest action) so it can never crowd the register or the soul-sync out of a small model's context.
It never carries transcript text: the caller's words reach the mind as `input`, once. `soul=` says
which lane last spoke (beat / working / greeting / filler / quiet / off), `last_line` is that line,
`local_actions` is the registry of actions the phone took itself (#421 step 2), and `device` is the
clock, battery, connectivity and Do Not Disturb, all read without a permission the app does not
already hold.

The anti-tool clause that used to sit in the user's message — *"Answer from what you know; do NOT do
exhaustive tool work or re-audit your own docs/state unless the caller explicitly asks"* — is
deleted. It is the reason a price question got a guess instead of a lookup, and it made Presence
measurably worse than Realtime on the same gateway with the same agent (D31).

### The receipt

`event=er-handoff` is the one line per turn that says what the mind was handed:

| line | meaning | read it as |
|---|---|---|
| `event=er-handoff gen=N ok=true\|false reason=… soul=beat\|escalate\|none\|- askChars=N` | **the receipt**: exactly one per turn. `ok=true` = the gateway accepted the turn and returned a stream id; `ok=false` = the mind never saw it, and `reason=` says which path dropped it (`suppressed-inflight`, `cancelled-before-submit`, `stopped`, `no-stream`) | `ok=false` names a turn the mind never saw. `suppressed-inflight` and a held utterance are no longer lost turns: the words go to `event=er-pending` and are submitted when the current turn settles. `cancelled-before-submit` or `no-stream` is the one to read |
| `event=er-pending` | the caller's words were queued as the next turn (`queued=true reason=hold / suppressed-inflight chars=N`) or became it (`submitted=true`) | `queued=true` with no later `submitted=true` = words the caller spoke and the mind never saw |
| `event=er-soul-played` | the beat was **audible** — recorded from the TTS start callback, never from the render | a beat that renders and does not play is not a beat |

`askChars` is a length, never the caller's text, so the line is safe with transcript logging off.
`soul=-` means the soul was not consulted (presence off, a typed turn) or its decision did not land
before the submit — a subsequent `event=er-soul` line is then the decision that arrived late, which
is harmless: the mind was already answering.

`er-swallow` and `er-mind-skip` are **gone** with the skip offer they logged (D30). A turn can no
longer be "answered by the voice and skipped by the mind"; what replaces them is the receipt plus
`heard=none`-style evidence in the turn's own line.

### Reading a session

```bash
# every turn that should have reached the mind, and whether it did
grep -E 'event=er-handoff' session.log

# words that were queued instead of dropped, and whether they became a turn
grep -E 'event=er-pending' session.log

# the soul's decisions this session, and the beat's delivery
grep -E 'event=er-(soul|soul-played|soul-drop)' session.log | less

# what the mind was told on each voice turn (register + presence context)
grep -E 'reasoning=' session.log | tail -20
```

The other drops are in the same vocabulary: `er-soul-drop reason=late` (the beat lost to the mind's
first token — correct, the answer is what was asked), `reason=not-a-beat` (the render answered
instead of opening; not spoken), `reason=already-spoke`.

### The guard: proposed, not added

The instruction for 0.9.1 is to instrument before restraining. A hard guard ("a turn answer
containing a question mark or a digit is refused and escalated") would be a keyword rule in the
decision path — the pattern the design deleted after 174 substrings failed three times in one
afternoon (`docs/DESIGN-enhanced-realtime-voice.md` §DECISION). With D30 there is nothing left to
guard on the turn path: the mind always answers. The guard that remains is the false-action-claim
one, which is cheap and asymmetric.

---

## The record

- `docs/DESIGN-enhanced-realtime-voice.md` — the architecture, the contract, the specialist review,
  and the decisions of 2026-09-07 → 2026-09-29.
- `docs/DECISIONS.md` — D13 (the original two-mode framing), D16 (Presence is a layer, not a mode),
  D17 (cadence), D18 (numbers), D19 (the guard's premise is an assumption), D20 (repair format,
  police content), D21–D22 (the release shape).
- `docs/RESEARCH-2026-10-voice-agents.md` — the field evidence behind interruption classes, recovery,
  endpointing and power.
- `docs/VOICE-BAKEOFF.md`, `docs/PRESENCE-BENCH.md` — the measured voice and model choices.
- `docs/PLAN-0.9.2.md` — what the Presence release still owes.
