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

## The bridge: VOX.md = Contract (sacrosanct) + Soul (agent-authored)
The user's agent authors its own distilled soul into a VOX.md file, guided by a
static prompt we host. The agent knows its own SOUL.md + memory + tics + relationship
better than any generic template — so the distillation is done deliberately, at
authoring time, by the one entity qualified to do it.

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

## Relationship to the current release family
- 0.5.0-A: speech-locked transcript (text reveals with the voice, dims the tail).
- 0.5.0-B: state-driven presence motion (stall→waiting-constellation etc.).
- This design: the VOICE + IDENTITY architecture that Enhanced Realtime uses them for.
These ship together as the "presence" release that ER opens into.
