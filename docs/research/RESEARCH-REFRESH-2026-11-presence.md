# Research refresh — Presence against the current literature, November 2026

Purpose: re-read our own voice research, check what has been published since, and hold our timings
and mechanics against it. This is a **cross-reference**, not a survey: each row names what we do,
what we measured, and what the literature says the number should be.

Prior work in this repo: `docs/research-on-device-voice-2026-08-27.md` (the latency budget and the
"everything streams and overlaps" principle) and `docs/RESEARCH-2026-10-voice-agents.md` (which
already concluded that D17's numbers were too lax and that a level gate cannot tell "mm-hm" from
"stop").

## 1. The human benchmark has not moved

- The **modal gap between turns is about 200 ms**, and modal overlap is under 100 ms (Levinson &
  Torreira 2015; Heldner & Edlund 2010; Stivers et al. 2009 — the cross-linguistic result that made
  this robust).
- The **standard maximum of unmarked silence** before a pause becomes meaningful is about **1 s**
  (Jefferson 1989); finer work puts the noticeable threshold near **700 ms** (Roberts & Francis
  2013).
- A measured voice agent's **floor transfer offset was 1366 ms (SE 30 ms)** and was judged to be
  "in the ball park of what would normally be meaningfully marked gaps" — i.e. it strips a layer of
  naturalness. That was our own October finding, and nothing published since contradicts it.

**Consequence:** 700 ms is the point at which a pause starts to *mean* something to a listener. It
is not a comfortable target; it is the edge of marked silence.

## 2. What is new since our October note

**Endpoint anticipation, not endpoint detection.** `arXiv 2606.13450` (2026) reframes endpointing as
a *prediction* problem: a model anticipates an end-of-turn **up to 2.56 s ahead**, letting the LLM
and TTS pipelines run speculatively on partial context, and reports **505 ms average latency
reduction for a 28.4 % increase in speculative compute**. This is the single most applicable result
for us, because it converts "wait for the VAD" into "start work early and discard if wrong".

**VAD-gated cascades are the known weak point.** `arXiv 2603.09180` (DuplexCascade, 2026) argues
directly that VAD segmentation "often forces half-duplex turns and brittle control", and replaces
long utterance turns with **chunk-wise micro-turns** coordinated by control tokens, preserving a
text LLM's intelligence while gaining full-duplex turn-taking. We are precisely the architecture it
criticises: Silero VAD decides the turn, then a cascaded LLM and TTS follow.

**The vocabulary has been formalised.** `arXiv 2606.19453` (2026) gives an L0–L3 architectural
hierarchy, a T×I×R interaction ontology (temporal relation, user intent, required response), and a
decision state machine — IDLE / LISTEN / SPEAK / WAIT / DUAL. Our `ErArbiter` is a hand-rolled
version of that state machine; adopting their names would make our behaviour legible to anyone
reading the field.

**Backchannels are predicted continuously.** `arXiv 2512.14085` and the NAACL 2025 VAP work predict
backchannel timing at frame level over ~300 h of dyadic conversation, with an open-source real-time
implementation (MaAI). Our beats are timer-driven from the soul's render, which is the rich version
of the same instinct but not the learned one.

## 3. Our numbers against theirs

| What | Ours | Literature | Verdict |
|---|---|---|---|
| Caller stops → first audible sound (E1 target) | p50 ≤700 ms, p95 ≤1.4 s (D18) | modal human gap ~200 ms; 700 ms is the edge of *marked* silence | **Target is the ceiling, not the goal** |
| E1 as estimated in the review | ≈1.8–2.6 s | as above | **9–13× the human modal gap; 2.6–3.7× our own target** |
| Caller stops → first word of the answer (E2) | ≈3.6–5.4 s estimated | cascaded systems target ~1 s complete turn; 1.5 s+ is where satisfaction falls steeply | **Beyond the cliff** |
| End-of-turn silence before the turn commits | `vad_silence_ms` 800 default, early start 600 | endpoint *anticipation* avoids paying this at all | **Paying a cost the field has learned to speculate around** |
| Soul's decision gates the mind's submit | 700–900 ms added on every escalated turn | no human turn-taking analogue; pure added latency | **Indefensible — already being deleted (D30)** |
| Barge-in | level + VAD, 15 phrases for semantic | full-duplex work reports **barge-in latency** and **success = stop ≤1.5 s** | **We do not measure either; adopt both** |
| Backchannels | timer-driven beats from the soul's render | continuous frame-level prediction (VAP/MaAI) | **Adequate for now; the learned version is the upgrade path** |
| Turn control | VAD decides, then cascade | VAD-free / micro-turn control is the 2026 direction | **Our architecture is the one the literature is moving away from** |

## 4. What this implies for us

1. **D30 is confirmed by the literature, not merely by our own review.** The 700–900 ms soul gate
   sits on a path already 2–4× over target; there is no turn-taking theory under which adding it
   helps. Deleting it is the cheapest latency win available anywhere in the app.

2. **The next win is speculative start.** Endpoint anticipation is worth ~500 ms by published
   measurement, which is more than the gate costs. Concretely: begin the mind's request on the
   partial transcript when the VAD is confident, and discard or amend when the caller continues.
   The mechanism needs a cancel path that already exists (the `/v1/runs` stop) — this is a genuine
   option for us rather than a research project.

3. **Our E1 target should be restated.** 700 ms is not "good"; it is the point at which a listener
   reads meaning into the pause. A defensible posture: **p50 ≤ 400 ms for first audible sound**
   (the beat), **p95 ≤ 1 s**, and the answer's first words under 2 s on a healthy gateway. Keep
   700 ms as the *failure* threshold, not the goal.

4. **Adopt the field's three metrics so our results are comparable:** first-turn response latency,
   barge-in latency, and barge-in success (stop within 1.5 s). Our E1–E7 remain useful internally,
   but these three make our numbers legible outside the project — and let us say honestly whether
   we are competitive.

5. **Do not rush to VAD-free.** DuplexCascade's argument is strong, but it is an architectural
   replacement, and we have a release to finish. Record it as the direction of travel and take the
   cheap part — speculative start — now.

## 5. What we should stop claiming

"Presence makes the phone feel faster" is true relative to a cold mind, but our own numbers put the
first audible sound 2.6–3.7× outside our stated target. The honest claim today is: **the beat hides
the mind, and the mind is still slower than a conversation.** That sentence, with the numbers above,
is what the next release should be measured against.
