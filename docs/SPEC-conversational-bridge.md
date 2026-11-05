# SPEC — the conversational bridge (profiles and files, without touching Hermes)

> **Historical — kept as a record.** It describes the state of the app at the time it was written and is not a claim about the current build. See `docs/INDEX.md` for what is current.

Two features the app needs that the Hermes API does not expose: **which profiles a host serves**, and **a way to hand the agent a file**. Both are solved here as *conversation*, using the surface the app already has and the agent's own tools. No plugin, no fork, no host-side service, no change to Hermes.

Status: proposed. Nothing here is deployed. Supersedes the patched-contract approach in `feat/api-server-profiles` and `feat/api-server-attachments`, both parked.

---

## A. Profiles, asked in conversation

### Why

The gateway multiplexes profiles by default and addresses them at `/p/<profile>/…`, but nothing lists them. The app already knows how to ask the agent for a document and validate the reply — VOX.md does exactly this — so the same shape carries the profile list.

### The ask

One ordinary chat turn on the connection the app already holds. Reserved marker first, so the agent can key on it and the app can recognise its own request in a log:

```
[[vox:profiles]] Which Hermes profiles does this gateway serve to this key?
Reply with one fenced vox-profiles block and nothing else.
```

### The reply

Exactly one fenced block, labelled, containing only these lines:

```
```vox-profiles
default=home
profile=home
profile=work
```
```

Rules:
- `default=<name>` — zero or one line.
- `profile=<name>` — one or more lines.
- A name matches `[a-z0-9][a-z0-9_-]{0,31}`. Anything else is dropped.
- No other line types are honoured. Prose around the block is ignored; prose *instead of* a block is the fallback case.
- Duplicates are read once. Names are case-sensitive as written.

### The rule that makes the answer safe

**The list is a hint; a probe is the proof.** A name is only added as a usable profile if a request through it succeeds:

- default profile → `GET {base}/v1/models`, bearer as configured;
- named profile → `GET {base}/p/<name>/v1/models`, same bearer.

A 200 adds or refreshes the profile. Anything else — 401, 404, timeout — marks it **unavailable**, which the registry already displays and which never deletes the user's key. A hallucinated or stale name therefore cannot become a broken entry the user has to debug.

Probes run once per refresh, at most 8 at a time, each with a 10 s timeout.

### Cache and refresh

- The ask runs on **connect** and when the user taps **Refresh from host**. It does *not* run when the switcher opens: the switcher reads the cache instantly, and offers refresh explicitly. This is the whole reason the answer is cached — a switcher that waits on a model turn would be worse than manual entry.
- Cache freshness is displayed, not hidden: `profiles from this host · checked 4 min ago`.
- A manual refresh is refused if the last one is under 60 s old, to make a runaway loop impossible.

### Failure, stated plainly

Every failure lands on one line in the switcher, never an empty list and never a spinner:

| failure | line |
|---|---|
| no fenced block, prose instead, unparseable | `this host did not answer with a profile list` |
| marker echoed but no names survive the charset | `this host named no usable profiles` |
| ask timed out (45 s) | `the host did not answer in time` |
| some names dropped as unusable | `2 profiles added · 1 not reachable` |

The manual add/edit path stays available in every one of these states.

### Security

- The ask carries no key, no address, no user data beyond the marker.
- Names are charset-pruned before they reach the registry — no slashes, no dots, no traversal.
- The app never derives an address it has not probed.
- A profile switch still ends any live call. Unchanged, and still covered by the property test over every trigger.
- **The ask is a turn the user pays for.** One per connect, one per manual refresh, cached in between, and it is logged as `event=profiles-ask` with its duration so the cost is visible rather than assumed.

---

## B. Files, offered by the phone and fetched by the agent

### Why

The API accepts text and inline images; it refuses `file` parts by design. But the phone and the agent are both on the tailnet, and the agent has `curl`. So the phone serves the file for the length of one fetch, and the turn carries a capability URL instead of bytes.

### The flow

1. The user picks a file, or shares one to Vox.
2. The app copies it into a private staging directory and computes `sha256` and byte count.
3. The app starts a **one-shot HTTP server** bound to the **tailnet address only** — never `0.0.0.0`, never cellular — on an ephemeral port, serving exactly one path: a 128-bit random token. No directory listing, no other route, no method but `GET`.
4. The app sends a normal turn carrying the offer:

```
[[vox:file]] name=meeting-notes.md type=text/markdown bytes=8123
sha256=9f2c… url=http://100.82.156.1:49152/6b1f…e0 expires=1793650000
I am sharing one file with you. Fetch it now, verify it, and keep it.
```

5. The agent fetches with `curl`, **verifies the byte count and the sha256**, writes it somewhere it can read later, and confirms in its reply with the path it kept.
6. The server shuts down on the first completed fetch, or at expiry, whichever comes first.

### The limits, stated before any of it is built

- **Size cap 100 MB**, refused above it with the number in the message. No silent truncation anywhere in this flow.
- **Expiry 5 minutes** from the offer. After that the URL is dead and the app says so.
- **Single use.** One successful transfer, then the server stops. A second request gets 410 Gone.
- **Verification is mandatory.** The agent compares bytes and hash and reports a mismatch as a failure — a truncated fetch must never be filed as a success by either side.
- **If Tailscale is down** the app does not start a server nobody can reach; it says so and offers the inline path.
- **Inline fallback**: text files under 8 KB travel inside the turn as a fenced block with the name in the fence label, so a small note never needs a network dance.
- **One request has one total deadline** (L7, 0.9.1 review). The server handles one connection at a time, so a peer that trickles a header byte every few seconds could otherwise hold the only handler until the offer expired. A request must finish within 15 s of accept plus one second per 512 KB of body (so a large file on a slow link is not cut, while a trickler still cannot hold the handler), or its socket is closed and the listener moves on. The per-read timeout still applies inside that window.
- **Our own private storage is not a share source** (L4, 0.9.1 review). A `file://` URI whose canonical path is under this app's `dataDir`, or a `content://` URI for this app's own authority, is refused with "that is Vox's own private file · nothing was sent". Otherwise another app could hand Vox a path into its keys, database or staged copies and have Vox read it with its own rights.

### What the user sees

- While offering: `sharing meeting-notes.md · waiting for the agent to collect it`
- On success: `collected · meeting-notes.md (8.1 KB) · ~/.hermes/inbox/meeting-notes.md`
- On expiry: `the agent did not collect the file · tap to try again`
- On mismatch: `the agent received a different file than we sent · nothing was kept`

### Security

- Bind to the tailnet interface only; a server reachable from the LAN or the internet is a defect, not a configuration.
- Token is 128-bit, path-only, single-use, and the server logs every request it refuses.
- Only the staged file is reachable; path traversal, range tricks and alternate methods are rejected and logged.
- The file's contents never enter the conversation transcript — only its name, size, type and hash.
- The staged copy is deleted when the flow ends however it ends.

---

## What this spec deliberately does not do

- It does not add a route to Hermes, and it does not depend on one. If Hermes later ships `GET /v1/profiles` or an attachment endpoint, the client feature-detects and the conversational path retires without an app change.
- It does not put file bytes through the model.
- It does not make the switcher wait on a turn.
- It does not claim the agent will always co-operate: every path above has a stated failure, and the failure is shown to the user rather than logged and forgotten.

## What a build needs to prove

1. A profile ask against this host returns a block the parser accepts, and a second ask is refused inside 60 s.
2. A name that does not probe is marked unavailable, not added as usable.
3. A file of 8 KB, 1 MB and one just over 100 MB behave as specified — collected, collected, refused with the number.
4. A fetch that is cut short fails verification and keeps nothing.
5. The listener refuses a LAN and a cellular address; only the tailnet one answers.
