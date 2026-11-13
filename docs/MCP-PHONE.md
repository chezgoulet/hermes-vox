# Your agent acting on your phone

**Phone tools** lets the **agent** act on the Android phone this conversation is happening on.
The agent is the Hermes instance on your **gateway** (the Hermes API server) that answers the
voice turns; the phone is Vox. When Phone tools is on, Vox runs a small **MCP** (Model Context
Protocol) server on the phone's **tailnet** address — the private Tailscale network only your own
devices can reach — and offers the agent one tool called `phone`. The agent calls it with an
action name and that action's arguments. The phone performs the action or refuses it, and the
result says which.

A **call** is one live voice conversation between Vox and the agent.
The listener is up while a call is, and while a setup attempt holds its window open. Everything
else — the grants, the approval cards, the audit — belongs to the phone and outlives a single call.

This document is for the owner who has never set Phone tools up, and for the agent that must use
it. `docs/MCP-SURFACE.md` is the wire contract: every action, argument, result field and reason
string in one place.

## What you need

- Vox on an Android phone, with **Phone tools** selected in **Alpha features** (Settings). Without
  it there is no token, no listener and no configuration to hand over.
- **Tailscale** running on the phone. The listener binds one tailnet address and nothing else; with
  no address it does not start and the log says `reason=no-tailnet`.
- A gateway configured under **Entity & Connection**: an address with a scheme and a key. The setup
  turn is sent on that connection, and an unconfigured one is refused with its own line.
- The agent running on that gateway, able to edit its own MCP configuration with the tools it
  already has (`hermes config set`, or `hermes mcp add` as a fallback).
- A live call for the agent to reach the phone outside setup. The listener has no daemon of its own.

## How it is set up

### The one button, and the listening window

Open **Settings → Phone tools**. Until the agent knows about the phone, the sheet draws one state
first: *Your agent does not know about this phone yet*, with the sentence that until it is set up
the agent cannot see the phone at all. The one action is **Set this up with my agent**.

Before you press it, the sheet says what the press does: your agent will be given this phone's
address and its token, and the press opens this phone's listener **for as long as the attempt runs**.
The listener is opened before the turn leaves, so the agent can reach it whenever it gets there. If
it cannot be opened — no tailnet address, port taken, Phone tools off — nothing is sent and the
sheet says *This phone could not be made reachable*.

The setup path is a turn, not a block to paste. One press sends one ordinary chat turn using
the active profile's client, resolved at the moment of the press, so a connection that was
re-pointed is the one used. The turn is stored in the conversation like any other; nothing
restarts.

The window has no clock. It stays open while the run is alive, however long the agent takes, and
for **20 seconds** after the run ends in case the gateway connects just then. It closes at once
when the handshake arrives, or when you press **Stop this attempt** or close the sheet. A live call
holds the listener past the window, and a hangup during the attempt does not close it.

### What the turn contains

The turn is `PhoneSetup.prompt`, and its text is pinned by a test. It names:

- the server name `vox`, the real tailnet URL `http://<tailnet-address>:8765/mcp`, and the tool
  name the model will see, `mcp__vox__phone`;
- the phone's `Token:`;
- the three documented commands that write the entry, in this order:
  `hermes config set mcp_servers.vox.url …`,
  `hermes config set mcp_servers.vox.headers.Authorization "Bearer …"`, and
  `hermes config set mcp_servers.vox.enabled true`;
- `hermes mcp add` only as an alternative, with its limitation: it asks which tools to enable and
  whether to save, and a tool call has no stdin;
- the documented per-server `lazy` key: `hermes config set mcp_servers.vox.lazy true`;
- what the phone can and cannot do, and that only granted groups are offered;
- the idempotence rule — update an existing `vox` entry in place, never add a second;
- that this phone's listener is open for the attempt;
- that the agent must verify with `hermes mcp list` (or `hermes mcp test vox`) and name the URL it
  saved — a reply without that URL is not a completed setup;
- that **no restart is needed and none is wanted**, because a running gateway reconciles its own MCP configuration.

It is idempotent by content. Sending the same turn twice therefore describes the same end state,
and **Ask again** is safe.

`hermes config set` writes nested keys, creates the entry without `--force`, and a running gateway
reconciles the change by itself. A handshake proves the phone was reached; it does not prove the
entry was saved, so the turn requires the named URL in the agent's reply. `hermes mcp add` verifies
before it saves and refuses a server it cannot reach, but it prompts, and a tool call has no stdin.

### The states the sheet shows

| state | what the sheet says | what you can do |
|---|---|---|
| **not set up** | *Your agent does not know about this phone yet* — the agent cannot see the phone, and any answer it gives about it is honest but unhelpful | **Set this up with my agent**, or copy the turn and hand it over |
| **adding** | *Your agent is working on this*, with **What your agent did**: one line per tool call, appended in arrival order | Wait, or **Stop this attempt** |
| **answered** | *Your agent has answered* — the run ended without a reported failure, and the 20-second grace for the handshake is running | Wait for the grace, or **Stop this attempt** |
| **asked** | *Asked — your agent is working* — a turn was handed over in an earlier process and no handshake has arrived | **Ask again**, which opens a window and sends the turn again |
| **connected** | *Connected*, with the handshake line and the agent's reply | **Ask again** (updates the entry in place) |
| **failed** | the cause in its own words, the step it reached, and the agent's last words | Read the step, then copy the turn; **Try again** clears the failed attempt and returns to the one button |

The not-set-up, waiting and connected headings are separate constants and may not share wording;
only the handshake's state may use the word *connected*.

The app verifies locally, never on the agent's word. **Connected** is set only when the phone's own
MCP server has seen an authenticated `initialize` made with the token in force. An agent's "done"
is shown beneath the state but cannot change it. Rotating the token clears the proof.

A failure names itself. The send has four named failures: *no connection configured*, *key
rejected*, *your agent did not answer*, and *turn failed: <the gateway's own reason>*. The
one-button flow adds the listener that could not open, an add the agent reports as failed, and a
run that ended without the handshake. A failed attempt stays failed; the next press is a new
attempt, and the copyable turn stays on screen.

### The lazy entry

The entry the agent writes carries `lazy: true`. A lazy entry is registered from its schema cache
on disk, so `mcp__vox__phone` is in the model's hands whether or not the phone has been reached
recently; the connection is made when the tool is used. A successful connection to the phone fills
the cache. A missing or stale cache falls back to the ordinary eager connect at startup.

### The manual fallback

The setup turn is the normal path. Behind **Show the manual config block** the sheet also renders
the same entry as YAML for an agent that cannot edit its own configuration, or for a host that is
not the one this phone talks to. The block is generated by `PhoneMcpConfig.snippet` and carries the
live address and token:

```yaml
mcp_servers:
  vox:
    url: "http://100.101.102.103:8765/mcp"
    headers:
      Authorization: "Bearer <the token from the phone>"
    connect_timeout: 5
    timeout: 30
    lazy: true   # register the tool from its schema; connect when it is used
    trust: full   # set `untrusted` to require an approval card for every call
    tools:
      include: [phone]
```

- `url` is the phone's tailnet address, the port the app binds (`8765`), and the one path it
  answers (`/mcp`).
- `connect_timeout: 5` fails fast when the phone is idle, rather than holding a turn open for the
  gateway's default 60 seconds.
- `timeout: 30` is the per-server tool-call timeout. The key is `timeout`; `tool_timeout` is not a
  server key and is ignored.
- `trust: full` leaves the phone's grants in charge. `trust: untrusted` puts the gateway's own
  approval card in front of every call, so an Allow group still asks at the gateway.
- The token may come from the environment (`"Bearer ${VOX_PHONE_TOKEN}"`) when the config file is
  synced or shared.

`docs/MCP-SURFACE.md` describes the transport this block points at.

### The token

- It is **128 random bits**, 32 lowercase hex characters, generated once and stable across
  launches.
- It is stored encrypted with the Android Keystore. If encryption fails, it lives in memory for
  that launch only, the log says `event=phone-mcp token-store=failed session-only=true`, and the
  owner copies the configuration again next launch.
- It is never logged, never audited and never written to a preference. The setup turn is kept in
  memory for the process that sent it, and an agent reply that echoes the token is scrubbed before
  it is stored.
- The server also accepts the active connection's API key as a second bearer. Comparison is
  constant-time and both comparisons always run, so a refusal never says which credential was
  tried.
- **Rotate token** (inside the manual block) writes a new value, makes the old one fail on its next
  request, and clears the recorded handshake, because a connection proved with the old token is not
  evidence for the new one.

## What the agent can do

### One tool, thirteen groups

There is **one** tool, `phone`, with an action enum. The enum is built from the owner's grants each
time the agent lists tools: a capability group that is Off contributes no action name. The two
actions that are not a capability — `undo` and `status` — are always offered, because a phone whose
grants are all Off must still be able to say so.

The throttle is deliberate: the schema rides on every voice turn the gateway offers the tool on,
and one tool with a required `action` is cheaper and harder to misuse than a dozen near-synonyms.

| group | tier | actions today | what it lets the agent do |
|---|---|---|---|
| Timers and alarms | 0 | `timer`, `alarm` | set and check a countdown or a wall-clock alarm |
| Do not disturb and volume | 0 | `dnd`, `volume` | turn Do Not Disturb on or off, change the media volume |
| Reminders and notes | 1 | `note` | keep a short note and report it back |
| Media | 0 | `play`, `pause`, `skip`, `now_playing` | send transport keys and ask what the phone will admit to playing |
| Device state | 0 | `state` | read battery, charging, screen, network, storage, volume, Do Not Disturb and the clock |
| Clipboard | 0 | `clip`, `clip_read` | write text to the clipboard, and read it when Android allows |
| Files | 1 | `file_list`, `file_read`, `file_offer` | list and read inside the one folder you granted, and offer a file to the agent |
| Calendar | 1 | `agenda`, `event_create` | read today's events and create one, undoable |
| Notifications | 0 | `notifications`, `notification_read`, `notification_dismiss` | count notifications per app during a call, read one by id, dismiss one or an app's |
| Messages | 0 | `message` | compose a text and hand it to your messaging app; you send it |
| Calls | 0 | `dial` | open the dialer with a number ready; you make the call |
| Apps | 0 | `apps`, `launch`, `open`, `uninstall` | list launchable apps, open an app or a link, or remove an app |
| Contacts | 0 | `contact` | look up a contact's number by name |

Two actions carry their own tier inside a group, because their consequence differs from their
neighbours: `uninstall` is tier 2 in the instant Apps group, and `notification_dismiss` is tier 1 in
the instant Notifications group. The action's own tier is what a refusal reports.

Files also declares `delete`, a file delete. It is **planned and not built**: it is never
advertised, and a call naming it is refused `not_built` with the group's name. Announcing it in the
enum would be a promise the phone cannot keep.

### The three states

Every one of the thirteen groups has the same three states, all off by default.
The same three words for every group, and the same behaviour behind each one; the tier changes
what a card says and whether an undo exists, never which choices you may set.

| state | the enum | a call that names the action |
|---|---|---|
| **Off** | the group's actions are absent | `ok:false, reason:"not_granted"` — nothing was performed, and the detail names the group |
| **Ask** | the actions are offered | nothing runs on the first call: a card is raised and the result is `ok:false, reason:"approval-required"` with `approval_id`, `expires_at` and `summary` |
| **Allow** | the actions are offered | executed without a card |

A stored state is honoured as stored. Allow on a consequential action is a real permission: the
Apps row at Allow can remove an app, and the row says so in its own loud line. There is no hidden
veto and no short list of actions that always ask behind an Allow row. Every group is set on its
own; the sheet has no allow-all control.

**Ask is one handshake.** Every action at Ask uses the same two-step handshake, whatever its tier:

1. Call the action with its arguments and no `approval`. Nothing is performed; a card naming the
   action and its arguments is raised, and the result carries `approval_id`, `expires_at` (two
   minutes after the card was raised) and `summary`.
2. After the owner answers, call **the same action with the same arguments plus
   `approval:"<the id>"`**. It runs only when the id is unexpired, unused and bound to those exact
   arguments.

An id is used once. Different arguments are `approval_mismatch`; a denied card is
`approval_denied`; an unanswered card that expires is `approval_expired`; an answer from a locked
phone is `approval_locked`; a reused or unknown id is `approval_unknown`. Every one of those burns
the id. One card is on screen and two may wait behind it; the fourth request is `approval_busy` and
raises nothing. A card is answered by tap or by the same yes/no vocabulary the gateway's cards
use; dismissing it, or silence at expiry, is a no.

A message or a dial addressed to a contact **name** reads the address book, so it also answers the
Contacts group's Ask even when Messages or Calls is Allow. A number does not.

### The tiers

The tier is a description of the consequence, not a permission. Every group offers all three states.

| tier | name | what it describes |
|---|---|---|
| **0** | Instant | cheap, reversible and private. Most register an undo; reads and handoffs do not |
| **1** | Reported | changes something you can see, is recorded in Recent actions, and is undoable where the row says |
| **2** | Consequential | no undo and no way back. `uninstall` and `file_offer` are built and run at Allow or after their card; `delete` is planned and refused `not_built` |

### What each action reports

Every success carries `ok`, `action`, `tier` and `undoable` explicitly, plus `id` where the action
registered one, plus an `undo` object of the form `{"action":"undo","id":"…"}` when it can be
reversed. The table names the remaining fields.

| action | tier | reports on success |
|---|---|---|
| `timer` | 0 | `remaining_ms`, `duration_ms` |
| `alarm` | 0 | `at` (`HH:MM`), `in_ms` |
| `dnd` | 0 | `on` (the filter actually in effect), `previous` |
| `volume` | 0 | `level` (read back), `previous` |
| `note` | 1 | `text`, `reported_next_turn` |
| `play`, `pause` | 0 | `dispatched`, `note` |
| `skip` | 0 | `direction`, `dispatched`, `note` |
| `now_playing` | 0 | `known`, `playing`, `package`, `title`; `note` when `known` is false |
| `state` | 0 | `battery_percent`, `charging_source`, `charging_status`, `screen_on`, `network`, `tailnet_present`, `free_storage_bytes`, `media_volume`, `dnd`, `timezone`, `utc_offset_minutes`, `time` — each `null` when unreadable |
| `clip` | 0 | `chars` (the length only) |
| `clip_read` | 0 | `text`, `chars` |
| `file_list` | 1 | `folder`, `path`, `depth`, `count`, `has_more`, `next_offset`, `entries` (`name`, `path`, `kind`, `bytes`, `modified`, `mime`) |
| `file_read` | 1 | `name`, `folder`, `bytes`, `chars`, `text`; a byte window adds `offset`, `length` and `truncated`, with `bytes` the file's total |
| `file_offer` | 2 | `name`, `folder`, `mime`, `bytes`, `sha256`, `url`, `expires_at` |
| `agenda` | 1 | `count`, `events` (`start`, `end`, `title`, `calendar`, `description`, `location`, `attendees` — names only) |
| `event_create` | 1 | `title`, `start`, `end`, `duration_min`, `start_ms`, `end_ms`, `calendar` |
| `notifications` | 0 | `sensitive:true`, `count`, `apps` (`app`, `count`, `latest_at`, opaque `ids`) — no title, no text |
| `notification_read` | 0 | `sensitive:true`, `app`, `posted_at`, `title`, `text` |
| `notification_dismiss` | 1 | `app`, `count`, `note`, and `failed` when some of the app's notifications remained |
| `message` | 0 | `to`, `chars`, `handed_off:true` |
| `dial` | 0 | `to`, `dialer` (`opened`), `note` |
| `apps` | 0 | `apps` (`package`, `label`), `total`, `truncated`; with usage access `can_see_running:true`, `foreground`, `recent`; without it `can_see_running:false`, `running_detail`, `usage_settings` |
| `launch`, `open` | 0 | `package`, `component`, `started` (`now` or `after_pip`), `handoff` (`opened` or `pending`) |
| `contact` | 0 | `count`, `matches` (`display_name`, `numbers` with `label` and `number`); at most 20 people |
| `uninstall` | 2 | `package`, `label`, `platform_confirmation:true`, `note` |
| `undo` | 0 | `undone` (the kind that was reversed), `state` (`cancelled` or `restored`) |
| `status` | 0 | `offered`, `tiers`, `groups_on`, `grants`, `running`, `notes`, `registry`; `dnd` and `volume` when the DND-and-volume group is on, and `dial` when the calls group is on and a handoff has finished |

`status` is the discovery action: it answers "what can this phone do and what is running" from the
same registry the presence context reports. `undo` and `status` belong to no group and are always
offered.

`undo` can reverse only the agent's own entries, and only while their group is not Off. A timer you
set by voice is yours; the agent cannot cancel it. An undo of an id with no live undo — fired,
superseded, or never this phone's — is `reason:"expired"`.

### What you see in the sheet

**Capabilities** lists the thirteen groups by tier, each with its one line, the same three choices,
and a standing warning where the consequence is not obvious. Files, Notifications, Clipboard,
Messages, Calls, Apps and Contacts carry a second, louder warning that appears only while the row is
Allow — the state in which the consequence happens without a card.

**Recent actions** is the counter-claim: one row per call, successes and refusals, newest first,
with time, action, summarised arguments, outcome and an Undo where the action carries one. A note's
text is stored as its length; a notification row is a count; a contact row is a count; a listing row
is folder, path and count. Tapping an undoable row calls the same `undo` the agent would, and the
toast repeats the result.

**This phone** shows reachability, whether the server is listening and at which URL, the last
request, the token's state, and each special access the actions depend on — Do Not Disturb, Now
playing, Notification access, Contacts permission, Volume permission, Files folder and Calendar
permission — with the button that opens the right Android screen.

## What it writes, sends or changes

- **The agent's own configuration.** The setup turn asks the agent to add or update one `vox` entry.
  Vox does not write to the host, and nothing restarts.
- **One token, once.** It rides the setup turn over the same authenticated connection as every other
  turn, is kept in memory only, and is scrubbed from a reply that echoes it.
- **A listening socket** on the tailnet address, port 8765, path `/mcp`, only while a call is or a
  setup window is open.
- **An audit row per call**, success or refusal, through the phone's audit store. Arguments are
  summarised: a note, clip or message text and an event title as `NN chars`; a dismissal by app and
  count; a notification summary by count; a contact lookup by count; a listing by folder, path and
  count; an uninstall by package and label.
- **The phone effects themselves**: timers and alarms in this app, the interruption filter, the
  media stream, the clipboard, a note in private storage, a folder read or a file staged to the
  agent, a calendar read or insert, notification reads and dismissals, a draft handed to your
  messaging app, the dialer opened, an app or a link brought forward, and an uninstall that still
  needs Android's own confirmation.
- **A file export** of at most 100 MB (100,000,000 bytes), served from the file bridge. At most four
  offers are held open at once; the oldest is closed to make room for the fifth.

## What it refuses

A refusal is a **result**, not a transport error: `ok:false`, a stable `reason`, and a `detail`
sentence that says nothing was performed. The agent is told to check `ok` before telling you an
action is done.

The four shapes worth knowing:

- **`not_granted`** — the group is Off. Nothing was attempted, and the detail names the group you
  would have to grant.
- **`approval-required`** — the group is Ask. A card is waiting, and the id is in the result. Nothing
  was performed.
- **`unavailable`** — the action was recognised and the phone or Android refused or failed. Nothing
  was performed, unless the result also carries `outcome_uncertain:true`.
- **`outcome_uncertain`** — the work was already dispatched and the caller stopped waiting: a tool
  call that exceeded Vox's four-second bound, or a dial whose dialer opens only after the live call
  is torn down. The work may still complete, so the agent is told to call `status` rather than say
  nothing happened.

`not_built` is its own reason, not a grant state: the action is declared by a group and this phone
cannot do it, so no card is raised and granting the group would not help. `invalid-arguments` and
`unknown-action` say the call itself was malformed or named something that is not a phone action.

The complete reason vocabulary, and the refusals particular to each action, are in
`docs/MCP-SURFACE.md`. The ones that are about your choices rather than the phone's state are:
`not_granted`, `approval-required`, `approval_denied`, `approval_expired`, `approval_mismatch`,
`approval_unknown`, `approval_busy`, `approval_locked`, `not_built`, and `expired` for an undo that
no longer exists.

## Permissions and access

| permission or access | why the phone declares it | what refuses without it |
|---|---|---|
| `INTERNET` | the listener answers on the tailnet | nothing starts without it |
| `ACCESS_NOTIFICATION_POLICY` | Do Not Disturb is a special access, granted on Android's own screen | `dnd` answers `permission` |
| `MODIFY_AUDIO_SETTINGS` | changing the media stream is what `volume` does | the platform's own refusal, as `unavailable` |
| `READ_CALENDAR`, `WRITE_CALENDAR` | `agenda` reads, `event_create` writes and needs read to find a writable calendar | `permission`, naming the exact Android string |
| `READ_CONTACTS` | a name lookup is an address-book read | `permission`, naming `android.permission.READ_CONTACTS` |
| notification-listener access | `now_playing` names the app and track; the notifications group reads during a call | `permission` for the group; `known:false` for `now_playing` |
| usage access (`PACKAGE_USAGE_STATS`) | `apps` can say what is in front only with it | no refusal: the result says `can_see_running:false` and how to grant it |
| a folder through the system picker | Files reads inside one tree the owner picked; no storage permission is declared | `no_folder` until one is granted |
| `POST_NOTIFICATIONS` | the app's own timer and call notifications | a denied notification is logged; the timer still rings |
| `VIBRATE`, `WAKE_LOCK`, `RECORD_AUDIO`, `ACCESS_NETWORK_STATE`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, `FOREGROUND_SERVICE_DATA_SYNC` | the call, the sound cues and model downloads; not a phone action | — |

**This is a *special* access**, not a runtime dialog, for Do Not Disturb, notification access and
usage access: the sheet opens Android's own screen and shows whether the grant landed.

Timer and alarm need no exact-alarm permission. They use `AlarmManager.setExact(…, OnAlarmListener,
handler)`, the listener form the exact-alarm restriction exempts, so the manifest declares neither
`SCHEDULE_EXACT_ALARM` nor `USE_EXACT_ALARM`.

The notification-listener grant is permanent; the app's **use** of it is not. The listener attaches
at call start and detaches at call end, and detaching drops every record and every id.

## Security

- **The tailnet, not the LAN.** The server binds exactly one address — the one Tailscale gives the
  phone — and `PhoneMcpServer.start` refuses an any-local address itself.
- **A bearer on top.** Every request needs `Authorization: Bearer <credential>`, where the
  credential is the phone's token or the API key of the connection it talks to. Anything else is
  `401` before the body is parsed. Both comparisons are constant-time and always run, so the refusal
  says nothing about which one was presented.
- **The grants are the third layer.** Neither of the first two says what an authenticated client may
  do. The group grant does, and it is read at call time, never cached for the life of a session:
  turning a group Off removes its actions from the next `tools/list` and refuses the next call, even
  mid-call.
- **No MCP resources, no SSE, no server-initiated messages, no second route.** Each would be a
  surface with no caller. A file listing is an action, not a resource listing.
- **No payload logging.** A note's text, a clip's payload, a file's contents, a notification's title
  and a calendar event's contents are never written to a log.

## What it cannot do

- It cannot send a text, place a call, send email, or read a message thread.
- It cannot install an app, and it cannot remove one without Android's own confirmation.
- It cannot delete a file: `delete` is planned for Files and is refused `not_built`.
- It cannot make a purchase, a payment or a transfer; those are not phone actions, and a call naming
  one is `unknown-action`.
- It cannot read outside the folder you granted, and it uses no all-files access and no storage
  permission.
- It cannot read or dismiss a notification when no call is live, and a summary never carries a title or a text.
- It cannot read the clipboard while Vox is not in front; Android decides, and the refusal says so.
- It cannot see what a media key did, nor which app is playing without notification access.
- It cannot launch another app from the background; every handoff is refused `background` first.
- It cannot undo a media key, a read, a clipboard write, a message, a dial, a launch, an open, an
  uninstall or a dismissal.
- It cannot undo a timer or alarm after it rings, a DND or volume change after the next change of the
  same setting or after ten minutes, a note after 24 hours, or a created event after it starts or
  after ten minutes, whichever is later.
- It cannot reverse the owner's own actions, only the agent's.
- It cannot raise a fourth approval card while three are waiting.
- It cannot approve a card from a locked phone.
- It cannot run while Phone tools is off, while Tailscale is down, or while no call or setup window
  holds the listener up.
- It cannot serve more than one tool or answer on more than one route.
