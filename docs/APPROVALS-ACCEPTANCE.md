# Approvals on the phone — acceptance script (#282, #376)

Five acceptance cards (T41, T42b, T43, T44, T45) failed or were blocked in 0.9.1
because **no approval request ever reached the phone**: the device logged zero
`event=approval` lines in the whole run. That was not a UI defect. Two separate
things were missing, and this document supplies both:

1. **A trigger** — something that makes Hermes ask for a sign-off on a run the
   phone placed, without depending on a lucky model turn.
2. **A route that can carry the request to the app.** When this document was written the app's
   ordinary turns rode `POST /v1/responses`; on gateway 0.22.6 that route does **not** attach an
   approval notifier, so a flagged command there is resolved by `approvals.unattended_mode`
   (default `deny`) and no event is emitted at all. Only `POST /v1/runs` and streaming
   `POST /v1/chat/completions` carry `approval.request` to a client. **Since #404 the app's turns
   ride `/v1/runs` by default** (`docs/RUNS-ROUTE.md`), so this is now the normal path as well as
   the arm's; a gateway that answers the admission with 404 still falls back to responses and
   cannot raise a card.

(2) is why "three voice attempts to trigger it" could never have worked even with a
perfect trigger. (1) is why a normal turn was never going to raise one either. Both
are addressed below: the app now has a **debug-only approval arm** that places a run
on `/v1/runs` whose only purpose is to raise an approval, and the trigger is the
gateway's own dangerous-command policy — not the model's discretion.

Nothing here weakens the gateway's policy. The arm is a **harmless command that is
dangerous in shape**, and it is reachable only from the `FLAG_DEBUGGABLE` intent
harness (inert in a release build, never on a shipped surface).

---

## 1. What the arm is, and what part of it is deterministic

### 1.1 The command, and the rule it trips

```
mkdir -p /tmp/vox-approval-arm && rm -rf /tmp/vox-approval-arm
```

- **Harmless in effect.** It creates an empty directory under the gateway host's
  `/tmp` and deletes that same directory. Nothing else is touched, and running it
  twice is idempotent.
- **Deterministic in policy.** `tools/approval_detection.py` scores the command
  *before the agent may run it*, independently of any model:

  ```
  DANGEROUS_PATTERNS = [
      (r'\brm\s+(-[^\s]*\s+)*/', "delete in root path"),
      (r'\brm\s+-[^\s]*r', "recursive delete"),
      ...
  ```

  The gateway will not execute it without a human answer. This is checkable on the
  gateway host without executing anything, and it is the line to quote in the
  acceptance report:

  ```
  $ hermes approvals test -- rm -rf /tmp/vox-approval-arm
  command : rm -rf /tmp/vox-approval-arm
  env-type: local
  verdict : ask-approval  (exit 2)
  rule    : delete in root path
  detail  : matches a dangerous-command pattern; the runtime would raise an
            interactive approval prompt (pattern key: 'delete in root path')
  ```

  Exit code 2 is `ask-approval`; 0 is allow, 3 is deny. The compound form
  (`mkdir … && rm -rf …`) gives the same verdict, because detection is
  quote/`&&`-aware and only the *allowlist* shortcut is disqualified by a compound
  command.

### 1.2 What is **not** deterministic, stated plainly

Whether the agent runs the command at all is still the model's judgement. No code in
the gateway lets a client force a tool call, and this arm does not pretend otherwise.
It shrinks that judgement to the smallest step it can be:

- the trigger is **text**, not voice (the recognised-path mangling that sank the
  earlier attempts cannot happen);
- one exact command, with a path the command itself creates, so the agent cannot
  refuse it as "unresolvable";
- the path is under `/tmp` on the gateway host, so it is visible to the agent.

Consequence for reading a failure: if the card never appears, first ask **which**
half failed:

| What the log shows | What failed |
|---|---|
| `event=approval-arm-end … requested=false` | the model never ran the command (judgement, or the gateway refused the turn — see §6) |
| `event=approval-arm … status=waiting_for_approval requested=true` then a card | the policy half worked; any missing card is an app/transport defect |
| `event=approval-arm-timeout` after 3 min | the run never parked on an approval |

### 1.3 The routes (why `/v1/runs`)

Checked against the gateway running on the acceptance host (0.22.6):

| Route | Approval carry | Evidence in the gateway tree |
|---|---|---|
| `POST /v1/responses` | **no** | `_handle_responses` spawns the agent with no `approval_notify_callback` (`gateway/platforms/api_server_openai_routes.py`, the `_spawn_stream_agent(...)` call); `_presence()` in `tools/approval.py` then drops `is_ask` because no notifier is registered, and the unattended branch resolves the command from `approvals.unattended_mode` |
| `POST /v1/chat/completions` (stream) | yes | `_register_stream_approval` → `event: approval.request` frames → `POST /v1/runs/{id}/approval` |
| `POST /v1/runs` | yes | `_make_approval_notify` parks status `waiting_for_approval`; the request is readable on `GET /v1/runs/{id}`; answer via `POST /v1/runs/{id}/approval` |

So the arm places its run on `/v1/runs` and **polls the run's status** for the
request (`ApprovalArm.requestFromRunStatus`), which accepts both gateway shapes:

```
{"run_id":"run_…","status":"waiting_for_approval","execution_generation":3,
 "pending_controls":[{"kind":"approval","prompt_id":"…","execution_generation":3,
                      "command":"…","description":"…","choices":["once","deny"]}]}
```

(session authority) and the plain lane's `"approval": {...}` (the event itself).
Under session authority the answer must be exactly
`{"choice": “…”, "request_id": “…”, "execution_generation": N}` — the app already
sends that shape (`GatewayClient.answerApproval`).

### 1.4 Gateway settings a user would need — and what **not** to change

The arm needs the gateway's normal, safe policy. On the sasquatch gateway as found
(2026-11-03) that is already the case:

```yaml
approvals:
  destructive_slash_confirm: false   # irrelevant to this arm (CLI slash commands)
  # mode is unset -> "manual" at runtime (tools/approval_context.py: .get("mode","manual"))
  # timeout is unset -> 300 s
command_allowlist:
  - execute_code
  - in-place edit of Hermes config/env
  - script execution via -e/-c flag
```

- **`approvals.mode` must stay unset/`manual`.** With `smart`, an auxiliary LLM may
  auto-approve a flagged command and no card is raised; with `off`, nothing prompts.
  The arm's determinism assumes `manual`.
- **Do not add `recursive delete` / `delete in root path` to `command_allowlist`.**
  That is the one change that would make this arm silently stop prompting — and it
  would weaken the policy for real work, not just for the test.
- `approvals.timeout` (300 s) must stay **longer than the phone's own window**
  (290 s, `ApprovalGate.DEFAULT_TIMEOUT_MS`), so the phone's timeout denies before
  the gateway gives up. Shortening it to e.g. 60 s makes the gateway win the race and
  the phone's POST returns 409 — the timeout card then proves nothing.
- `approvals.unattended_mode` is irrelevant once the run is on `/v1/runs`; it is
  exactly what silently denies a flagged command on `/v1/responses`.

**This change did not touch any live host's config.** The procedure above describes
what a user would set; sasquatch's `~/.hermes/config.yaml` was read, not modified.

---

## 2. Arm A — the real gateway (the acceptance arm)

What it proves: the real gateway's policy, the real run, the real card, the real
`POST /v1/runs/{id}/approval`, the real log lines.

### 2.1 Build and install (never the Pixel's release install)

```bash
cd android && ./gradlew assembleDebug
adb -s emulator-5560 install -r app/build/outputs/apk/debug/hermes-vox-0.9.1.apk
adb -s emulator-5560 logcat -c
```

`applicationIdSuffix` builds are fine too; the point is a **debuggable** APK, because
the arm is behind `FLAG_DEBUGGABLE`.

### 2.2 Arm it

The arm is one intent extra on the existing debug harness. `url`/`key`/`model` are
applied only when present; `arm=approval` is the trigger. `arm_prompt` overrides the
command for a special case.

```bash
adb -s emulator-5560 shell am start -n com.hermesvox/.DebugHarnessActivity \
  --es url "http://<gateway-host>:8742" \
  --es key "<API_SERVER_KEY>" \
  --es model hermes-agent \
  --es arm approval
```

The phone places the run itself (`POST /v1/runs`), then polls
`GET /v1/runs/{run_id}` once a second for up to three minutes. Pre-creating the
directory on the gateway host is optional and changes nothing about the policy:

```bash
# on the gateway host, harmless and idempotent
mkdir -p /tmp/vox-approval-arm
```

### 2.3 The three answers

Do each one separately (one arm run each). The card, the answers and the timeout are
the same code paths as a real request — the arm only supplies the run.

**No, by touch**

```
adb -s emulator-5560 logcat -s HermesVox:* | grep -E "event=approval"
# event=approval-arm run=run_… status=waiting_for_approval requested=true
# event=approval-request run=run_… request=<prompt id> choices=<what the gateway offered> action=…
# event=approval decision=deny choice=deny run=run_… request=req_… cause=touch
# event=approval-sent run=run_… request=req_… choice=deny
```

**No, by voice** — the voice answer needs the call's microphone loop, so either start
a call first, or start one while the card is up (a call ending no longer withdraws an
arm request: the arm's run outlives the turn). Say "no":

```
# event=approval-voice capture=segment matched=deny
# event=approval decision=deny choice=deny run=run_… request=req_… cause=voice
# event=approval-sent run=run_… request=req_… choice=deny
```

**Not an answer, by voice** — with the card up, say something that is not a plain
answer ("I'm not sure", "where does that go"). The card must stay up, nothing is
posted, and the app says **"say approve or no"** once. Say a second non-answer: it is
not said again. Then answer normally (or tap) — the card resolves as usual.

```
# event=approval-voice capture=segment matched=false
# event=approval-prompt request=req_… reason=not-an-answer
# event=approval-voice capture=segment matched=false        (second non-answer: no prompt)
```

The prompt is never spoken over a reply that is already speaking: if the voice is busy
the device logs `event=approval-prompt-skip request=req_… reason=voice-busy` and stays
silent, and the next non-answer may prompt instead. It never approves, never denies,
and never stops the 290 s timeout. A new card (answered, replaced or timed out first)
gets its own one prompt.

**Left to time out** — do nothing for 290 s:

```
# event=approval decision=timeout choice=deny run=run_… request=req_… cause=timeout
# event=approval-sent run=run_… request=req_… choice=deny
```

In all three, the run then continues (the gateway records the deny and the agent gets
`BLOCKED: … denied by user`). Confirm the run left the waiting state:

```bash
curl -s -H "Authorization: Bearer $API_SERVER_KEY" \
  "http://<gateway-host>:8742/v1/runs/$RUN_ID" | jq .status   # running | completed | failed
```

The phone-side evidence is one line per decision. Quote it verbatim in the
acceptance report; it carries both ids so the gateway side can be matched without a
screenshot.

### 2.4 The security assertion (the part that is not a UX test)

Nothing may proceed without an answer. On this arm, "proceed" would mean the
directory was deleted without a human. Check it directly:

```bash
# on the gateway host, before arming
mkdir -p /tmp/vox-approval-arm && ls -ld /tmp/vox-approval-arm
# after answering no / timing out: the directory must still be there
ls -ld /tmp/vox-approval-arm
```

and the run's output / the agent's next step must say it was denied. An approval that
executes on timeout, or one that the app answers twice, is a **security** failure,
not a cosmetic one: `POST /v1/runs/{id}/approval` must be observed exactly once per
`request_id`, and a second tap/voice match is a local no-op (`ApprovalGate`).

---

## 3. Arm B — the scripted harness, runs lane (works today, no gateway turn)

What it proves: the phone half end-to-end (arm → card → answer → POST), with the same
wire shapes as the real gateway, when the gateway itself cannot run a turn (§6). It
does **not** prove the real policy — the harness flags nothing.

```bash
# 1. Start the harness on the host (it now serves the runs lane too)
python3 scripts/approval-harness.py --port 8765 --key harness-key \
    --arm-delay 1.5 --runs-shape authority     # or --runs-shape plain for the other lane

# 2. Point the debug APK at it and arm
adb -s emulator-5560 shell am force-stop com.hermesvox
adb -s emulator-5560 shell am start -n com.hermesvox/.DebugHarnessActivity \
  --es url "http://<host>.<tailnet>.ts.net:8765" \
  --es key harness-key --es arm approval

# 3. Answer on the phone (tap NO, say "no", or wait), then:
adb -s emulator-5560 logcat -d -s HermesVox:* | grep event=approval
```

Harness output for a deny:

```
event=arm-run run_id=run_harness_… request_id=req_… prompt='Use the terminal tool …'
event=approval-request run_id=run_harness_… request_id=req_… choices=once,session,deny shape=authority
event=approval-http run_id=run_harness_… request_id=req_… choice=deny count=1
event=run-completed run_id=run_harness_… choice=deny
```

A second POST after the decision is answered `409` and logged as
`event=approval-duplicate … status=409` — that is the harness proving the app's
exactly-once rule, not a second decision.

`--runs-shape plain` exercises the other gateway shape (`approval` on the status, no
`request_id`, so the app keys the request by its run id). Both must show the same
card.

---

## 4. Arm C — the scripted harness, SSE lane (kept from #282)

Still valid for the `/v1/responses` event path (the shape a future gateway will send
on the app's ordinary turn): start the harness **without** `--arm`, then send a text
turn with the existing harness extra instead of the arm:

```bash
adb -s emulator-5560 shell am start -n com.hermesvox/.DebugHarnessActivity \
  --es url "http://<host>.<tailnet>.ts.net:8765" --es key harness-key \
  --es text "please run the flagged action"
```

The harness emits `response.created → approval.request → (answer) →
output_text.delta → response.completed` and the phone renders the same card. This
proves the stream parser and the same three answers, but again not the real policy.

---

## 5. What each answer must produce (checklist)

| Card | Action | Device log (must be quoted) | Gateway evidence |
|---|---|---|---|
| T41 — the card appears at all | Arm (§2.2) | `event=approval-request run=… request=… choices=…` | run status `waiting_for_approval` |
| T42b — no by touch | Tap **NO** | `event=approval decision=deny choice=deny … cause=touch` | one `POST …/approval` `choice=deny`; command did not run |
| T43 — no by voice | Say "no" during a call | `event=approval decision=deny choice=deny … cause=voice` | same, one POST |
| T43b — not an answer | Say "I'm not sure" twice during a call | one `event=approval-prompt request=… reason=not-an-answer`, no `event=approval decision=` | no POST; card still up |
| T44 — timeout denies | Wait 290 s | `event=approval decision=timeout choice=deny … cause=timeout` | one `choice=deny` (`deny`, not `approve`) |
| T45 — nothing proceeds unanswered | The above, checked on the host | `event=approval-sent … choice=deny` | `/tmp/vox-approval-arm` still exists; agent reports the denial |

A card that shows a choice the gateway did not offer, an answer sent twice, or an
approval granted without a human answer all fail the suite. The card must render
**exactly** the gateway's list: in manual mode that is normally
`once, session, always, deny`; a smart-denied override or `allow_session=false`
narrows it to `once, deny`; `deny` is always present. The arm never asks for a scope
it was not offered.

---

## 6. State of play on the acceptance host (2026-11-03) — what still needs proving

Investigated on sasquatch against the gateway it runs (`/v1/health` → version
**0.22.6**). Recorded so the next run does not rediscover it:

1. **The trigger is proven.** `hermes approvals test -- rm -rf /tmp/vox-approval-arm`
   returns `ask-approval` / `delete in root path` (§1.1). The gateway's policy will
   stop that command. Nothing was executed to prove it.
2. **The route gap is proven by code and by probe.** A `/v1/runs` turn and a
   `/v1/responses` turn were both placed against the live gateway with harmless
   prompts. Neither reached a tool call: **every API-server turn on this host
   currently fails at the first inference** with a provider error —

   ```
   HTTP 400: Invalid 'tools[19].function.name': string does not match pattern.
   Expected a string that matches the pattern '^[a-zA-Z0-9_-]+$'
   ```

   (the index varies by route: 19 on `/v1/runs`, 21 on `/v1/responses`). This is a
   **gateway-side blocker unrelated to approvals**: it predates this work, affects
   the app's ordinary turns too, and means the real-gateway arm could not be
   exercised from here. First action for the next acceptance run:
   `hermes doctor`, then retry the arm — no approval code is involved in that error.
3. **The phone half is proven off-device.** `ApprovalArmTest` (10 cases) pins the
   wire reading for both gateway shapes, the exactly-once answer, the arm request
   surviving an unrelated turn, and the two HTTP calls against a gateway-shaped
   server; the harness's runs lane was exercised end-to-end over HTTP (§3).
4. **What still needs a device / a healthy gateway.**
   - The card rendering and the touch answer on the real gateway (`/v1/runs`).
   - The voice answer inside a live call (`cause=voice`).
   - The 290 s timeout with the screen off, and the app's deny landing before the
     gateway's 300 s timeout.
   - The security check in §2.4 on the host, with the log lines quoted.
   - The five cards must be signed off with those lines attached; this document is
     the procedure, not the evidence.

If the gateway can emit `approval.request` and the phone still cannot see it on a
live `/v1/runs` run, that is a transport bug in the runs arm (`ApprovalArm` /
`VoiceController.watchArmRun`) and should be filed as one.

---

## 7. Where the code is

| Piece | File |
|---|---|
| The arm's prompt, both wire shapes, the status reader | `android/app/src/main/java/com/hermesvox/core/ApprovalArm.kt` |
| `POST /v1/runs`, `GET /v1/runs/{id}` | `android/app/src/main/java/com/hermesvox/core/GatewayClient.kt` |
| Session entry points (internal, arm only) | `android/app/src/main/java/com/hermesvox/core/HermesSession.kt` |
| The poller, the same card/voice/timeout path, the turn-end guard | `android/app/src/main/java/com/hermesvox/VoiceController.kt` (`armApproval`, `closeApprovals`) |
| The FLAG_DEBUGGABLE extra `arm=approval` | `android/app/src/main/java/com/hermesvox/MainActivity.kt` (`handleDebugHarness`, `armApproval`) |
| Gate: `closeAllExcept` (a turn must not withdraw an arm's request) | `android/app/src/main/java/com/hermesvox/core/Approval.kt` |
| Scripted gateway, both lanes | `scripts/approval-harness.py` (`--arm-delay`, `--runs-shape`) |
| Tests | `android/app/src/test/java/com/hermesvox/core/ApprovalArmTest.kt` |
