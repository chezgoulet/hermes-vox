# PRESENCE — what it is, where it came from, and what it is for

Presence is the layer that makes Hermes Vox a being you are talking to rather than a phone that
answers. It is the on-device voice that hears you, keeps the line alive while the agent thinks,
yields when you interrupt, and sounds like *your* agent rather than a generic assistant.

It is on by default. It is not a second agent, and it does not know anything the agent does not know.

---

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
conversation history keeps it from repeating. The mind is now *told* what the voice said, through a
soul-sync epilogue, and may reply `<<SKIP>>` when the voice already answered.

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
- **It waits, but not for long.** The mind's submit waits for that decision for a bounded, adaptive
  moment — the measured render p50 plus 200 ms, capped at 900 ms, and zero when the soul is measured
  slow. The beat plays through the wait.
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

**What is still unproven**, stated plainly because the design depends on it: the render budget
(~2.5 s against a 900 ms gate) is not solved — the beat is what makes the race stop mattering, not a
faster model; the guard's premise is being measured rather than assumed (#256); and whether a user
wants ambient cues *and* presence audio at once is an open design question (#251), with the owner's
read being one or the other.

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
