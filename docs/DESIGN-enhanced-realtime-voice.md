# DESIGN — Enhanced Realtime voice: bridging a bespoke Hermes agent to the on-phone voice

## Problem (Christopher, 2026-09-07)
A user constructs an agent with its own soul + personality on their Hermes instance
(the "mind"). The on-phone Gemma 4 **2B** is meant to be an extension of that
personality, producing the Sesame experience. How do we bridge the two — what does
the phone model get fed so it acts both as the Sesame-style conversational presence
AND carries the bespoke identity?

## The architecture: a two-model split (already half in the code)
Current Vox already splits roles:
- **Gemma (phone, local, low-latency)** = the *presence layer*: speakGlue, mid-work
  narration, backchannels, holding the wait. This is the SOUL'S VOICE.
- **Cloud Hermes agent (gateway)** = the *mind*: tools, memory, facts, hard reasoning,
  the substantive reply.

A 2B cannot carry a 100k-token agent (memory + tools + skills). It CAN faithfully
carry a tightly-authored VOICE. So: **don't make the 2B be the whole agent — make it
correctly project it.** Intelligence = cloud; presence = phone. This is the correct
split, and a smaller phone model makes the manifest's *quality* matter MORE, not less.

### The bridge: VOX.md = Contract (sacrosanct) + Soul (agent-authored)
The user's agent authors its own distilled soul into a VOX.md file, guided by a
static prompt we host. The agent knows its own SOUL.md + memory + tics + relationship
better than any generic template — so the distillation is done deliberately, at
authoring time, by the one entity qualified to do it.

**LOCATION: `$HERMES_HOME/VOX.md` — the sibling of `$HERMES_HOME/SOUL.md`.**
SOUL.md is profile-level (`~/.hermes/<profile>/SOUL.md`), injected as system-prompt
slot #1, scanned for injection. VOX.md lives beside it — same directory, sibling
file — so the agent's identity (SOUL) and its voice-export (VOX) are co-located and
versioned together by the same entity.

### VOX.md lifecycle & onboarding (Christopher's refined flow, 2026-09-07)
Direction of truth is UNAMBIGUOUS and one-way: **gateway is authoritative; device is
a read-only mirror. Never the other way.** Codified as an invariant.

BOOTSTRAP (first ER enable, app already authenticated to the gateway):
1. The app asks the gateway agent to generate VOX.md from its own SOUL.md + the
   authoring contract. The agent writes `$HERMES_HOME/VOX.md` on the gateway — the
   only place it's ever authored/edited.
2. The app REQUESTS it (pull) and syncs a copy locally (within the app's own storage,
   NOT into the gateway). The device never writes back.

KEEP-FRESH:
- Settings presents a **"Resync VOX.md"** button → re-PULLs the file from the gateway
  to the device, overwriting the local mirror. That's the only sync action and it's a
  pull. (The web-prompt idea is absorbed: the host authoring template guides the
  *gateway* agent's generation; the app drives it and mirrors it. Not a web page the
  user visits.)
- The agent has a mechanism to regenerate/update its OWN VOX.md on the gateway (via the
  same authoring contract), and it's reflected in the app after a resync.

INVARIANTS (the "sacrosanct" of identity sync):
- Device NEVER pushes VOX.md (or any identity file) to the gateway — a phone can't
  silently re-write your agent's soul. If the app ever tried to, it fails closed.
- The gateway is the single source of truth; the device is a cache that is refetchable.
- The authoring contract + validator sit ON THE GATEWAY side (checks Contract bytes
  identical, Soul fields non-empty, no secrets/PII — the House hard limit), so a
  bad VOX.md is caught at generation, before it's ever mirrored.

### Contract — fixed, sacrosanct, byte-identical every time (dictated by us)
1. Never invent facts / never fake a result. If you don't know, say so or escalate.
2. Never commit to real-world actions (purchase, config, send/destroy) — the mind's
   job, with tool authority.
3. Substantive/factual/tool/planning questions ESCALATE to the mind. Hold smalltalk,
   emotion, casual, presence only.
4. Never claim capabilities you don't have — you're the voice, not the practitioner.
5. Always interruptible — never talk over the user.
6. Don't fabricate shared history beyond what the distilled memory states.

### Soul — variable, agent-authored (open fields the prompt leaves blank for the agent)
name, self-image, 2-3 sentence essence, register/tone/catchphrases, how it addresses
the user, the relationship, the handful of distilled memory facts that give warmth
(e.g. "you bake; moving to Quebec; daughter Béatrice; family names"), humor, what
it's proud of.

## Linting at authoring time (the "sacrosanct" guarantee)
A validator checks the produced VOX.md: (a) Contract bytes identical to canonical;
(b) Soul fields non-empty + length-bounded; (c) no secrets or family/PII leaked
(public-facing app — the House hard limit). "Sacrosanct" enforced at creation AND
at runtime, since relying on a 2B to *remember* a rule is how it drifts.

## Soft escalation, controller-gated (the safety invariant)
Soft path chosen (Christopher agreed) — the 2B may converse on smalltalk/emotional
content directly and escalate on real work. The safety rule: escalation is
**initiated by the 2B** (recognizes "beyond me") but **honored/gated by the controller
in code**, not by the 2B's discretion. The 2B's prompt makes it want + able to
escalate; the system makes it *unable to not-escalate when it should* (tool/fact/plan
classes route regardless). Presence = 2B's job; intelligence = mind's.

## Open questions for Miles (conversational-TTS / turn-taking expert)
1. **Division of authority**: is 2B-initiated + controller-gated soft escalation the
   right split, or should the 2B be *purely* presence with escalation always decided
   in code?
2. **Handoff in time**: does the mind preempt the 2B mid-filler like a real
   interruption (the barge-in model applied at the model layer), or does the 2B
   finish its beat? Where should the boundary sit?
3. (Secondary) presence-filler quality on a 2B vs latency budget — is there a known
   good architecture for keeping a 2B "alive on the line" while the mind thinks?

## Miles's review — ADOPTED (2026-09-07, no follow-up needed; this IS the answer)
Miles (conversational-TTS specialist) confirmed the design and sharpened it into
five concrete engineering rules. All five are adopted as the Enhanced Realtime spec:

**1. Division of authority → hard gate, confirmed + made concrete.**
2B soul = PURE EXPRESSION only (backchannel, fillers, mirroring affect, small-talk
glue, low-stakes paraphrase). Never a source of truth. The CONTROLLER does early
intent classification (emotion | information | action). For information/action the
soul only acknowledges ("let me think…") and yields content authority to the mind.
INVARIANT: if a response would change the user's external state or teach a concrete
fact, it originates from the mind, never the soul. (Confirms my Contract rule #3,
sharpened into a classifier + invariant.)

**2. Handoff timing → mind barges in like a human recollection.**
Soul opens with a SHORT BOUNDED PREAMBLE (300-700ms max of "thinking" filler). The
mind may preempt mid-phrase as soon as it has a stable answer. Prefer open-ended
stems ("let me think…", "okay so…") that truncate cleanly without semantic loss.
Target pattern: "Hmm, let me see—oh, right. The best way is…" where "oh, right" is
the mind snapping into focus, not a second persona.

**3. Latency → personality-driven fillers + fail-soft.**
Small inventory of non-verbal/semi-verbal fillers keyed to sentiment + task type
(hmm, okay…, right…, breath, mouth-noise). Driven by a state machine keyed to
time-since-user-stopped, time-since-mind-requested, and current emotional tone.
HARD CAP density: ≤1-2 fillers per 3s, no full sentences until the mind returns.
FAIL-SOFT: after 3-5s, shift from neutral "thinking" to an in-character lag
acknowledgment ("my brain's a little slow today, give me one more sec"). Separate
slowness (brain-fog metaphor) from failure (brief apology + invite retry).

**4. Audio buffer → single priority arbiter (the double-talk foot-gun).**
Miles names the exact failure I was worried about (overlapping audio when the mind
lands mid-filler). One output arbiter, strict priority queue:
  P0 system stop/cancel · P1 mind content · P2 critical soul (safety/clarify) ·
  P3 non-critical fillers. Any P0-2 interrupts all P3 immediately.
Split speech into 150-300ms cancellable chunks; snap cuts only at phoneme/word
boundary. If overlap for ultra-low latency, keep <100ms + acoustic matching so it
reads as self-correction, not two speakers. Log interruptions/overlaps aggressively.

**5. Context drift → shared state + vibe vector.**
The mind operates on stale input (network+compute). Fix: mind sees raw user text +
a compact log of soul actions since the query (which fillers, paraphrases, small-talk
already covered) so it doesn't re-state confirmations. Soul periodically emits a
low-dim VIBE VECTOR {mood, energy, trust, last_ack} the mind conditions on (softer
tone if frustration high, more direct if energy high). Soul does a light
post-processing pass to harmonize tone without changing facts; guardrails prevent it
contradicting the mind's answer. INVARIANT: both layers always share a consistent
view of what's promised/answered/deferred, a near-real-time emotional estimate, and
ONE coherent persona despite cognition and expression living in different places.

## What this changes in the design
- My six Contract rules are CONFIRMED; rule #3 becomes a controller-side intent
  classifier + invariant (not just a prompt instruction).
- The handoff protocol was the open question; Miles gave the answer (mind preempts
  mid-phrase, bounded preamble, clean truncation stems) — this is the model-layer
  analog of the barge-in we already ship on the audio side.
- New engineering surfaced that I hadn't specced: the priority audio arbiter (P0-P3)
  is a real requirement, and the filler density cap + fail-soft persona are needed
  to keep a 2B from over-talking or drifting. The vibe-vector state sync is the piece
  that keeps the two layers from feeling out of phase.

## ER barge-in scope — the semantic gate (Christopher's "take your time" question)
In ER there are TWO outputs (soul P3 fillers + mind generation) and one listener. Barge-in
grows a SCOPE: the physical trigger (audio-level) always cuts the P3 filler (user can
always interrupt the soul's presence layer), but whether it cancels the MIND is a
SEMANTIC decision, not physical.

MECHANISM — two-stage barge "fire-and-hold":
1. Audio barge fires (3ms, unchanged) → P3 filler cuts instantly.
2. Mind NOT cancelled yet. The utterance is STT'd on-device (already running) and read
   before deciding.
3. Classify into two classes (reuse Miles rule #1's intent classifier + a 4th class):
   - GENUINE BARGE (new instruction, redirect, "stop," "never mind," "actually do X",
     "what?") → cancel the mind, utterance becomes new context. Today's behavior.
   - BACKCHANNEL ("take your time," "okay," "mmhmm," "go on," "right," "sure") → do NOT
     cancel the mind. Soul acknowledges in-character ("okay, give me a sec") and keeps
     holding the space while the mind continues.
   Backchannel is the smallest, most well-bounded class — exactly the 2B's lane. It
   must be local/low-latency, never round-trip to cloud.

SAFETY ASYMMETRY (the sacrosanct rule that matters):
Default to NOT cancelling the mind on ambiguity. If the classifier is <90% sure it's a
genuine barge, treat as backchannel and let the mind work. Rationale:
  - Missed cancel (user meant to barge): user repeats themselves. CHEAP.
  - False cancel (user said "take your time", killed 15s of work): regenerate from
    scratch. EXPENSIVE.
Bias the error toward not-cancelling the mind, while the filler always cuts.

THREADING:
- Priority arbiter (rule #4): user speech = P0, cuts P3 filler unconditionally. Whether
  it ALSO emits a P1-cancel to the mind is the separate semantic step ABOVE the arbiter.
- Backchannel acknowledgment = P2 "critical soul" (must play even before mind returns),
  cuttable by a real barge.
- Fail-soft (rule #3): long wait + patient user "take your time" → in-character lag
  acknowledgment is the right response, not a cut.
- MEASURE: log every mind-cancel + whether it followed a backchannel-classified utterance
  → field miss-rate → tune the classifier against real data, not guess.

## Relationship to the current release family
- 0.5.0-A: speech-locked transcript (text reveals with the voice, dims the tail).
- 0.5.0-B: state-driven presence motion (stall→waiting-constellation etc.).
- This design: the VOICE + IDENTITY architecture that Enhanced Realtime uses them for.
These ship together as the "presence" release that ER opens into.

---

# DECISION (2026-09-10, 0.8/M3c) — the soul decides; the lists stop routing

## The problem, stated with numbers

The intent classifier held **174 literal substrings** across seven lists — 32 backchannel,
34 action, 31 information, 26 emotion, 20 smalltalk, 16 greeting, 15 barge — and decided
from them whether a turn belonged to the soul or the mind. It could not converge, and the
field proved it inside one afternoon with three misses, each fix breeding the next:

- *"So how's it going?"* → **information** (a leading discourse marker hid the greeting)
- *"hey, what's the weather"* → **smalltalk** (a real question rode in behind a greeting)
- *"how are ya?"* → **information** (a greeting variant the lists did not contain)

A fourth patch would have produced a fourth edge case. The tell is that each fix was a
*language* judgement expressed as a string list, and language does not enumerate. Worse, the
consequence was not cosmetic: a turn routed to the soul lane **skipped the presence ladder
entirely**, so a misroute bought silence for the whole mind-work window — 10.5 s in a normal
turn, 79 s in a stalled one.

## The decision

**Ask the soul model the question, once per turn, and let its output be the decision.**
Gemma reads the caller's line with the Contract in front of it and either answers as the soul
or emits a single escalate token. Nothing else routes.

**1. The keyword lists keep one job, and it is a safety job.** Backchannel-never-escalates and
barge-cancels stay deterministic, enumerable and auditable. You do not want a 2B model in the
abort path, and those lists are *supposed* to be finite — a safety rule that stops growing is
correct, not limited. `ErIntent` is now that object and nothing more: `HOLD_ONLY` or
`ACK_AND_YIELD`, and every non-backchannel turn is the mind's lane until the soul says
otherwise.

**2. The mind runs in parallel, and that is the safety net.** Every turn still goes to the
mind, whose reply preempts the soul by the existing `speak()` precedence. A wrong soul answer
therefore cannot do damage — it can only be wrong about *who speaks first* — which is what
makes a model-based router acceptable where a model-based abort decision would not be.

**3. One render per turn, not two.** The soul's decision and its knowledge of what the mind is
doing ride the *same* call. The tool context (which tool is running, what it is for) is fed
into that render so the soul's line can be topical — *"checking your inbox now"* rather than a
generic greeting. Two calls per turn would double the GPU cost for no gain.

**4. Narration comes INSIDE the presence loop.** The tool-call narration path currently fires
per tool event, outside the loop, with no density discipline at all. That is the defect the
field exposed: seven *identical* greetings across thirty-nine seconds of one turn —
seventeen seconds of GPU to say the same wrong thing seven times.

**5. A spacing guard must yield silence, never the stand-in.** The rail correctly skipped a
regeneration and then spoke the `RoutedExpress` fallback line, because `express()` returns the
fallback when the guard fires. Two-line fix.

**6. A same-text guard.** Nothing currently stops the same sentence twice in a window. The
field defect was not the *number* of utterances; it was that seven came out identical, blind to
time and blind to what was happening. A stuck-record guard is the actual fix.

**7. The count is not a constant.** What governs how much the soul says is **change** (a new
tool starting is a natural beat, and where a topical acknowledgement belongs), **density**
(the existing per-3s cap and the user's slider), **duration** (silence-first, then fail-soft),
and the soul's own judgement of whether saying anything adds anything. A fixed per-turn number
is exactly the kind of constant that breaks in context.

## Status of this document

The routing is removed in this change. The **decision render** — the soul reading the turn and
answering or escalating — is the next increment on the same branch, together with items 4-6.
Until it lands, every non-backchannel turn is the mind's lane with the presence ladder running,
which is strictly better than a misroute that silenced the ladder. Nothing here claims the soul
lane is wired until that render exists.

