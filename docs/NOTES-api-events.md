# What the Hermes API server gives the app

Verified 2026-10-21 against the running install: **Hermes Agent v0.22.1+37.ge7b816e**.
Sources: `gateway/platforms/api_server_openai_routes.py`, `api_server_runs.py`, `api_server.py`.

This note exists because two 0.9.1 issues depend on the answer: #281 (operations) and #282
(approvals). One is an app problem; the other is an upstream gap.

## `POST /v1/responses` — the stream Vox uses

Events:

- `response.created`
- `response.output_item.added` / `.done` — item types `message`, `function_call`,
  `function_call_output`
- `response.output_text.delta` / `.done`
- `response.reasoning_summary_part.*`, `response.reasoning_summary_text.*`
- `response.completed` / `response.failed`

**Tool identity is in the stream.** A `function_call` item carries `name`, `arguments` and `call_id`;
`function_call_output` carries the result. The app already parses both (`core/Stream.kt`) and handles
them in `VoiceController`, where they are currently rendered as log lines.

So **#281 is a rendering and state problem, not plumbing.** No upstream change is needed to know which
tool is running and on what.

## Approvals are a different story

The responses writer has no approval path. The gateway has a complete approval surface, but it is
wired to chat completions and the runs bridge:

- **Event:** `approval.request` with `{ event, run_id, timestamp, command (redacted), choices }`
  and `choices` drawn from `once | session | always | deny`, narrowed by policy.
- **Run status:** `waiting_for_approval`.
- **Answer:** `POST /v1/runs/{run_id}/approval` with a choice.

`_register_stream_approval()` registers a streaming completion as a run so that endpoint can answer
it. It is called from the chat-completions path only. `/v1/responses` never registers its response id
as a run and never emits `approval.request`, so a Vox turn that hits an approval has no channel: it
stalls.

## What #282 needs upstream

Parity, not a new mechanism:

1. register the `/v1/responses` stream as a run, the way `_register_stream_approval()` does for chat
   completions;
2. pass `approval_notify` into the responses agent call so the `("__approval__", event)` tuple reaches
   the responses writer;
3. write it as `event: approval.request`, as the chat-completions writer already does.

Then the app can render #282 and answer with `POST /v1/runs/{response_id}/approval`. This is the
"small upstream hook" D25 describes: the capability is upstream-owned, and the app's work is the
affordance.

## The app side (#282, landed)

The client now consumes that contract and does not need the tool's display name:

- **Event:** `approval.request` (`core/Stream.kt`), carrying `run_id`, `request_id`,
  `command` (already redacted upstream), `description`, `reason` and `choices`. The event
  name rides both the SSE `event:` line and the `event` field; `type` is not required.
  A session-authority gateway also sends `execution_generation`; the app echoes it when
  present and omits it otherwise.
- **Answer:** `POST /v1/runs/{run_id}/approval` with `{"choice", "request_id"}`
  (`core/HermesSession.answerApproval`).
- **Rules:** the choices are rendered exactly as offered (unknown words dropped, `deny`
  always appended); a positive voice answer takes the least privilege offered
  (`once` → `session` → `always`); a timeout is always `deny`; the exactly-once latch is
  `core/ApprovalGate`, so a double tap or a second voice match never posts twice.
- **Log:** `event=approval decision=once|deny|timeout choice=… run=… request=… cause=…`.

**Verified against this host's gateway source (Hermes 0.22.5):** `_approval_request_event`
builds exactly that event, and the `/v1/runs` bridge answers it. On 0.22.5 the
`/v1/responses` writer still does **not** register its response as a run or pass
`approval_notify`, so a real `approval.request` cannot yet appear on Vox's stream from
this build. That is the upstream hook above, unchanged. The deterministic stand-in used
to prove the phone half is [`scripts/approval-harness.py`](../scripts/approval-harness.py);
it emits the same event and records the POST. Once the gateway ships the hook, the same
code path runs with no app change.
