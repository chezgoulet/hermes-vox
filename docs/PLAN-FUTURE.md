# Plan — unnumbered work: what is on the table

Nothing on this page is scheduled. It is the honest list of ideas good enough to keep, kept so they
survive the gap between "we thought of it" and "the community asked for it" (#268). Anything here can
be taken up early; anything here can be dropped without breaking a release.

The rule for entry is not "could we build it" but "would this make the app notably better for
someone using it". The rule for leaving is a demonstrated want, not a plan running out of numbers.

---

## Continuity (#231)

One conversation, moved between surfaces with the same session, memory and voice — done twice, on
real hardware, without repeating a turn.

**What it depends on, and what is not ours.** The gateway's session-key semantics
(`X-Hermes-Session-Key` for a named conversation, `X-Hermes-Session-Id` for a thread), a resume token
that means *this conversation, this far*, and a per-surface renderer. Two of those are a Hermes-side
contract, not an app change.

**Two constraints recorded rather than discovered later.** Android Auto's Car App Library is
template-only — the being cannot be drawn there, so the car gets voice plus the platform's own list
and message templates. And two surfaces holding the same session race unless one is clearly the
writer; handoff must be an explicit transfer, not two clients guessing.

**The second surface is a decision to make first.** Desktop was ruled out as a client (D1), so the
realistic candidates are a web or PWA text surface, the car, or a satellite device. Only one of them
can draw the being.

## The household (#234, #46)

Speaker ID and per-person relationships: who is talking, and how the entity addresses a family rather
than a user. Multi-profile (#174) becomes identity rather than connection bookkeeping.

**This is a security property, not a nicety.** Today any detected voice triggers the agent —
`VoiceController.kt:158-211` runs a turn on any VAD speech, and there is no embedding model in the
catalogue. In a one-person tool that is a privacy annotation; in a multi-person app it is the
difference between an assistant and a speaker anyone in the room can drive. The direction is a
speaker-embedding stage between VAD and STT, with enrolment and an explicit fallback.

## Entity calls (#232)

Per-event-class routing — voice call, text, none, delegated — with a user-supplied push endpoint and
a per-class rate ceiling. This is what turns an armed session into a proactive presence.

**Constraint:** the entity may only initiate on an arm the user set, never unprompted. A presence
that can reach you without permission is not a feature, it is a liability.

## Remote control of the interface (#233)

An MCP surface plus a closed scene vocabulary, so the agent can *draw* meaning — a shape, a label, an
arrow, a timeline — rather than free-draw. Per-tool grants, a visible "the agent has control" state,
an audit log, and a dead-man switch on screen-lock or call-end.

**Closed by design:** a language model must never be asked to be a renderer. The six-verb grammar in
#227 is the app-local ancestor; if this is ever built, it inherits that discipline rather than
inventing a second vocabulary.

## Wake word (#45)

There is no wake-word engine and no ambience service today; Silero VAD only gates utterance-start
inside a line the user already opened. D6 step one is training a wake word in our own pipeline, and
the House has no ML-training GPU — so this needs rented compute or a decision to drop it.

The question to answer before the compute question: does an always-listening app fit what this
product is? A phone call you place has a clear beginning; an always-on room does not.

## Watch items (#237, #238)

Two pieces of prior art, tracked so they survive:

- **Ming-3.0-tts 0.5B** — MIT, 44.1 kHz, paralinguistic tags, but 2.96 GB of weights, PyTorch-only,
  no ONNX graph for the backbone. Not adoptable now; worth following.
- **VoiceManager** — one WebSocket carrying streaming STT and TTS, documented frame by frame. Its
  frame set is the same set of races Vox has to solve. Read it before designing anything in the
  continuous lane; the vocabulary already exists.
