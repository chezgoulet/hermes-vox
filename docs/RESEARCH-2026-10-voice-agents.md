# Research checkpoint — voice interaction with agents, October 2026

The practice: before a cut, check what we believe against what the field currently reports, and
record where our assumptions were confirmed and where they were wrong. This is the first
checkpoint. Every claim below is cited; nothing here was measured on our device.

Scope: turn-taking and endpointing, overlap and interruption, backchannels, latency
perception, on-device streaming ASR, and power.

---

## 1. Confirmed — our model choice was right, by someone else's sweep

**arXiv 2604.14493**, *Pushing the Limits of On-Device Streaming ASR* (Apr 2026). A systematic
empirical study of 50+ configurations across Whisper, **Nemotron**, Parakeet TDT, Canary,
Conformer Transducer and Qwen3-ASR, evaluated in batch, chunked and streaming modes on CPU
without GPU acceleration. It identifies **NVIDIA Nemotron Speech Streaming as the strongest**
of the set for on-device streaming.

That is independent corroboration of D5 and of the arm wired in #215 — our A/B reached the same
conclusion on our own corpus, and the field reached it on a much larger one. It also tells us
the *class* of model is right: CPU, streaming, compact, not a GPU-bound LLM ASR.

## 2. Challenged — D17's latency numbers are too lax

**Barthel 2025**, *"Alexa, you are too slow!" Invariant turn-transition times and conversational
flow in natural human–voice agent interaction* (SEMDIAL 2025, `semdial.org/anthology/Z25-Barthel_semdial_3315.pdf`).
Measured on 1077 turn-transition places in real households using a first-generation Echo Dot;
831 measurable floor-transfer offsets from 9 users in 6 households.

- Human–human turn gaps are commonly **200–300 ms** (Levinson & Torreira 2015; Heldner & Edlund 2010; Stivers et al. 2009).
- The "standard maximum of unmarked silence" before a gap becomes meaningful is about **1 s** (Jefferson 1989); finer work puts the meaningful threshold near **700 ms** (Roberts & Francis 2013).
- The voice agent's modelled mean floor transfer offset was **1366 ms (SE 30 ms)** — and the paper's judgement is that this is "generally in the ball park of what would normally be meaningfully marked gaps in human-human interaction", i.e. it *strips a layer of naturalness*.
- Users adapt: they learn to tolerate gaps beyond **3 s**, roughly double the agent's modal response.

**What this does to D17.** D17 set p50 ≤ 1.5 s, p95 ≤ 2.5 s, on the working assumption that
under a second reads as immediate and over two and a half seconds reads as broken. The field
says 1.37 s **is the slow thing users complain about**, not the acceptable band. The right
reading of our own principle — "speed only matters up to cadence" — is that cadence is
**~300–700 ms**, and 1.4 s is the bar to *beat*, not to meet. The failure line at ~2.5 s stands.

The principle in D17 is confirmed; the numbers need tightening. Proposed: **p50 ≤ 700 ms,
p95 ≤ 1.4 s**, with the constant-tail property unchanged and unchanged in importance.

## 3. Challenged — a level gate cannot tell "mm-hm" from "stop"

**arXiv 2507.23159**, *Full-Duplex-Bench v1.5* (2025). The benchmark separates four overlap
scenarios: **user interruption, user backchannel, talking to others, background speech**.

**arXiv 2603.24144**, *Semantic-Aware Interruption Detection* (Mar 2026) — SID-Bench. States the
field's polarisation precisely: "trigger-happy VAD-based methods that misinterpret
backchannels" against "robust end-to-end models that exhibit unacceptable response delays".

Our barge gate is a duty-cycle level test with a calibrated floor. It is exactly the
"trigger-happy VAD" pole, and the history in #186 is the predicted consequence: raise the floor
and a soft-spoken user cannot interrupt; lower it and a backchannel kills the reply. Tuning the
constant was never going to solve it, because **the missing dimension is the class of the sound,
not its level**. A backchannel should not cancel a reply; an interruption must.

## 4. Missing entirely — what happens *after* an interruption

**arXiv 2606.19595**, *IHBench* (Jun 2026). Existing benchmarks measure interruption *timing* —
barge-in detection, endpointing, turn-taking. IHBench measures recovery: does the agent resume
the workflow at the correct step, does it address the user's interjection, and does it avoid
re-delivering content the user already heard.

We have no notion of any of the three. Barge-in currently ends the reply, and the turn is
simply over. For an entity doing agentic work — a long tool run, a multi-step answer — stopping
mid-sentence and never returning to the task is the difference between "interruptible" and
"responsive".

## 5. Confirmed — endpointing is a prediction problem, and backchannels are continuous

**arXiv 2509.20410**, *Phoenix-VAD* (2025): an LLM-based, plug-and-play module for **streaming
semantic endpoint detection**, sliding-window trained, supporting streaming inference. This is
the architecture #229 assumes, and it exists as prior art.

**arXiv 2512.14085**, *Multilingual and Continuous Backchannel Prediction* (Dec 2025):
frame-level continuous prediction of backchannel timing, ~300 h of dyadic conversation, with
real cross-lingual differences and limited zero-shot transfer between languages. Two
consequences for us: backchannel timing is predictable **continuously** rather than at turn
boundaries, and the model is language-specific — so a single timing policy for all languages
would be wrong once we leave English.

## 6. Confirmed — power is a first-class constraint, and it is component-level

**NAACL 2025 Industry**, *Breaking Down Power Barriers in On-Device Streaming ASR*
(`aclanthology.org/2025.naacl-industry.50.pdf`): power depends on how weights are configured,
varying with **invocation frequency and memory allocation**, and adjusting components by their
own energy sensitivity achieves **up to 47 % lower energy** at comparable accuracy.

This is the evidence #228's battery precondition needs, and it says the lever is not "use a
smaller model" but "spend the energy where the invocation is frequent".

## 7. Also worth reading, not yet assessed

- arXiv 2503.04721 — Full-Duplex-Bench v1 (turn-taking benchmark).
- ACL 2026 short 4 — Full-Duplex-Bench-v2, multi-turn with an automated examiner.
- arXiv 2607.20460 — Instruct-FD: can full-duplex systems follow turn-taking *instructions*?
- arXiv 2509.22243 — FLEXI: benchmarking full-duplex human–LLM speech interaction.
- ACL Anthology 2025.iwsds-1.27 — survey of turn-taking modelling in spoken dialogue.
- arXiv 2505.17070 — improving endpoint detection in end-to-end streaming ASR.

## What follows for us

| finding | consequence |
|---|---|
| §2 latency | tighten D17's numbers to 700 ms / 1.4 s (owner's call — it changes an approved gate) |
| §3 interruption classes | barge-in must classify overlap, not just measure level |
| §4 recovery | after an interruption, resume, address, and do not repeat |
| §6 power | the battery precondition in #228 gets a method, not just a checkbox |

## Caveats

Preprints move: v1 to v5 revisions are visible in several of these, and numbers shift between
them. The latency figures come from one corpus (German households, first-generation Echo Dot),
so they are a strong prior rather than a law. None of it was measured on our hardware, and
nothing here replaces the field run in #179.
