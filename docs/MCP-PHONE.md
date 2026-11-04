# The phone as an MCP server (#421 steps 3 and 4)

Presence has two ways of acting on the phone, and they deliberately do not share a wire:

| initiator | path | guarantee |
|---|---|---|
| **the caller** — "set a timer for five minutes" | `LocalActionGrammar` → `LocalActions.run` | instant, no round trip; the mind learns about it from the presence context on the next turn (D32) |
| **the mind** — "I'll remind you in an hour" | MCP `tools/call` on this server → `LocalActions.phone` | a request and a **result**; the mind knows whether it happened |

This document is the second one: what the phone exposes, how the gateway is pointed at it, and what
happens when it is not there. It is written to be followed by hand; nothing here changes the owner's
gateway.

---

## What the phone exposes

**One tool, `phone`, with an action enum.** Not eight tools: the schema rides on every turn the
gateway offers it on, and a single tool with a required `action` is cheaper and harder for a model to
misuse than `phone_timer` / `phone_alarm` / `phone_dnd` / … (#421, third comment).

| action | tier | arguments | result (success) |
|---|---|---|---|
| `timer` | 0 | `duration_ms` (1000 – 86 400 000) | `id`, `remaining_ms`, `duration_ms`, `undo` |
| `alarm` | 0 | `at` (`"07:30"`, 24-hour) | `id`, `at`, `in_ms`, `undo` |
| `dnd` | 0 | `on` (bool) | `id`, `on`, `previous`, `undo` |
| `volume` | 0 | `level` (0–100) **or** `direction` (`up`/`down`) | `id`, `level`, `previous`, `undo` |
| `note` | 1 | `text` (≤ 500 chars) | `id`, `text`, `reported_next_turn`, `undo` |
| `undo` | 0 | `id` | `undone`, `state` (`cancelled` / `restored`) |
| `status` | 0 | – | `offered`, `tiers`, `running`, `notes`, `registry`, `dnd`, `volume` |

`status` is the discovery action: it answers "what can you do, and what is running right now" from
the **same registry** the presence context block reports (`local_actions:`), so the mind never has to
guess and the two can never disagree. A timer there carries its id and `remaining_ms`; the registry
line carries the compact `timer(remaining=3m12s)` form.

### Results, and the honesty rule

Every call returns one JSON object as its `content[0].text`:

```json
{"ok":true,"action":"timer","tier":0,"id":"timer-3","remaining_ms":300000,
 "duration_ms":300000,"undoable":true,"undo":{"action":"undo","id":"timer-3"}}
```

A refusal is a **result, not a transport error** — `isError` stays `false`, because a phone that
cannot set Do Not Disturb has answered the question, it has not failed:

```json
{"ok":false,"action":"dnd","tier":0,"reason":"permission",
 "detail":"this app does not hold do-not-disturb access; only the owner can grant it in system settings"}
```

The reason vocabulary is stable and small:

| `reason` | meaning |
|---|---|
| `permission` | the phone does not hold a permission the action needs (Do Not Disturb only, today) |
| `unavailable` | the action was recognised and the platform refused or failed |
| `expired` | no undo is registered for that id any more (it fired, was superseded, or was never this phone's) |
| `invalid-arguments` | the arguments do not describe a runnable action |
| `unknown-action` | not a phone action; `detail` lists what is on offer |
| `approval-required` | tier 2: nothing was done, and the owner's approval surface owns it |

**The invariant**: `ok: true` is only ever rendered after the platform said it performed the action,
and a refusal carries no `id`, no `state`, no success fact. So the mind's claim "I set a timer" is
traceable to a result it was actually given — which is the false-action-claim guard's premise applied
to the phone's own claims first. Results never use the key `error`: the gateway classifies a result
whose text contains one as a server application error, and a refusal is not a server failure.

### Undo lifetimes

Every action that can be undone registers an undo **for its lifetime**, keyed by the id it returned:

| action | undo does | lives |
|---|---|---|
| `timer` / `alarm` | cancels it | until it rings |
| `dnd` | restores the previous interruption filter | until the next `dnd` (a second change makes the older "previous" untrue) or 10 minutes |
| `volume` | restores the previous level | the same |
| `note` | deletes it | 24 hours — a note outlives the turn that made it |

`undo` of an id with no live undo is `ok:false, reason:"expired"`. It never guesses.

### Tiers

| tier | actions | what the phone does |
|---|---|---|
| **0 — instant** | timer, alarm, dnd, volume, status, undo | executes now, reversible, private |
| **1 — reported** | note | executes now, reported in the next turn's presence context, undoable |
| **2 — approval** | share, message, call, email, delete, purchase, launch, send … | **never executes.** The result says `approval-required` and nothing happens |

Tier 2 is refused rather than queued on purpose. An MCP call carries no run id, so the phone cannot
mint the approval card `core/Approval.kt` answers — and building a second approval surface inside the
MCP server is exactly what #421 rules out. Consequential actions stay on the **gateway's** approval
surface. An owner who wants *every* phone call behind a card can have that without any code change:

```yaml
    trust: untrusted    # every write-capable tool call asks through the standard approval surface
```

That is the same card as any other tool's; the phone does not grow a second one.

---

## The gateway config, exactly

The phone shows this snippet in **Settings → About & Diagnostics → Phone as an MCP server**, with the
live address and token filled in and a **Copy config** button, so what the owner pastes is what this
build actually speaks.

```yaml
mcp_servers:
  vox:
    url: "http://100.101.102.103:8765/mcp"
    headers:
      Authorization: "Bearer <the token from the phone>"
    connect_timeout: 5
    tool_timeout: 30
    trust: full   # set `untrusted` to require an approval card for every call
    tools:
      include: [phone]
```

- `url` is the phone's **tailnet** address (`100.64.0.0/10`), the port the app binds
  (`8765`), and the one path it answers (`/mcp`). Nothing else on the network can reach it.
- The model sees the tool as **`mcp__vox__phone`** (the gateway's `mcp__<server>__<tool>` convention).
- `include: [phone]` keeps the server's tool surface to the one tool; add nothing else there.
- `connect_timeout: 5` is short on purpose: the phone is only up while a call is (see below), so a
  down phone must fail fast rather than hold a turn open. The gateway's default is 60 s.
- `trust: full` is what makes Tier 0 instant. `untrusted` routes every call through the approval
  surface first — correct for a cautious owner, and it costs the "instant" part of the tier.

Prefer the environment reference for the token if the config file is synced or shared:

```yaml
    headers:
      Authorization: "Bearer ${VOX_PHONE_TOKEN}"
```

with `VOX_PHONE_TOKEN=…` in the profile's `~/.hermes/.env` (values resolve from the active profile's
secret scope). The literal form above is fine when `config.yaml` stays on the gateway host.

### Token handling

- The token is **128 random bits**, generated once on first use, and stable across launches.
- It is stored **encrypted** (`Android Keystore`, AES/GCM, its own alias — `SecureStore`) in private
  SharedPreferences. It is never written in plain text: if the keystore refuses to encrypt, the token
  lives in memory for that launch only, the log says `event=phone-mcp token-store=failed
  session-only=true`, and the owner re-copies the config next time.
- It is **never logged**. The refusal log lines name the request's shape (`method=POST path=<mcp>`),
  never the credential.
- Comparison is **constant-time** ([PhoneMcpToken.matches]), so the port does not leak a prefix.
- **Replacing** it means clearing the app's data (or uninstalling): the next launch generates a new
  token and the gateway fails authentication until the config is updated. There is no remote rotation
  and no second token.
- It is not the Hermes `API_SERVER_KEY` and nothing derives it from that key.

### When the phone is offline

The server is up **while a voice call is up**: `VoiceController.start` starts it, `VoiceController.stop`
(hang up) closes it. There is no listening socket outside a call.

What that means, in order of who notices:

1. **Gateway connect fails.** With `connect_timeout: 5` the failure is quick. The gateway parks the
   server with backoff and the model simply does not see `mcp__vox__phone` for that turn.
2. **A call starts.** The phone binds again; the next connect (or the gateway's retry) finds the tool.
3. **The tool is missing or the call fails.** The mind must not claim the action. There is no second
   path — a directive-in-reply fallback would be the fire-and-forget shape #421 rejected, and the
   phone's own answer to "could you set a timer" is still the local path, which does not need the
   gateway at all.

If the phone has no tailnet address, nothing binds and the log says
`event=phone-mcp unavailable reason=no-tailnet`. If the port is taken, `reason=bind`. In both cases
the phone keeps working; only the mind's reach into it is absent.

---

## Security reasoning

**Why a tailnet, not the LAN.** The server binds exactly one address: the one `Tailnet.pick` chooses
(`100.64.0.0/10`, or the Tailscale IPv6 ULA). `PhoneMcpServer.start` refuses an any-local address
itself, so a future caller cannot turn it into `0.0.0.0` by accident. The same rule the file-offer
server follows (#B), for the same reason: an unauthenticated phone on a café network is a remote
control for someone else's prank.

**Why a bearer token on top.** The tailnet says *which network*; the token says *which client*. A
tailnet can carry other devices, and the phone's actions are private — Do Not Disturb, volume, a
note about a plumber. Every request needs `Authorization: Bearer <token>`; everything else is 401
before a byte of body is parsed.

**Why the tiers are the third layer.** Neither of the first two says *what* an authenticated client
may do. The tier table does: Tier 0 is reversible and private, Tier 1 is reversible and visible, and
Tier 2 is refused because it leaves the phone or cannot be undone. The phone will not send a message
or place a call for the mind however it authenticates.

**What is deliberately absent.** No file access, no listing, no second route, no SSE stream, no
server-initiated messages, no session state (`Mcp-Session-Id`): each is a surface with no caller on
this side. There is also no verbose log of the arguments — a note's text is the caller's, and it
belongs in the note, not in a log that gets attached to bug reports.

---

## What the wire actually is

Stateless **Streamable HTTP** JSON-RPC on `POST /mcp`, which is what the gateway's MCP client speaks
(MCP 2025-03-26 and later). `initialize` negotiates `protocolVersion` (echoed when it is one we
implement: `2024-11-05`, `2025-03-26`, `2025-06-18`, `2025-11-25`), `notifications/initialized` gets
`202 Accepted`, `tools/list` returns the one tool, `tools/call` returns a `content` text block.
`HEAD` answers `application/json` so the gateway's pre-flight probe recognises an MCP endpoint rather
than a web page; `GET` is `405`.

No `Mcp-Session-Id` is issued, so the client never opens the GET stream: every call is independent,
which is what a phone that can be backgrounded needs. The state that matters lives in the registry,
not in a session.

---

## What a device pass should check

1. **Start a call with Tailscale connected.** The log shows `event=phone-mcp start url=http://100.x…:8765/mcp`.
   Hang up: `event=phone-mcp stop served=N`.
2. **Point a scratch client at the URL with the token** (`curl` the `initialize` POST, or
   `mcp-server-fetch`-style tooling). Without the token: `401`. With it: `tools/list` returns `phone`.
3. **`timer` from the client** → `ok:true` with an id; the timer rings; `status` no longer lists it;
   `undo` of that id afterwards says `expired`.
4. **`undo` while it is running** → the timer is cancelled and does not ring.
5. **`dnd` on a phone that has never granted policy access** → `ok:false, reason:"permission"`, and
   the Do Not Disturb state is unchanged.
6. **`volume`** → the reported `level` matches the phone's own slider afterwards.
7. **`note`** → the next voice turn's `local_actions:` line carries `note(id=…)`, and `undo` deletes
   it.
8. **`message`** → `ok:false, reason:"approval-required"`, and nothing is sent.
9. **Turn the call off** → the next tool call from the gateway fails (server parked), and the agent
   says it could not reach the phone rather than claiming the action.
10. **Turn Tailscale off** → no listener at all (`reason=no-tailnet`), and the app is otherwise
    unaffected.
