# Hermes Vox 0.5.2-claude — no false 401, and a user-facing /compress

Two items from the 2026-09-07 device pass on 0.5.1.

> `conn-test: verdict=auth ping=401 stream=-1` at 14:27 and again at 16:05 — and the
> very next turns streamed perfectly, over the same gateway, with the same key.

> "We need to expose a compress command to the user through this app and/or have
> [the entity do it]." — Christopher, end of the 15-turn session

---

## K1 — the false 401 (root cause, not a reword)

### K1_CAUSE — it was **(c)**: the probe sent the key it never should have had

The gateway key is stored **encrypted at rest** (`SecureStore`, AES/GCM — the
SharedPreferences value is the string `ivBase64:ctBase64`). Every *live* path resolves
it before use:

```kotlin
// MainActivity.storedKey(), SettingsActivity, OnboardingActivity — all of them:
GatewayKey.resolve(prefs.getString("key", ""), SecureStore::decrypt)   // -> plaintext
```

The conn-test probe did not. `probeConnection` / `testConnection` read the pref
straight and sent it as the bearer:

```kotlin
val k = prefString("key", "")                                  // the CIPHERTEXT
c.setRequestProperty("Authorization", "Bearer " + k)           // "Bearer ivB64:ctB64"
```

So the probe presented base64 ciphertext as a credential and the gateway answered
`401` — correctly, to a request no live turn ever makes. The live voice channel, going
through `HermesSession(url, storedKey(), model)`, sent the real key and streamed fine.
Two different credentials on the same device, one of them never valid: that is the
whole of `verdict=auth ping=401` followed by working turns. It reproduced on *every*
device whose key encrypted successfully, which is every device since 0.4.0.

(Not (a): the probe's timing was already handled — `ConnectionPhase.resolve` refuses to
render any gateway verdict while the local pipeline is cold. Not (b): the endpoints and
headers matched the Go client's; only the key's *value* differed.)

### The fix, in two layers

**1. One credential.** `VoiceController.gatewayKey()` resolves the key exactly as the
live path does, and all four conn-test entry points (`testConnection`,
`probeConnection`, `testConnectionAsync`, `testConnectionHuman`) go through it. The
probe and the stream can no longer disagree about *what they are sending*.

**2. The live channel outranks the probe.** Per the spec's "when in doubt, defer to the
live stream's actual observed state": `ConnectionPhase.reconcile(probe, live)` is the
new gate, and it is pure/unit-proven.

| probe says | live channel observed | reported |
|---|---|---|
| `AUTH` | `AUTHORIZED` (a real stream delivered events) | `OK` — the probe is overruled |
| `AUTH` | `UNKNOWN` / `REJECTED` | `AUTH` — a real auth failure still says auth |
| `OK` | `REJECTED` (a live turn was genuinely rejected) | `AUTH` — the probe can't hide it |
| anything else | anything | unchanged |

`Live` is observed by the turn engine itself and nowhere else: real SSE events off the
wire (or a clean `done`) mark `AUTHORIZED`; a terminal error marks `REJECTED` **only**
when `ConnectionPhase.authFailure()` says it is a genuine auth rejection (401/403/
unauthorized/forbidden/invalid api key) — a timeout, a reset socket or a provider 500
says nothing about the key and moves nothing. Observations age out after
`LIVE_AUTH_TTL_MS` (10 min) so yesterday's success never underwrites a changed key.

The runtime log now carries both readings, so a future field log shows the disagreement
instead of hiding it:

```
conn-test: verdict=ok raw=auth live=authorized ping=401 stream=-1
```

### Verbose copy — three failures, three sentences
- **needs auth** — "Reached your gateway and it needs valid auth — it rejected this key.
  Re-enter the key in Settings › Entity & Connection."
- **reachable, not warm** — "Reached your gateway — it answered, but it isn't ready to
  talk yet (still warming up)…" (never mentions the key)
- **unreachable** — "Couldn't reach the gateway. Check that your network is on…"

The 0.5.1 phase machine is unchanged; the 401 is now wired into it correctly.

---

## K2 — `/compress`, a user-facing command

### K2_SITE — where it is wired, and how

- **`CompressCommand.kt`** (new, pure JVM): the command's names (`/compress`, alias
  `/compact`), the gateway directive, the mini-UI copy, and `matches()` / `card()`.
  Unit-proven off-device like `ConnectionPhase` / `EndpointRule` / `BargeGate`.
- **`MainActivity.showCommands()`** — the native command dispatcher, alongside
  `/models`, `/health`, `/new`, `/reconnect`, `/clear`, `/reset`, `/status`, `/help`.
  `/compress` is now in the sheet and routes to `compressContext()`.
- **`MainActivity.compressContext()`** — the same shape as the other native commands:
  C0 key check, status pill (`Compacting the conversation…`), work on a background
  thread, native card with the gateway's answer.
- **`MainActivity.send()`** — typing `/compress` (or `/compact`) runs the same command
  instead of vanishing into the turn as ordinary conversation.
- **`showHelpCard()`** — `/help` lists it.

**It is the gateway's compaction, not an app-side trim.** The directive goes out over
`HermesSession.turnStored(...)` — the non-streaming `/v1/responses` call that rides the
**same server-side chain as the voice turns** (`previous_response_id`, with the reply's
id chained back in `PollStreamJSON`). So the entity compacts the session the user is
actually in, and the long conversation keeps going. `/clear` stays exactly what it was:
the local transcript, nothing more. No new permission.

---

## KEEP_VERIFY — zero-touch

Files touched: `ConnectionPhase.kt`, `VoiceController.kt` (conn-test surface + the
live-auth observation inside the existing stream loop), `MainActivity.kt` (command
dispatcher + `/help` + `send()` routing), `CompressCommand.kt` (new),
`ConnectionPhaseTest.kt`, `CompressCommandTest.kt`, `build.gradle` (version).

Untouched: MotionState enum/signals, the public AvatarView API, `silenceAll`, the
stream fence, retirement, focus, BargeGate/EscapeRule, ReplySettleRule, the crash guard,
the static-transcript reveal boundary, screen-alive, the VisualStyle category system and
its Settings surface, CrawlView, SherpaTts, the VoiceController turn engine (only the
conn-test surface changed, plus three call sites that *record* what the live stream
already observed), the 13-archetype swarm.

## Gate
- `:app:testDebugUnitTest` — exit 0.
- Release build — `:app:compileReleaseKotlin` + `:app:minifyReleaseWithR8` succeed;
  `assembleRelease` then stops at `packageRelease` because the gitignored
  `keystore/release.keystore` is not present in this environment (signing input, not a
  code failure).
- versionCode 91 / versionName 0.5.2.
