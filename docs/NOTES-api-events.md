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
