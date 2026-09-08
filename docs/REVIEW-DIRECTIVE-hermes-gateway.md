# DIRECTIVE — Hermes Agent (NousResearch) adversarial code review (Opus). Read-only.

You are reviewing a COPY of the Hermes Agent codebase at /home/c/hermes-review
(HEAD fdc342c0), cloned into an isolated sandbox. DO NOT modify, commit, or
"fix" anything. DO NOT touch /home/robot/.hermes/hermes-agent (the LIVE gateway
this review is about). Your ONLY deliverable is a findings REPORT. You may read
any file, run read-only commands, and inspect databases/logs under the sandbox,
but you must not write to the live system or change the sandbox's tracked files.

## Part 1 — Track down the empty `tool_calls` error (the primary ask)

There is a real, reproducible production error. Confirm it and root-cause it:

On 2026-09-07 21:10:33, session b033ac1f-863d-461a-926c-2c134eab6362 (Vox voice
client, /v1/responses path), the gateway sent DeepSeek a request that DeepSeek
rejected HTTP 400:

  Invalid 'messages[38].tool_calls': empty array. Expected an array with
  minimum length 1, but got an empty array instead.

Same class on 2026-09-06 19:54 (messages[23]). The gateway THEN retried 3x and
failed with "Non-retryable client error" each time. This shows up on the phone as
a short/terse 130-char reply. The field log for that session shows the app
streamed fine (stream-done, clean turn labels) — so the failure is the gateway
forwarding a malformed `tool_calls: []` to DeepSeek.

### Evidence already gathered (verify, don't trust — re-derive if you can)
- The gateway PERSISTED history for b033ac1f in ~/.hermes/state.db is CLEAN —
  every assistant message has a real non-empty tool_calls array or (none). No
  `[]` anywhere. Same for 3 sibling api_server sessions that afternoon.
- The response_store (~/.hermes/response_store.db) records for that session
  (resp_9580c202..., resp_f2d800e1..., resp_739c2eb9...) are CLEAN too (0 empty
  tool_calls in their conversation_history).
- Because persistence is clean but the error reached the wire, the empty array
  is introduced at SEND time, not stored. The live in-memory `messages` list is
  copied verbatim to api_messages (agent/conversation_loop.py ~1587-1589:
  `api_msg = msg.copy()`), so `tool_calls: []` on a live message rides straight
  out. Model responses that emit a tool-call block resolving to zero calls can
  land as `tool_calls: []` in a live message.
- Candidate send-path sites to scrutinize: agent/conversation_loop.py
  `_canonicalize_api_tool_calls` (~798-829, the `if not tcs: continue` skips
  without dropping the key); agent/message_sanitization.py (:107, :359);
  agent/transports/chat_completions.py (:266, :322, :848);
  agent/replay_cleanup.py (:61, :71).

### What Part 1 must deliver
1. The EXACT mechanism/location where an empty `tool_calls: []` reaches the wire.
   Cite file:line and quote the relevant code. Distinguish root-cause from
   symptom.
2. Whether it's the gateway's fault (a gap it should close) or correct behavior
   wrongly triggered. The environment assumes the gateway SHOULD be robust here —
   DeepSeek correctly rejects malformed input; the gateway is expected to not
   send it. So confirm whether this is a gateway robustness bug the maintainers
   should fix, and where.
3. A minimal, correct, KEEP-list-safe fix (as a PATCH you would propose, NOT apply
   — present it in the report). It must strip an empty tool_calls on the send
   path without mutating persisted history (per-conversation prompt caching is
   sacred) and without breaking valid tool-call sequences.
4. A regression test that would catch this (asserts an assistant message with
   empty tool_calls is stripped before the wire, and valid tool_calls are kept).

## Part 2 — General code correctness / maintainability / bug review

Systematic review of the codebase. The vendor's own conventions (from the repo's
AGENTS.md) include: never break per-conversation prompt caching; strict role
alternation; no cache-breaking mid-conversation; behavior-contract tests over
snapshot tests; the core is a narrow waist. Weight findings against those.

Prioritize by risk, roughly in this order:
- HIGH: anything that could drop/lose data, break a conversation mid-turn,
  double-charge a model call, leak a secret, or send malformed provider input
  (the empty-tool_calls class). 
- MEDIUM: correctness bugs on a path that's actually exercised (voice realtime,
  the /v1/responses SSE path, the gateway command dispatcher, context
  compression, message repair/sanitization).
- LOW: maintainability — god-files, dead code, duplicated logic, places the
  AGENTS.md "refactor these" rule names (cli.py, run_agent.py, gateway/run.py),
  missing error handling.

For each finding: severity, file:line, what's wrong, why it matters, and a
concrete suggested fix. Group by severity. Be concrete and honest — do not pad.
If a claimed bug doesn't hold up on close reading, say so (the report is stronger
for having ruled things out).

## Report format
Produce a single findings report covering Part 1 AND Part 2. Lead with Part 1's
root-cause (that's the ask that matters most). For every finding include
SEVERITY / LOCATION / CONCLUSION / SUGGESTED-FIX. End with a top-5 "most
worth fixing" list (highest impact per effort). Print "REVIEW COMPLETE" at the
very end.
