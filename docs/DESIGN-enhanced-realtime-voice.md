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

## Relationship to the current release family
- 0.5.0-A: speech-locked transcript (text reveals with the voice, dims the tail).
- 0.5.0-B: state-driven presence motion (stall→waiting-constellation etc.).
- This design: the VOICE + IDENTITY architecture that Enhanced Realtime uses them for.
These ship together as the "presence" release that ER opens into.
