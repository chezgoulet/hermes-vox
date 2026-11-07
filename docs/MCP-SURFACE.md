# The `phone` tool's surface

The phone exposes exactly **one** MCP tool, `phone`. This document is its contract: the action
enum, the arguments each action reads, the fields each returns, the shapes a call can end in, the
reason strings, the approval handshake, the audit, and the transport. `docs/MCP-PHONE.md` is the
operational document — what the owner sets up, what the thirteen capability groups mean, and what
the phone refuses to do at all.

**The phone** is the Android device running Vox. **The agent** is the Hermes instance on the
gateway that calls the tool. **A call** is one live voice conversation; the tool's server is up
while one is, and while a setup window is open. **A grant** is the owner's Off/Ask/Allow choice for
one capability group. **The entry** is the `vox` server entry in the agent's own MCP configuration,
which is `lazy` so the tool stays registered while the phone is idle.

## What you need

- A client that speaks **Streamable HTTP** JSON-RPC, pointed at the phone's tailnet URL
  (`http://<tailnet-address>:8765/mcp`), with a bearer credential.
- An MCP `initialize`, then a session, for every request that is not `initialize`.
- A live call, or an open setup window, so the listener exists at all.
- Grants the owner has set. With every group Off the tool offers `undo` and `status` and nothing
  else.

## The tool

| property | value |
|---|---|
| name | `phone` |
| the name the model sees | `mcp__vox__phone` — the gateway prefixes `mcp__<server>__` |
| input | one object, `required: ["action"]`, `additionalProperties: false` |
| annotations | `title: "Phone"`, `readOnlyHint: false`, `idempotentHint: false`, `openWorldHint: false` |

The tool changes the phone, so `readOnlyHint` is false and the gateway's `trust: untrusted` mode is
meant to gate it.

The description is rebuilt on every request, because a grant can change between two turns. It
carries the rule that makes `sensitive:true` mean something, in the agent's own copy:

> Notifications and their contents may include verification codes, private messages and financial
> alerts. Do not read them aloud unless the owner asked you to, do not repeat them back, and do not
> store them in a note.

It then names the groups that are on, and their states. With every group Off it says: *No
capability group is on: only `status` and `undo` are available.* A group at Ask is described as one
where each call raises a card and runs only after the owner answers.

The `action` property's description lists the offered actions with a one-phrase gloss each — *timer:
countdown*, *contact: a contact's numbers by full or partial name* — and ends by saying that an
action in a group that is Off is absent on purpose and that naming one is refused with
`reason=not_granted`.

## The action enum

The enum is a pure function of the owner's grants, read at request time: every implemented action
whose group is not Off, in group order, then `undo` and `status`. A group at Ask is offered, because
a card answers it; a group that is Off contributes no name.

| group | tier | actions in the enum when the group is on |
|---|---|---|
| `timers-alarms` | 0 | `timer`, `alarm` |
| `dnd-volume` | 0 | `dnd`, `volume` |
| `notes` | 1 | `note` |
| `media` | 0 | `play`, `pause`, `skip`, `now_playing` |
| `device-state` | 0 | `state` |
| `clipboard` | 0 | `clip`, `clip_read` |
| `files` | 1 | `file_list`, `file_read`, `file_offer` |
| `calendar` | 1 | `agenda`, `event_create` |
| `notifications` | 0 | `notifications`, `notification_read`, `notification_dismiss` |
| `messages` | 0 | `message` |
| `calls` | 0 | `dial` |
| `apps` | 0 | `apps`, `launch`, `open`, `uninstall` |
| `contacts` | 0 | `contact` |
| — (no group) | 0 | `undo`, `status` — always offered |

Two actions have their own tier inside a group: `uninstall` is tier 2 in the instant Apps group, and
`notification_dismiss` is tier 1 in the instant Notifications group. A refusal reports the action's
tier, so the same action cannot report tier 0 when refused and tier 2 when run.

`files` also declares `delete`, which is **planned and not built**. It is never in the enum, is not
executable, and a direct call is refused `not_built` naming Files — not `approval-required` for a
card nothing can raise. `apps` lists the launchable entries; `notification_dismiss` removes one.
`install` is not a phone action. An ordinary app cannot install a package, so a call naming it is
`unknown-action`, as are `call`, `send`, `email`, `purchase`, `pay` and `transfer`.

## Arguments, per action

`action` is the only property the schema marks `required`, because one tool carries many actions.
The **parser** enforces each action's requirements and refuses a missing or unusable one with
`invalid-arguments`, whose detail names both the action and the argument. The same parser refuses
any key the action does not read: a misspelt `duration` for `duration_ms`, or a `to` on a `note`, is
a refusal, never something silently dropped. `approval` is accepted by every action, and no action
lists it among its own arguments.

A whole number may arrive as a JSON integer or a numeric string. Text is trimmed where the table
says so.

| action | required | optional | bounds and shapes |
|---|---|---|---|
| `timer` | `duration_ms` | — | integer, 1,000 – 86,400,000 ms |
| `alarm` | `at` | — | 24-hour clock time, one or two digit hour and two digit minutes (`7:30`, `07:30`), hours 0–23, minutes 0–59 |
| `dnd` | `on` | — | `true` or `false` |
| `volume` | one of `level` **or** `direction` | — | `level` 0–100, or `direction` `up`/`down` for one stream step. A level wins when both are present |
| `note` | `text` | — | trimmed, 1–500 characters |
| `play`, `pause` | — | — | takes no arguments |
| `skip` | — | `direction` | `next` (the default) or `previous`. `prev` is not a word this tool accepts |
| `now_playing` | — | — | takes no arguments |
| `state` | — | — | takes no arguments |
| `clip` | `text` | — | 1–2,000 characters, written exactly as given (not trimmed) |
| `clip_read` | — | — | takes no arguments |
| `file_list` | — | `folder`, `path`, `depth`, `pattern`, `offset`, `limit` | `folder` is a granted folder's label, case-insensitive; `path` is relative inside it (≤ 1,024 characters, `""` = the folder); `depth` 1–5, default 1; `pattern` a name glob (≤ 255 characters, `*` and `?`, case-insensitive); `offset` ≥ 0, default 0; `limit` 1–1,000, default 200 |
| `file_read` | `name` | `folder`, `offset`, `length` | `name` is a file name, ≤ 255 characters, never a path; `offset` ≥ 0 bytes; `length` 1–65,536 bytes |
| `file_offer` | `name` | `folder` | the same name rule; `offset`/`length` are refused — an offer is the whole file |
| `agenda` | — | — | takes no arguments |
| `event_create` | `title`, `start`, `duration_min` | `calendar` | `title` ≤ 200 characters; `start` local ISO-8601 `YYYY-MM-DDTHH:MM` with optional seconds, no `Z` and no offset; `duration_min` an integer ≥ 1 with no upper bound of ours; `calendar` by name, omitted for the default |
| `notifications` | — | — | takes no arguments |
| `notification_read` | `id` | — | an id a summary of this call returned, ≤ 80 characters |
| `notification_dismiss` | exactly one of `id` **or** `app` | — | `id` ≤ 80 characters; `app` ≤ 200 characters, launcher label or package, case-insensitive |
| `contact` | `name` | — | full or partial name, case-insensitive, ≤ 200 characters |
| `message` | `to`, `text` | — | `to` ≤ 200 characters, a number or a contact name when Contacts is on; `text` non-blank, ≤ 1,000 characters |
| `dial` | `to` | — | ≤ 200 characters, a number or a contact name when Contacts is on |
| `apps` | — | — | takes no arguments |
| `launch` | `app` | — | a package or the launcher's label, ≤ 200 characters |
| `open` | `uri` | `package` | `uri` ≤ 2,000 characters, must carry a scheme and contain no whitespace; `package` ≤ 200 characters |
| `uninstall` | `package` | — | a dotted package name, ≤ 255 characters |
| `undo` | `id` | — | the id an earlier result returned |
| `status` | — | — | takes no arguments |
| any action at Ask | — | `approval` | the `approval_id` a previous result returned, ≤ 64 characters; omit it to ask for the card |

A name is a name, never a path: `..`, an absolute path, a separator, a URI or a control character
is `bad_path` before the folder is asked. A listing's `path` is the other shape — a relative
subdirectory, refused for an absolute path, a backslash, a URI, a control character, or an empty,
`.` or `..` segment.

`open`'s `uri` must have a scheme. `intent:`, `file:` and a string with no scheme are `bad_uri`, and
**Nothing was resolved and no intent was built.** `https:`, `http:`, `content:`, `mailto:` and a
deep link pass through.

The `folder` property's enum is the owner's granted folder labels, built per `tools/list`; with no
folder granted there is no enum and the description says the file actions refuse `no_folder` until
the owner picks one. With one folder granted it is the default; with several, a call without
`folder` is `invalid-arguments` naming the choices.

The `direction` property is shared by `volume` and `skip`, and their vocabularies are disjoint, so
the schema advertises **no enum** for it: an enum would advertise `up` for a skip and `next` for a
volume. The property's description names both pairs, and the parser accepts exactly those, refusing
anything else with a sentence naming the action and the value it was given.

## Results, per action

Every result is one JSON object, `ok` first. A success carries `ok:true`, `action`, `tier`, `id`
when the action registered one, the fields below, and **`undoable` explicitly** — `true` exactly
when an `undo` object is present, `false` for every read and every irreversible action. Absence
never means "unknown" in that field.

| action | tier | result fields besides `ok`, `action`, `tier`, `undoable` | undo |
|---|---|---|---|
| `timer` | 0 | `id`, `remaining_ms`, `duration_ms` | **yes** — cancels it; lives until it rings |
| `alarm` | 0 | `id`, `at`, `in_ms` | **yes** — cancels it; lives until it rings |
| `dnd` | 0 | `id`, `on`, `previous` | **yes** when a previous filter was readable; superseded by the next `dnd`, or 10 minutes |
| `volume` | 0 | `id`, `level`, `previous` | **yes** when the previous level was readable and different; superseded by the next `volume`, or 10 minutes |
| `note` | 1 | `id`, `text`, `reported_next_turn` | **yes** — deletes it; 24 hours |
| `play`, `pause` | 0 | `dispatched`, `note` | no — a key press into an unknown state is not a reversal |
| `skip` | 0 | `direction`, `dispatched`, `note` | no — there is no "unskip" the phone can perform |
| `now_playing` | 0 | `known`, `playing`, `package`, `title`; `note` when `known` is false | no — reporting only |
| `state` | 0 | all twelve fields below | no — read-only |
| `clip` | 0 | `chars` (the length only) | no — the write cannot be reversed |
| `clip_read` | 0 | `text`, `chars` | no — reading changes nothing |
| `file_list` | 1 | `folder`, `path`, `depth`, `count`, `has_more`, `next_offset`, `entries` (each `name`, `path`, `kind`, `bytes`, `modified`, `mime`) | no — listing changes nothing |
| `file_read` | 1 | `name`, `folder`, `bytes`, `chars`, `text`; a window adds `offset`, `length`, `truncated`, with `bytes` the file's total | no — reading changes nothing |
| `file_offer` | 2 | `name`, `folder`, `mime`, `bytes`, `sha256`, `url`, `expires_at` | no — an export cannot be recalled |
| `agenda` | 1 | `count`, `events` (each `start`, `end`, `title`, `calendar`, `description`, `location`, `attendees` — names only) | no — reporting only |
| `event_create` | 1 | `id`, `title`, `start`, `end`, `duration_min`, `start_ms`, `end_ms`, `calendar` | **yes** — deletes that one event; until it starts, or ten minutes, whichever is later |
| `notifications` | 0 | `sensitive:true`, `count`, `apps` | no — reading changes nothing |
| `notification_read` | 0 | `sensitive:true`, `app`, `posted_at`, `title`, `text` | no — reading changes nothing |
| `notification_dismiss` | 1 | `app`, `count`, `note`, and `failed` when some remained | no — Android cannot restore a dismissed notification |
| `message` | 0 | `to`, `chars`, `handed_off:true` | no — Vox composed a draft; the owner's app sends |
| `dial` | 0 | `to`, `dialer`, `note` on success | no — the owner's call is not Vox's to unplace |
| `apps` | 0 | `apps`, `total`, `truncated`, and the running half below | no — reading changes nothing |
| `launch`, `open` | 0 | `package`, `component`, `started` (`now` or `after_pip`), `handoff` (`opened` or `pending`) | no — bringing an app forward is not a reversal |
| `contact` | 0 | `count`, `matches` | no — a lookup changes nothing |
| `uninstall` | 2 | `package`, `label`, `platform_confirmation:true`, `note` | no — a removed app cannot be put back by this phone |
| `undo` | 0 | `id`, `undone` (the kind reversed), `state` (`cancelled` or `restored`) | — |
| `status` | 0 | `offered`, `tiers`, `groups_on`, `grants`, `running`, `notes`, `registry`; `dnd` and `volume` when the DND-and-volume group is on, and `dial` when the calls group is on and a handoff has finished | no — reporting only |

A successful `undo` has `state:"cancelled"` for a timer, alarm or created event and `"restored"` for
a DND, volume or note reversal.

### Media, precisely

`play`, `pause` and `skip` dispatch a platform media key and nothing else. They act on whichever
session Android considers active; the phone does not choose a player and does not start one.
`dispatched:true` means the audio service took the key, never that a track started, stopped or
changed. All three carry a `note` saying so.

`now_playing` reports what the phone will say. `playing` is `isMusicActive`. The package and title
are the active session's own values and `known` is `true` only with notification-listener access;
without it `package` and `title` are `null`, `known` is `false`, and `note` says what the phone
cannot see. The phone never invents a title. When the access is held but no app is publishing a
session, the note says that, rather than blaming a missing grant.

```json
{"ok":true,"action":"now_playing","tier":0,"known":false,"playing":true,"package":null,
 "title":null,"undoable":false,
 "note":"audio is playing, but the phone cannot see which app it is or what it is: that needs notification-listener access, which this app does not hold"}
```

### Device state, and the null rule

`state` returns one object with every field present; a field the platform will not answer is `null`,
never a guess and never a zero:

| field | value | `null` means |
|---|---|---|
| `battery_percent` | 0–100 | the battery broadcast was unavailable |
| `charging_source` | `ac`, `usb`, `wireless`, `dock`, `none` | the platform would not say; `none` is a real answer (not plugged in) |
| `charging_status` | `charging`, `discharging`, `full`, `not-charging` | the platform would not say |
| `screen_on` | boolean | `PowerManager` would not answer |
| `network` | `wifi`, `cellular`, `ethernet`, `vpn`, `other`, `none` | the platform would not say; `none` is a real answer (connected to nothing) |
| `tailnet_present` | boolean | the interfaces could not be enumerated |
| `free_storage_bytes` | integer | the data directory's free space could not be read |
| `media_volume` | 0–100 | the media stream could not be read |
| `dnd` | `on`, `off` | Do Not Disturb access is not held, so the filter is not readable |
| `timezone` | e.g. `Europe/Berlin` | there is no device zone |
| `utc_offset_minutes` | integer, east of UTC positive | the offset could not be read, which also makes `time` `null` |
| `time` | local `YYYY-MM-DDTHH:MM:SS` | the offset is unknown |

### Files, precisely

`file_read` returns text, never a whole file by accident.
The 64 KiB figure is the size of one answer. A whole read above `MAX_READ_BYTES` is `too_big` and
names `offset`/`length`; a windowed read returns the slice with `truncated:false` only when it is
the entire file, and `bytes` is always the file's total. A window is cut at byte offsets and snaps
inward to UTF-8 boundaries, so stitching windows by the offsets asked for never duplicates a
character at a seam.

`length`'s schema maximum and the parser's limit are both 65,536 bytes; asking for more is refused
with "read the rest with a later offset". `file_offer` has no such cap: it does not put bytes in the
result, and the file bridge's own limit is 100 MB. At most four offers are held open at once; the
oldest is closed for the fifth.

A listing walks one granted tree, one page at a time. `depth` is 1 by default and at most 5;
directories come before files at each level, each group alphabetical, so pages are stable. `pattern`
filters files and never hides a directory. A page answers `has_more` and `next_offset`; a folder
with more entries than a page is paged, never refused and never silently cut. Listing never opens a
file: it reads names, kinds, sizes, modified times and MIME types from the provider's child query.

Each grant is keyed by its **tree id** (`primary:Download/Work`), so a `Work` under Download and a
`Work` under Documents are two grants. A file's name is checked before the provider is asked and the
document id it resolves to is checked again afterwards; a symlink that leaves the tree is refused
`bad_path`. For a provider with no filesystem path, the document-id check is the whole check.

### Calendar, precisely

`agenda` reads today's events in the phone's own zone: start, end, title, calendar name,
description, location and attendee **names**. Attendee addresses are never projected. A field the
provider will not give is `null`.

`event_create` takes a local start: a trailing `Z` or an offset is refused rather than converted,
because a conversion would move the event. `duration_min` has no upper bound of ours — the
provider's own limits apply, and a duration it will not store is `unavailable`. A phone with no
writable calendar is `unavailable`. The undo deletes the event it created and nothing else.

### The apps running half

`apps` always answers the installed half: `apps` (`package`, `label`, sorted), `total`, and
`truncated` when the list was capped at 200. The running half needs usage access. With it,
`can_see_running:true`, `foreground` (`package`, `label`) and `recent` (up to five, newest first);
without it, `can_see_running:false`, `running_detail` (the sentence saying what is missing and where
to grant it) and `usage_settings` (the screen to open). It never returns an empty list as "nothing
is running".

### Contacts and notifications, precisely

`contact` returns at most 20 matching people from `ContactsContract`: `display_name` and `numbers`,
each with the owner's `label`. No note, postal address, photo or email is projected. No match is
`ok:true, count:0`. A missing `READ_CONTACTS` is `permission` naming the exact Android string.

The notifications grant is permanent; the app's use of the listener is not. A summary carries
`sensitive:true`, `count`, and one row per app with `count`, `latest_at` and opaque `ids` — no title
and no text at any depth. `notification_read` takes one id a summary **of this call** returned; an
id that was never shown is `unknown-id`, which is what keeps a vague question from vacuuming the
lock screen. `notification_dismiss` takes one id or one app and answers `app`, `count`,
`undoable:false`, a `note`, and `failed` when some of the app's notifications remained. Vox's own
notifications are excluded from the board, and dismissing one is `unknown-id`.

## The four shapes a call ends in

Every `tools/call` is a `CallToolResult` with one text block — the result JSON — and `isError` set
from that JSON's `ok`. The mind can tell the cases apart without reading prose.

**1. Done.** `isError:false`, `ok:true`, and what changed.

```json
{"ok":true,"action":"timer","tier":0,"id":"timer-3","remaining_ms":300000,
 "duration_ms":300000,"undoable":true,"undo":{"action":"undo","id":"timer-3"}}
```

**2. Refused before anything ran.** `isError:true`, `ok:false`, `reason`, `detail`. The action did
not happen, and the detail says so. The commonest is a group that is Off:

```json
{"ok":false,"action":"timer","tier":0,"reason":"not_granted",
 "detail":"the \"Timers and alarms\" group is off on this phone; nothing was performed. Only the owner can grant it in Phone tools."}
```

**3. Waiting for the owner.** `isError:true`, `reason:"approval-required"`, and the id the second
call needs. The fields are a call to action, not a success fact:

```json
{"ok":false,"action":"uninstall","tier":2,"reason":"approval-required",
 "detail":"the owner must approve uninstall Firefox; the card is up for 120 seconds. Call uninstall again with the same arguments and approval=\"ap-7\" after they answer.",
 "approval_id":"ap-7","expires_at":1767225600000,"summary":"uninstall Firefox"}
```

The same shape answers any action whose group is Ask — `set a timer for 5 minutes` is a `summary`
too — and a call with the id while the card is still up returns `approval-required` again with the
same id without burning it.

**4. Recognised, and it did not work.** `isError:true`, `reason:"unavailable"` (or `permission`,
`no_app`, `bad_path`, …), and a `detail` with the platform's own answer:

```json
{"ok":false,"action":"volume","tier":0,"reason":"unavailable",
 "detail":"the volume could not be changed; some devices refuse a stream change while do-not-disturb owns it"}
```

**A timeout is not a refusal of the work.** The phone runs one action at a time on its own thread,
and the caller stops waiting after four seconds; the action itself is not cancelled. The result is
the same shape with `outcome_uncertain:true`, so the mind must call `status` and must not tell the
caller nothing happened. A dial that ends a live call and opens the dialer afterwards answers the
same way, carrying `to` and `dialer:"unknown"`:

```json
{"ok":false,"action":"dial","tier":0,"reason":"unavailable",
 "detail":"Vox's call is ending, so the dialer has not opened yet: …",
 "to":"+15551234567","dialer":"unknown","outcome_uncertain":true}
```

*(The `detail` above is abridged; the result carries the whole sentence.)*

Every other refusal means the work was not dispatched.

## The reason strings

Stable strings; the model reads them.

| `reason` | meaning |
|---|---|
| `not_granted` | the action's group is Off; the action is not offered and was not performed. Also the answer when the agent tries to `undo` the owner's own entry, or an entry whose group is now Off |
| `not_built` | the action is declared by a group but this phone does not implement it; it is not offered, no card exists to raise, nothing was performed, and the detail names the action and the group |
| `approval-required` | the group is Ask: nothing was done, and a card with `approval_id`, `expires_at` and `summary` is waiting |
| `approval_denied` | the owner answered no; nothing was performed, and the id is burnt |
| `approval_expired` | the card was not answered within two minutes (silence is no); nothing was performed, and the id is burnt |
| `approval_mismatch` | the id was approved for another action or another argument set; nothing was performed, and the id is burnt |
| `approval_unknown` | no such id, or it has already been used; nothing was performed |
| `approval_locked` | the card was answered while the phone was locked; nothing was approved, and the id is burnt |
| `approval_busy` | three cards were already waiting; nothing was raised and nothing was performed |
| `permission` | the phone does not hold a permission or special access the action needs; the detail names Android's own string |
| `background` | Vox is not in the foreground, so the activity start a handoff needs would be blocked. Nothing was resolved, opened, sent or ended |
| `no_folder` | no folder has been granted, or not the one the call named |
| `bad_path` | the name or path is a path, climbs, or resolves outside the granted folder |
| `bad_uri` | `open` was given `intent:`, `file:`, or a string with no scheme. Nothing was resolved and no intent was built |
| `not_text` | the file's bytes are not text; `file_read` returns text only |
| `too_big` | a whole-file `file_read` is above 64 KiB; ask again with `offset`/`length`. A file offer above 100 MB answers the same |
| `unavailable` | the action was recognised and the platform refused, failed or would not say. Carries `outcome_uncertain:true` when the work may still complete |
| `invalid-arguments` | the arguments do not describe a runnable action: a missing required value, an unknown key, a value out of bounds, several granted folders with no `folder`, an ambiguous `open` target |
| `expired` | no undo is registered for that id any more |
| `no_app` | nothing on the phone answers the intent: no `ACTION_SENDTO` app, no dialer, no launchable activity, or no app for the URI |
| `unknown-id` | `notification_read` or `notification_dismiss` was given an id no summary of this call showed, or the notification has left the phone; a dismissal by `app` that matches nothing answers the same way |
| `unknown-action` | not a phone action; the detail lists what is offered right now |

### Refusals particular to an action

Every action in a group can answer `not_granted`, `approval-required` and `invalid-arguments`;
`unknown-action` is what a name that is not a phone action gets, before any action is chosen. These
are the refusals that belong to one action's own work:

| action | refusals of its own |
|---|---|
| `dnd` | `permission`, `unavailable` |
| `file_list` | `no_folder`, `bad_path`, `unavailable` |
| `file_read` | `no_folder`, `bad_path`, `not_text`, `too_big`, `unavailable` |
| `file_offer` | `no_folder`, `bad_path`, `too_big`, `unavailable`, and the `approval_*` family |
| `agenda`, `event_create` | `permission`, `unavailable` |
| `notifications` | `permission`, `unavailable` (no live call, or no bound listener) |
| `notification_read` | `permission`, `unavailable`, `unknown-id` |
| `notification_dismiss` | `permission`, `unavailable`, `unknown-id` |
| `contact` | `permission`, `unavailable` |
| `message` | `background`, `no_app`, `unavailable`, `permission` for a name without `READ_CONTACTS`, `not_granted` naming Contacts for a name while Contacts is Off |
| `dial` | `background`, `no_app`, `unavailable`, `permission` for a name, `not_granted` naming Contacts for a name |
| `launch`, `open` | `background`, `no_app`, `unavailable`; `open` also `bad_uri` |
| `uninstall` | `background`, `unavailable`, and the `approval_*` family |
| `undo` | `expired`, `unavailable`, `not_granted` |
| `timer`, `alarm`, `volume`, `note`, `play`, `pause`, `skip`, `clip`, `clip_read` | `unavailable` |
| `now_playing`, `state`, `apps`, `status` | none of their own: they answer, with `null` or `known:false` where the phone cannot see |

## The approval card

An MCP call carries no run id, so an approval cannot park a host run. It is a handshake on the same
action, for every action at Ask:

1. **Request.** The action's arguments, no `approval`. Nothing is performed; a card is raised naming
   the action and its arguments, and the result is `approval-required` with `approval_id`,
   `expires_at` (epoch milliseconds, two minutes after the card was raised) and `summary`.
2. **Execute.** The same action with the same canonical arguments plus `approval:"<id>"`. It runs
   only when the id is unexpired, unused and bound to the stored canonical text **and** its SHA-256
   digest.

`file_offer` is here because offering a file **exports** it: the bytes leave the granted folder,
travel over the tailnet and stay with the agent. `file_offer` is in the approval tier for that
reason: the offer exports it for the agent, and the folder grant makes that export possible, not
intended, so at Ask every offer gets its own card, one card per file, showing the file's name, type
and size. `uninstall` is the other member, and a removed app cannot be put back. For a file the
card reads *Your agent wants to read `report.pdf` (PDF, 240 KB). Approve?*; for an app, *Your agent
wants to remove Firefox. Approve?* The size is on the card because handing over a 40 MB file is a
different act from handing over a note, and the tool's own name never appears.

The canonical text is `action` + `\n` + every argument except `approval`, sorted by key, with string
values trimmed and internal whitespace collapsed. Key order and whitespace therefore do not matter;
a different package, file name or folder cannot hash the same by accident, and even a digest
collision would have to match the stored text as well.

One card is on screen and two may wait behind it: three pending at most. The fourth request is
`approval_busy` and raises no id at all. A denied, expired, locked or mismatched id is burnt, so a
second use is `approval_unknown`. A dismissed card is a deny, and silence at expiry is a deny too. A card can be answered by tapping Approve or Deny, or by voice with
the same yes/no allowlist the gateway's cards use; a hedged answer is not an answer and the card is
re-asked once.

The card is not actionable on a locked phone. Its notification is `VISIBILITY_PRIVATE`, so the lock
screen shows neither the file's name nor the app being removed, and the answer path checks the
keyguard before recording a yes. A locked answer is `approval_locked`, burns the id, and posts a
private notice that names nothing. Denying is always allowed, because a no cannot authorise
anything. Unlock, ask again, and a fresh card can be approved normally.

After our card says yes, `uninstall` still opens Android's own confirmation. The result says
`platform_confirmation:true`, never "removed": the two screens ask different questions, and the
phone cannot see the owner's answer on the system one. These are behaviours the shipped card
implements.

## The audit

Every call the agent makes — success **and** refusal — writes one row through `PhoneAudit`: time,
action, summarised arguments, outcome, and the undo id when there is a real one. The outcome is
`done`, the refusal's `reason`, or `uncertain` for work that was dispatched and whose result the
phone no longer knows; that row must not read as "refused, nothing happened".

Arguments are summarised, never copied:

- a `note`, `clip` or `message` `text`, and an `event_create` `title`, as `NN chars`;
- a summary's count as `notification_count`; a single read as `app` plus `notification_count=1`;
- a contact lookup as `count`; an app listing as `app_count`;
- a listing as folder, relative path and `NN entries`, never a name it saw;
- an uninstall as package and label;
- everything else — a duration, a clock time, a boolean, a level, a direction, an id, a file name, a
  folder, a recipient, a URI — as itself, clipped to one line.

A refusal that knows the app a dismissal was about keeps the app, never the id. Nothing in this
surface logs a secret, a clipboard payload, a file's contents, a notification's title or body, or a
calendar event's contents.

## The transport

**Streamable HTTP** JSON-RPC on `POST /mcp`, one route, with a server-side session. The gateway's
MCP client is the shape this is written for: it initializes, keeps a session alive with
`tools/list` and `ping`, opens a GET stream, and treats a `405` there as "no push stream".

Authentication comes first: every request needs `Authorization: Bearer <credential>`, where the
credential is the phone's token or the API key of the connection the phone talks to. With neither,
`401` and `WWW-Authenticate: Bearer`, before the body is parsed. Both comparisons run on every
request, so the refusal never says which credential was tried.

| # | request | answer |
|---|---|---|
| 1 | `POST /mcp` `initialize` `{protocolVersion, capabilities, clientInfo}` | `200`, JSON-RPC result with the negotiated `protocolVersion`, `capabilities.tools.listChanged:false`, `serverInfo` and `instructions`, plus **`Mcp-Session-Id`** |
| 2 | `POST /mcp` `notifications/initialized` + the session | `202 Accepted`, no body |
| 3 | `GET /mcp` + the session | `405`, `Allow: POST, DELETE` — there is nothing to push |
| 4 | `POST /mcp` `tools/list` + the session | `200`, the one `phone` tool, built from the live grants and folder labels |
| 5 | `POST /mcp` `tools/call` + the session | `200`, a `CallToolResult` whose text block is the result JSON |
| 6 | `POST /mcp` `ping` + the session | `200`, `{}` — a real answer; `-32601` would make the client latch "ping unsupported" |
| 7 | `DELETE /mcp` + the session | `200`, `{"ok":true,"ended":true}`, and that session is gone; with no id or an unknown one, `404` |
| — | `HEAD /mcp` | `200`, `application/json`, no body — the pre-flight probe sees an MCP endpoint, not a web page |

Protocol revisions implemented: `2024-11-05`, `2025-03-26`, `2025-06-18`, `2025-11-25`. A request
for an unknown one is answered with `2025-06-18`. Every revision listed has the same session
requirement.

**The session.** The id is 128 bits from the platform CSPRNG, 32 hex characters, minted on
`initialize`, never derived from the token, the clock or a counter. Every later POST and DELETE
must carry it; one without a session, or with one this server no longer holds, is `404` — the
spec's cue to initialize again. A session expires after 30 idle minutes, at most 8 are held at once (the one idle
longest is dropped to make room), and `DELETE` retires one immediately.

**Keep-alive.** Every response is a normal HTTP/1.1 response: the connection stays open, and
`Connection: close` is sent only when the client asked for it (or is HTTP/1.0 without
`keep-alive`), when the server is stopping, or when the body could not be drained. A session lives in
the server, not on a socket, so a closed idle connection costs the client a TCP reconnect and never
a session. An idle connection is dropped after 180 seconds; at most 16 connections are served at
once and the next is closed; a body is at most 256 KiB, a header block 16 KiB, a request line
8 KiB.

**Protocol errors**, none of which is the phone refusing to act:

| what | answer |
|---|---|
| malformed JSON, or an empty body | `400`, JSON-RPC `-32700` |
| a batch or a non-object envelope | `200`, JSON-RPC `-32600` |
| an unknown tool | `200`, JSON-RPC `-32602` |
| `arguments` present but not an object | `200`, JSON-RPC `-32602` |
| an unknown method | `200`, JSON-RPC `-32601` |
| `Transfer-Encoding: chunked` | `411`, and the connection ends |
| a body above the cap | `413`, and the connection ends |
| a missing or unknown session | `404`, JSON-RPC `-32600` |
| a path other than `/mcp` | `404`, JSON `{"ok":false,"reason":"not-found"}` |

A `tools/call` whose handler throws still answers with a result — `ok:false`, `reason:"unavailable"`,
`detail` saying the phone failed to run that action — because the transport must not out-vote the
phone about whether something happened.

## What it cannot do

- It cannot offer a second tool: the server's `tools/list` returns exactly one.
- It cannot make the schema require a per-action argument: `required` names `action` and nothing
  else, because one tool carries every action; the parser is where each requirement is enforced.
- It cannot answer `notifications`, `notification_read` or `notification_dismiss` outside a live
  call: those three need the listener a call attaches, so off a call they answer `unavailable`.
- It cannot push anything to the client: no SSE stream, no resources, no resource templates, no
  server-initiated messages, no `listChanged` notification.
- It cannot perform anything outside the thirteen groups; `undo` and `status` are the only actions
  that belong to none.
- It cannot promise an action it has not built: a planned name is never in the enum and is refused
  `not_built`.
- It cannot exceed one answer's caps: 65,536 bytes per `file_read`, 1,000 listing entries per page,
  depth 5, 200 apps, 20 contacts, a 500-character note, a 2,000-character clip, a 1,000-character
  message.
- It cannot keep more than three approval cards, or hold a session for more than 30 idle minutes.
- It cannot approve anything from a locked phone.
