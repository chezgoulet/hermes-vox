# The phone as an MCP server (#421 steps 3–5)

Presence has two ways of acting on the phone, and they deliberately do not share a wire:

| initiator | path | guarantee |
|---|---|---|
| **the caller** — "set a timer for five minutes" | `LocalActionGrammar` → `LocalActions.run` | instant, no round trip; the mind learns about it from the presence context on the next turn (D32) |
| **the mind** — "I'll remind you in an hour" | MCP `tools/call` on this server → `LocalActions.phone` | a request and a **result**; the mind knows whether it happened |

This document is the second one: what the phone exposes, how the agent is asked to add it, what
happens when it cannot, and what the manifest actually needs. The setup turn changes the agent's own
configuration; the manual block remains for an agent that cannot.


---

## Setting it up: the app asks your agent, not you

The setup path is a turn, not a block to paste. **Settings → About & Diagnostics → Phone as an MCP
server → Set up with my agent** composes one ordinary turn and sends it on the active profile's client
— its url, key and declared scope, from the same live prefs Settings' connection test builds from.
The sheet resolves that connection at the moment of the press (`PhoneSetupStore.session`), and a
session object built for a different connection is never reused: a `HermesSession` keeps the address
and key it was created with, so after the connection is re-pointed the held one still names the old
gateway. That was #430 — the connection test passed while the setup turn went to the previous address
and the gateway in force logged nothing. If there is no session at that moment, one is built there;
only an unconfigured connection refuses. The agent edits its own configuration, verifies the entry,
and says what it changed. The turn is visible in the sheet behind **Show the setup turn**, and the
agent's own reply is shown beneath the state — a change to the agent's configuration is never silent.

The turn carries the server name (`vox`), the real tailnet URL (`http://<tailnet-ip>:8765/mcp`), the
tool name the model will see (`mcp__vox__phone`), the documented `mcp_servers` block to write (with
`url` and `headers`, no `command`), what the phone can and cannot do, the idempotence rule, and the
instruction to verify the entry and reload or restart the gateway if it needs that. The exact turn is
rendered by `PhoneSetup.prompt` and is pinned by `PhoneSetupTest`.

**It carries no credential** (#452). The agent authenticates with the API key it already uses for
this host — the same key this phone sends on its own turns — so the entry's header is written as the
documented environment reference (`Authorization: "Bearer ${API_SERVER_KEY}"`), which the gateway
resolves at connect time from the profile's `~/.hermes/.env`. That is why there is no pairing step:
nothing is exchanged, and nothing in the turn is a secret. The phone's MCP server accepts that key
alongside its own token (`PhoneMcpServer.authorized`), so a host that is not the one this phone talks
to can still use the token from the manual block below.

**It is idempotent by content.** The turn says that if a `vox` entry already exists, the agent must
update it in place, not add a second one. Sending the same turn twice therefore describes the same
end state; the sheet's **Ask again** is safe.

**The app verifies locally, never on the agent's word.** When the gateway connects it sends an MCP
`initialize`; the phone's own server sees it, and only then may the sheet say **Connected**. An
agent's "done" is displayed, but it cannot change the state. The proof is tied to the credential in
force: rotating the token clears it.

### The state machine

| state | what the sheet says | what the user can do |
|---|---|---|
| **not set up** | *Not set up yet* | **Set up with my agent** |
| **waiting** | *Waiting for your agent to connect*, with the agent's reply beneath | **Ask again**; start a call, because the listener is only up while a call is |
| **connected** | *Connected*, plus *Verified by an MCP initialize …* and the reply | **Ask again** (updates in place) |
| **unreachable** | the cause, in its own words: *No connection configured*, *Key rejected*, *Your agent did not answer: …*, or *Turn failed: …* | Fix what is named — the connection in Entity & Connection, the profile's key, the gateway — then **Try again** |
| **refused** | *Your agent could not edit its configuration*, with its own explanation | Fix its file access and **Try again**, or use the manual fallback |
| **timed out** | *Your agent was asked, but nothing connected in time* | **Try again**, or **Rotate token** and ask once more |

A failure names what happened, never one line for every case: a local misconfiguration must not read
as a network outage. The four are `no connection configured`, `key rejected`, `agent did not answer`
(transport, timeout, or an empty answer) and `turn failed: <the gateway's own reason>`
(`PhoneSetupSend`). The line is persisted with the record, so it survives reopening the sheet.

A timeout is three minutes. A late handshake still wins: if the agent connects after the sheet has
shown the timeout, the phone says Connected because the handshake is real. The reply stays readable
in every state, including the failures.

### The manual fallback

The config block still exists for an agent that cannot edit its own configuration, and for a host
that is *not* the one this phone talks to. It is behind **Show the manual config block**, labelled as
the fallback, and is never the default path. It is the one place that carries the phone's own token:
that credential is what authenticates a client which does not hold this connection's API key. The
token is created by the setup path (or by opening this disclosure); the block is generated by
`PhoneMcpConfig.snippet`, the same renderer the snippet below is checked against.

---

## What the phone exposes

**One tool, `phone`, with an action enum.** Not eight tools: the schema rides on every turn the
gateway offers it on, and a single tool with a required `action` is cheaper and harder for a model to
misuse than `phone_timer` / `phone_alarm` / `phone_dnd` / … (#421, third comment).

**The enum is a function of the owner's grants** ([#429] step 1). A capability group that is Off is
absent from it, so the model is not asked to resist a temptation it should never have had. This
section describes the actions that exist; the next section describes who may see them.
`docs/MCP-SURFACE.md` is the full contract for every action in every group — the built ones and the
ones declared for later lanes — with each action's arguments, result fields, undo and OS needs.

| action | tier | arguments | result (success) |
|---|---|---|---|
| `timer` | 0 | `duration_ms` (1000 – 86 400 000) | `id`, `remaining_ms`, `duration_ms`, `undo` |
| `alarm` | 0 | `at` (`"07:30"`, 24-hour) | `id`, `at`, `in_ms`, `undo` |
| `dnd` | 0 | `on` (bool) | `id`, `on`, `previous`, `undo` |
| `volume` | 0 | `level` (0–100) **or** `direction` (`up`/`down`) | `id`, `level`, `previous`, `undo` |
| `note` | 1 | `text` (≤ 500 chars) | `id`, `text`, `reported_next_turn`, `undo` |
| `play` / `pause` | 0 | – | `dispatched`, `note` |
| `skip` | 0 | `direction` (`next` default, or `previous`) | `direction`, `dispatched`, `note` |
| `now_playing` | 0 | – | `known`, `playing`, `package`, `title`, `note` when `known` is false |
| `state` | 0 | – | battery, charging, screen, network, tailnet, storage, volume, dnd, zone and time; each `null` when unreadable |
| `clip` | 0 | `text` (≤ 2 000 chars) | `chars` (the length only) |
| `clip_read` | 0 | – | `text`, `chars` when the platform allows the read; `unavailable` when it does not |
| `file_list` | 1 | optional `folder`, `path` (relative, `""` = the folder), `depth` (1–5, default 1), `pattern` (a name glob on files), `offset`/`limit` (a page, default 200, max 1000) | `folder`, `path`, `depth`, `count`, `has_more`, `next_offset`, `entries` (`name`, `path`, `kind`, `bytes`, `modified`, `mime`) |
| `file_read` | 1 | `name`, optional `folder` (`Downloads` or `Documents`), optional `offset`/`length` for a byte window | `name`, `folder`, `bytes`, `chars`, `text` (text only; `too_big` above the cap without a window); a window adds `offset`, `length`, `truncated`, with `bytes` the file's **total** |
| `file_offer` | 2 | `name`, optional `folder`; `approval` on the second call | `name`, `folder`, `mime`, `bytes`, `sha256`, `url`, `expires_at` |
| `agenda` | 1 | – | `count`, `events` (`start`, `end`, `title`, `calendar`, `description`, `location`, `attendees` — names only) |
| `event_create` | 1 | `title`, `start` (local ISO-8601), `duration_min` (≥ 1), optional `calendar` | `id`, `title`, `start`, `end`, `duration_min`, `start_ms`, `end_ms`, `calendar`, `undo` |
| `undo` | 0 | `id` | `undone`, `state` (`cancelled` / `restored`) |
| `status` | 0 | – | `offered`, `tiers`, `grants`, `running`, `notes`, `registry`, `dnd`, `volume` |

`play`, `pause` and `skip` dispatch a platform media key to whichever session is active: no
permission. `dispatched` means the audio service took the key, never that playback changed.
`now_playing` reports the cheap `isMusicActive` reading, and — when the owner has granted notification
access in the sheet — the active session's real package and title. Without that grant it answers
`known:false` with a sentence saying what it cannot see; a title is never invented. The sheet's *Now
playing access* row opens Android's own notification-access screen, the same shape as the Do Not
Disturb row.

`clip` writes the clipboard from wherever the call is — foreground, background or picture-in-picture
— because Android restricts clipboard **reads**, not writes; a platform refusal is
`reason:"unavailable"` with the platform's own reason. `clip_read` returns the text **when Android
allows the read**: a backgrounded app gets no clipboard, and an empty clip is no answer. Both refuse
with `reason:"unavailable"` and the platform's own outcome; when Vox is not focused, the sentence
names that as the **likely** cause of the missing clip — never empty text presented as the clip.

`status` is the discovery action: it answers "what can you do, and what is running right now" from
the **same registry** the presence context block reports (`local_actions:`), so the mind never has to
guess and the two can never disagree. A timer there carries its id and `remaining_ms`; the registry
line carries the compact `timer(remaining=3m12s)` form.

`undo` and `status` belong to no capability group, and are always offered. `undo` can only reverse
something the owner already allowed, and `status` only reports; a phone whose grants are all Off must
still be able to say so.

---

## Grants: what the agent may try (#429)

The registry is **one object**, `PhoneGrants`, and the schema, the Phone tools screen and the audit
all read it. There is no second list to drift.

| group | tier | actions today | what it lets the agent do |
|---|---|---|---|
| Timers and alarms | 0 | `timer`, `alarm` | set, cancel and check timers and alarms |
| Do not disturb and volume | 0 | `dnd`, `volume` | turn Do Not disturb on/off, change media volume |
| Reminders and notes | 1 | `note` | keep a short note and report it back |
| Media | 0 | `play`, `pause`, `skip`, `now_playing` | play, pause, skip, ask what is playing |
| Device state | 0 | `state` | read battery, charging, connectivity, screen, storage |
| Clipboard | 0 | `clip`, `clip_read` | write to the clipboard, and read it when Android allows |
| Files | 1 | `file_list`, `file_read`, `file_offer` | list what is in the folder you granted, read a named file in it (whole or a window), offer a file to the agent (Tier 2, with a card) |
| Calendar | 1 | `agenda`, `event_create` | read today's events, create an event (undoable) |
| Notifications | 0 | `notifications`, `notification_read` | read counts per app while a call is open, then one notification's content by id |
| Messages | 0 | `message` | compose a text and open the messaging app; you send it |
| Calls | 0 | `dial` | open the dialer with a number ready; you make the call |
| Apps | 0 | `launch`, `open`, `uninstall`; `install`\* | open an app or a link; removing an app needs its own card |
| Contacts | 0 | `contact` | look up a contact's number by name |

`*` A planned action: the group and its refusal exist, the capability does not ship yet. A name in
this column is **never advertised**; a direct call is refused as `not_granted` (group Off) or
`approval-required` (group Ask) rather than executed. The empty action column is the honest one for
a group whose capability lands in a later lane.

### The three states

**Off — the default for every group.** The group's actions are absent from the tool's `enum`, and a
call that still names one returns `ok:false, reason:"not_granted"`. The model cannot call what it
cannot see; if it tries anyway, the refusal is a result it can read and repeat, not a transport
error.

**Ask.** The actions are advertised, and every use is a decision rather than a run. A Tier 1 group at Ask
runs and is reported; a Tier 0 group waits for a card. On this wire an Ask call that needs a card
returns `ok:false, reason:"approval-required"` and performs nothing. `uninstall` and `file_offer`
are the Tier 2 actions with a two-step card on this phone; the rest stay visible refusals — visible
to the model (the action is offered) and to the owner (the refusal is in Recent actions).

**Allow.** The action runs immediately and is reported. **Only Tier 0 may be Allow** — reversible and
private. Tier 1 stops at Ask because the owner sees its result and should stay in the loop; Tier 2
stops at Ask because every use is a card of its own. A stored Allow against a Tier 1/2 group is read
back as Ask; a grant that cannot be given is not a grant.

**There is no allow-all.** The one bulk shortcut the design permits is *Allow all instant actions*,
and it touches Tier 0 groups alone. (The sheet does not need to show it for the promise to hold.)

**The grant is read at call time.** `PhoneGrantStore.source` consults SharedPreferences on every
`tools/call` and every `tools/list`; nothing is cached for the life of the session. Turning a group
Off in the sheet therefore removes it from the advertised `enum` and refuses the next call — even
mid-call, which is the only kind of revocation worth the name.

### What the owner sees

The Phone tools sheet lists the thirteen groups by tier, each with its one-line description and its
Off / Ask / Allow control (Allow only where the tier permits it). Files and Notifications carry one
sentence of plain consequence where the ability is not obvious — "your agent could read a file in
Downloads when you approve it". Messages and Calls do not need one: nothing leaves the phone except
through an app the owner already uses, and the owner still presses send or call.

**Recent actions** is the counter-claim: time, action, summarised arguments, outcome, and an Undo
where the action carries one. It records **refusals as well as successes**, because "my agent says
it tried" is a claim the owner has to be able to check. Arguments are summarised, never copied: a
note's text is stored as its length, so the list can be shown and attached to a report without
carrying the caller's words. Tapping an undoable row calls the same `undo` the agent would, and the
result says whether it really happened.

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
| `not_granted` | the capability group is Off; the action is not offered and was not performed |
| `permission` | the phone does not hold a permission the action needs (Do Not Disturb only, today) |
| `unavailable` | the action was recognised and the platform refused or failed |
| `expired` | no undo is registered for that id any more (it fired, was superseded, or was never this phone's) |
| `invalid-arguments` | the arguments do not describe a runnable action |
| `unknown-action` | not a phone action; `detail` lists what is on offer |
| `approval-required` | the group is Ask or the action is Tier 2: nothing was done, and the owner's approval owns it |

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
| `event_create` | deletes the event it created | until the event starts, or ten minutes |
| `file_list` / `file_read` / `file_offer` | — | listing, reading or offering changes nothing on the phone, so there is nothing to reverse |

`undo` of an id with no live undo is `ok:false, reason:"expired"`. It never guesses.

### Tiers

The tier is a property of the capability, not of the session. It decides whether Allow is even
offered, and it does not change with the grant.

| tier | actions | what the phone does | states |
|---|---|---|---|
| **0 — instant** | timer, alarm, dnd, volume, status, undo | executes now, reversible, private | Off / Ask / **Allow** |
| **1 — reported** | note, file_list, file_read, agenda, event_create | executes now when the group is on, reported in the next turn's presence context, undoable where the row says | Off / Ask |
| **2 — approval** | uninstall, file_offer, install, delete, purchase … | **never executes on the first call.** `uninstall` and `file_offer` use the phone's two-step card handshake; the rest answer `approval-required` and nothing happens | Off / Ask |

Tier 2 waits for a card on purpose. An MCP call carries no run id, so the phone cannot mint the
gateway's `core/Approval.kt` card; instead `uninstall` and `file_offer` use the phone's own two-step
handshake (`docs/MCP-SURFACE.md`, "Tier 2 — approval"), and every other Tier 2 name is refused.
The card's notification is private and a yes needs an unlocked device. An owner who wants *every*
phone call behind the gateway's card can have that without any code change:

```yaml
    trust: untrusted    # every write-capable tool call asks through the standard approval surface
```

That is the same card as any other tool's; the phone does not grow a second one.

---

## The gateway config (the manual fallback)

This is the block the disclosure shows, with the live address and token filled in. It is what the
app would otherwise ask the agent to write; the setup path above is the normal one.

```yaml
mcp_servers:
  vox:
    url: "http://100.101.102.103:8765/mcp"
    headers:
      Authorization: "Bearer <the token from the phone>"
    connect_timeout: 5
    timeout: 30
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
- `timeout: 30` is the documented per-server tool-call timeout (the MCP config reference's `timeout`
  key). `tool_timeout` is not a server key: an unknown key is ignored, so a block that names it reads
  as though the timeout were set when nothing was.
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
- **Rotating** it is the sheet's **Rotate token**: a fresh value is written, the live server reads
  it on the next request, and the old credential is refused immediately. Rotation also clears the
  recorded handshake, because a connection proved with the old token is not evidence for the new one;
  the sheet asks the agent again with the new token. Clearing the app's data (or uninstalling) has the
  same effect at the next launch.
- It is not derived from the Hermes `API_SERVER_KEY`, and rotating it does not change that key. The
  server does accept the active connection's API key as a *second* bearer (#452) — the key the phone
  already sends on its turns — so the token is the credential for a host this phone does not talk to,
  not the only way in (see the setup section above).
- Comparison for both credentials is `PhoneMcpToken.matches`: constant-time, and both comparisons run
  on every request, so a refusal never says which credential was tried.

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

## Permissions

Every manifest permission the phone's actions rely on, one line each. The point of this table is
that the manifest and the code agree; `PhoneMcpWiringTest` pins the parts that can be checked
off-device.

| permission | why it is declared | where the code uses it |
|---|---|---|
| `INTERNET` | the MCP listener answers on the tailnet; no socket without it | `PhoneMcpServer` |
| `ACCESS_NETWORK_STATE` | the app reads whether a network is usable before a model download and in the voice turn's context; not needed by the MCP listener itself | `ModelDownloader`, `VoiceController` |
| `ACCESS_NOTIFICATION_POLICY` | **Do Not Disturb only.** This is a *special* access: the user grants it on Android's own screen, not in a runtime dialog. The sheet shows whether it is granted and opens `ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS`; `dnd` refuses with `reason:"permission"` when it is missing | `LocalActions.dndGranted`, `LocalActions.setDnd`, `PhoneToolsSheet` |
| `POST_NOTIFICATIONS` | the timer/alarm notification on Android 13+. The ringtone is the signal that matters, so a denial is logged and the timer still rings | `LocalActions.fire` |
| — (none new) | `media`, `device-state` and `clipboard` add **no** runtime permission: media keys, battery, screen, transport, storage, volume, the clock and a clipboard read or write are all available with what is already declared. `now_playing`'s package and title come from **notification-listener special access**, granted by the owner on Android's own screen (the sheet offers it); without it the result is `known:false`, not a refusal from this app. Do Not Disturb is the one field `state` reports as `null` without its special access | `LocalActions.mediaKey`, `MediaListen`, `LocalActions.deviceState`, `LocalActions.clip`, `LocalActions.readClip` |
| `MODIFY_AUDIO_SETTINGS` | the media stream: `setStreamVolume` and `adjustStreamVolume` are what `volume` changes. It is a normal permission, granted at install | `LocalActions.setVolume`, `LocalActions.adjustVolume` |
| `READ_CALENDAR`, `WRITE_CALENDAR` | the calendar group: `agenda` needs `READ_CALENDAR` alone; `event_create` needs write, and read to find a writable calendar. Both are runtime permissions, asked when Calendar is turned on; a missing one is a `permission` refusal naming Android's exact string, and denying write does not take the read away | `PhoneCalendarAccess`, `PhoneToolsSheet` |
| `READ_CONTACTS` | the contacts group: a runtime permission asked when Contacts is turned on; a missing one is a `permission` refusal naming this exact string. The lookup uses `ContactsContract` only and returns display names and numbers with labels — never notes, addresses, photos or email | `ContactsAccess`, `PhoneToolsSheet` |
| — (notification-listener special access) | the notifications group: **not** a runtime dialog and **not** `POST_NOTIFICATIONS`. The owner enables the app in Android's notification-access screen; the sheet shows whether it landed and opens that screen. The system grant is permanent, but the app's board attaches at call start and detaches at call end, so a read outside a call is `unavailable` | `MediaListenerService`, `PhoneNotifications`, `PhoneToolsSheet` |
| — (none for files) | `files` declares **no** storage permission, and nothing requests `MANAGE_EXTERNAL_STORAGE`: the Storage Access Framework is the deliberate default, and the owner grants one folder through `ACTION_OPEN_DOCUMENT_TREE`, whose children `file_list` may list and from which only a named file is read. A future "all files" opt-in would use Android's own all-files access screen, with its own warning and its own grant — the owner's choice, not a capability this app withholds on their behalf | `PhoneFileAccess` |
| `VIBRATE` | the haptic half of the app's sound cues (not a phone action) | `SoundCuePlayer` |
| `WAKE_LOCK` | the voice call and model downloads (not a phone action; the MCP listener has no service of its own) | `MainActivity`, `ModelDownloadService` |
| `RECORD_AUDIO` | the voice line (not a phone action) | `MainActivity`, `VoiceController` |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE` | the call's microphone service, which is also the MCP listener's lifetime | `VoiceService` |
| `FOREGROUND_SERVICE_DATA_SYNC` | model downloads continuing with the Models screen closed (not a phone action) | `ModelDownloadService` |

**Timer and alarm need no exact-alarm permission.** They use `AlarmManager.setExact(…,
OnAlarmListener, handler)`, the listener form the exact-alarm restriction exempts. The manifest
therefore declares **no** `SCHEDULE_EXACT_ALARM` and **no** `USE_EXACT_ALARM`, and the code does not
ask for "Alarms & reminders" access.

**Why not the Clock app's intents.** They were the first choice, and they are the right choice for a
fire-and-forget alarm: no permission, and the alarm lands in the app the user expects. They cannot
carry this tool's contract. `ACTION_SET_TIMER` starts a timer we cannot read back or cancel;
`ACTION_SET_ALARM` opens another app's UI and reports no outcome. The phone's promise is a verified
result plus an undo, and a timer that rings in this process is the honest way to keep it. The cost is
plain: an in-app timer or alarm rings while the app is alive, which during a call it is; one that
must outlive the process is not this lane.

**Server lifetime.** The listener is up exactly while a call is: `VoiceController.start` opens it and
the hangup closes it. It has no foreground service and needs no new service type. The sheet says this
plainly, and the cost is in "When the phone is offline" below.

---

## Security reasoning

**Why a tailnet, not the LAN.** The server binds exactly one address: the one `Tailnet.pick` chooses
(`100.64.0.0/10`, or the Tailscale IPv6 ULA). `PhoneMcpServer.start` refuses an any-local address
itself, so a future caller cannot turn it into `0.0.0.0` by accident. The same rule the file-offer
server follows (#B), for the same reason: an unauthenticated phone on a café network is a remote
control for someone else's prank.

**Why a bearer token on top.** The tailnet says *which network*; the token says *which client*. A
tailnet can carry other devices, and the phone's actions are private — Do Not Disturb, volume, a
note about a plumber. Every request needs `Authorization: Bearer <credential>` — the phone's own
token, or the API key of the connection it talks to (#452) — and everything else is 401 before a byte
of body is parsed. Both are compared in constant time and both comparisons always run, so the 401
carries no hint of which one was presented.

**Why the tiers are the third layer.** Neither of the first two says *what* an authenticated client
may do. The tier table does: Tier 0 is reversible and private, Tier 1 is reversible and visible, and
Tier 2 is refused because it leaves the phone or cannot be undone. The phone will not send a message
or place a call for the mind however it authenticates.

**What is deliberately absent.** No MCP resources and no resource listing, no second route, no SSE
stream, no server-initiated messages, no session state (`Mcp-Session-Id`): each is a surface with no
caller on this side. The `file_list` action is a phone capability, not an MCP resource listing.
There is also no verbose log of the arguments — a note's text is the caller's, and it belongs in the
note, not in a log that gets attached to bug reports.

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

**Verified against the client this has to satisfy**, not only against the spec: the `mcp` SDK the
Hermes runtime ships (`mcp/client/streamable_http.py`, protocol `2025-11-25` as of this build)
connects to this server, negotiates the version, lists `phone`, gets `ok:true` from `status`, gets
`approval-required` from `message`, and gets a `McpError` from a tool it does not serve. That run is
the shape check: if the phone ever needs something this endpoint cannot say, the honest move is to
report the shape, not to grow a second protocol.

---

## What a device pass should check

The checklist for the actions added with the grants (`media`, `device-state`, `clipboard`) is in
`docs/MCP-SURFACE.md`, under "What a device pass should show".

1. **Start a call with Tailscale connected.** The log shows `event=phone-mcp start url=http://100.x…:8765/mcp`.
   Hang up: `event=phone-mcp stop served=N`.
2. **Point a scratch client at the URL with either credential** (`curl` the `initialize` POST, or
   `mcp-server-fetch`-style tooling): the phone's own token from the manual block, or the gateway's
   `API_SERVER_KEY`. With neither: `401`. With either: `tools/list` returns `phone`. **Run setup and
   do not copy anything:** the agent's own connection should complete with the key it already holds,
   and the sheet should reach *Connected* on its handshake.
3. **With every group Off (a fresh install), `tools/list` offers `undo` and `status` only.** A
   `timer` call returns `ok:false, reason:"not_granted"`; nothing is armed.
4. **Grant Timers and alarms = Ask.** `tools/list` now includes `timer`; a timer call returns
   `ok:false, reason:"approval-required"` and nothing is armed. Set the group to **Allow**: the next
   call returns `ok:true` with an id and the timer rings.
5. **Revoke mid-call.** While the call is live, set Timers and alarms back to Off. The **next**
   `tools/list` no longer lists `timer`; the next `timer` call is `not_granted`. No restart, no
   reconnect.
6. **`timer` from the client** → `ok:true` with an id; the timer rings; `status` no longer lists it;
   `undo` of that id afterwards says `expired`.
7. **`undo` while it is running** → the timer is cancelled and does not ring.
8. **`dnd` on a phone that has never granted policy access** → `ok:false, reason:"permission"`, and
   the Do Not Disturb state is unchanged.
9. **`volume`** → the reported `level` matches the phone's own slider afterwards.
10. **`note`** → the next voice turn's `local_actions:` line carries `note(id=…)`, and `undo` deletes
    it.
11. **`message`** → `not_granted` while Messages is Off, `approval-required` at Ask; at Allow the
    owner's messaging app opens on the draft and nothing is sent. **`dial`** → `not_granted` while
    Calls is Off; at Allow the dialer opens with the number, and a live Vox call is ended after the
    being says *dialling — ending our call*.
12. **Recent actions** shows every one of those calls, successes and refusals, with time, action,
    summarised arguments and outcome. A note's row shows `text=N chars`, never the note. Tap an
    undoable row: the action is reversed, or the toast says the undo expired.
13. **The sheet's groups match the wire.** Turn a group Off in the sheet, then ask the client for
    `tools/list` without leaving the call: the action is gone. Turn it on: it is back.
14. **Turn the call off** → the next tool call from the gateway fails (server parked), and the agent
    says it could not reach the phone rather than claiming the action.
15. **Turn Tailscale off** → no listener at all (`reason=no-tailnet`), and the app is otherwise
    unaffected.
16. **Run setup twice.** The second turn must update the existing `vox` entry in place; the gateway's
    MCP configuration still has exactly one `vox`. The sheet's Ask again is the path to use.
17. **Revoke Do Not Disturb access while the sheet is open.** Leave the sheet, revoke it in Android's
    special-access screen, return: the row says *Not granted* (no restart), and a `dnd` call returns
    `ok:false, reason:"permission"` with the DND state unchanged.
18. **Rotate the token.** The old token gets `401` on the next request even while the listener stays
    up; the sheet stops saying Connected until a new handshake arrives with the new token.
19. **Make the agent refuse.** Point it at a read-only config or ask it not to edit; the sheet says
    *could not edit its configuration*, shows the agent's own words, and offers the manual fallback.
20. **Confirm the manifest.** `aapt dump permissions` (or the merged manifest) shows no
    `SCHEDULE_EXACT_ALARM`, no `USE_EXACT_ALARM`, and no permission the table above does not name.
