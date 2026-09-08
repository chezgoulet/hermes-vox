# DIRECTIVE — Hermes Vox (chezgoulet/hermes-vox) adversarial code review (Opus). Read-only.

You are reviewing a COPY of the Hermes Vox Android app at /home/c/vox-review
(HEAD 9c1a95f5, main). It is isolated in a sandbox. DO NOT modify, commit, or
"fix" anything. DO NOT touch /home/c/hermes-vox or any live checkout. Your ONLY
deliverable is a findings REPORT. You may read files and run read-only commands,
but you must not write tracked files or touch the live app/gateway.

## Context: this is the phone-side voice client of the Hermes agent

Hermes Vox (Android + a native Go module via gomobile that produces mobile.aar)
is a STATELESS voice client. It talks to the Hermes gateway's /v1/responses
endpoint. It does NOT maintain its own message history — it uses a
`previous_response_id` chain (server-side reconstructed conversation state) so
the gateway rebuilds history on each turn. Key path: `VoiceController` drives
the streaming turns; `HermesSession.turnStored`/`turn` is the Go client that
POSTs /v1/responses with `previous_response_id`; the Go side is under cmd/,
voice/, journal/ (the gomobile module). The Android Java/Kotlin is under
android/app/src/main/java/com/hermesvox/.

## Part 1 — Does Vox produce the empty `tool_calls`? (the ask that matters most)

There is a real production error. The gateway (NousResearch hermes-agent) sends
the model (deepseek-v4-flash) a request DeepSeek rejects HTTP 400:

  Invalid 'messages[38].tool_calls': empty array. Expected an array with
  minimum length 1, but got an empty array instead.

It shows on the phone as a short/terse ~130-char reply. We have already proven
(in prior forensics) that the gateway's OWN persisted session history is clean
(no empty tool_calls anywhere) — which points AWAY from the gateway's stored
state and toward something being sent that carries an empty tool_calls.

### The question for THIS review (rule out OUR side first)
Determine whether Hermes Vox could plausibly cause or contribute to a
`tool_calls: []` reaching the model. Investigate specifically:
1. Does Vox ever send `conversation_history` (a client-built message array) to
   /v1/responses? Or does it rely ONLY on `previous_response_id`? If Vox sends
   its own history array, inspect how it builds each message — could it emit an
   assistant message with `tool_calls: []`? (Suspicion: it does NOT send a
   history array — it's stateless via previous_response_id — which would
   EXONERATE Vox. Confirm or refute with file:line.)
2. Trace the exact request Vox builds: what fields does it POST? Does it carry a
   `conversation_history` key, and if so, what's in it? Does it ever set
   `tool_calls` on anything?
3. Inspect the Go gomobile client (cmd/, voice/, journal/) for how turnStored
   assembles the payload, and whether an empty tool_calls could be introduced.
4. If Vox is exonerated, say so explicitly and explain WHY (evidence, file:line).
   If Vox CAN contribute, give the exact location and mechanism.

Be rigorous and honest. If the evidence says "Vox doesn't build tool_calls at
all" — that is a real, valuable finding (it rules out our side, which is the
whole point of this review). Do not invent a Vox bug to please the premise.

## Part 2 — General code correctness / maintainability / bug review of Vox

Systematic review of the whole Vox codebase, weighted by risk:
- HIGH: anything that could drop/truncate a reply, lose audio, break barge-in
  or the reveal-freeze transcript, race the teardown (the crash the 0.5.0.1
  guard fixed), leak a credential, or send malformed provider input. Note the
  conn-test recently emitted a false 401 by sending the *encrypted* key as the
  bearer credential while the live stream decrypts it — check for sibling sites
  that read the raw pref instead of the resolved key.
- MEDIUM: correctness on exercised paths — barge-in/escape rule, silence/retire,
  the static-transcript reveal boundary, screen-alive, the /compress command,
  the visual-category system (VisualStyle.kt, AvatarView.kt, the 13-archetype
  swarm, the cycle-all option), frame-cost / allocation in the render loop.
- LOW: maintainability — god-files (MainActivity.kt, VoiceController.kt,
  AvatarView.kt are large), dead code, duplicated logic, missing error handling,
  the Go module's layout.

For each finding: severity (H/M/L), file:line, what's wrong, why it matters, and
a concrete suggested fix. Be concrete and honest; if a claimed bug doesn't hold
up on close reading, say so.

## Report format
Produce ONE findings report covering Part 1 AND Part 2. Lead with Part 1's
verdict (Vox exonerate-or-culpable, with evidence). For every finding:
SEVERITY / LOCATION / CONCLUSION / SUGGESTED-FIX. End with a top-5 "most worth
fixing" list (highest impact per effort). Print "REVIEW COMPLETE" at the end.
