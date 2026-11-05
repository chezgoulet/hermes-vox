# The phone's MCP action surface

This is the **contract document** for every action the phone's `phone` MCP tool exposes: its group,
its tier, its arguments, the fields it returns, whether it can be undone, what it needs from
Android, and the refusals it can answer with. It is the agreement the next lanes implement against,
so a group that is designed but not shipped is written down here as **declared, not built** rather
than omitted — its name, its tier and its refusal already exist on the phone, and only the
capability is missing.

`docs/MCP-PHONE.md` remains the operational document: how the agent is asked to add the server, the
token, the wire, the device pass for the tool as a whole. This one is the surface itself.

**Built today (lane 1):** `media`, `device-state`, `clipboard` — plus the three groups that were
already real (`timers-alarms`, `dnd-volume`, `notes`).
**Built today (lane 2):** `files` and `calendar`.
**Added by the listing lane:** `file_list`, and `file_read`'s `offset`/`length` window.
**Declared, not built:** `notifications`, `messages-calls`, `apps`, `contacts`.

---

## One tool, and how an action is offered

There is **one** tool, `phone`, whose single required argument is `action`. The schema's `enum` is
built from [PhoneGrants](../android/app/src/main/java/com/hermesvox/PhoneGrants.kt) on every
`tools/list`, and from the same registry on every `tools/call`:

| grant | the schema | a call that names it |
|---|---|---|
| **Off** (the default for every group) | the group's actions are **absent** from the `enum` | `ok:false, reason:"not_granted"`, and the detail names the group the owner must grant |
| **Ask**, Tier 0 or Tier 2 | offered | `ok:false, reason:"approval-required"`; the owner's approval card owns it and nothing is performed |
| **Ask**, Tier 1 (reported) | offered | executed, audited, and reported — turning a reported group on *is* the grant |
| **Allow** | offered | executed, audited, and reported |

**Allow exists for Tier 0 only.** A stored Allow on a Tier 1 or Tier 2 group is read back as Ask: a
grant that cannot be given is not a grant. **Ask is not one behaviour:** a Tier 0 group at Ask waits
for the owner's card, and a Tier 2 group at Ask waits for a card on every use; a Tier 1 group at Ask
runs, because the owner has already turned it on and sees what it did. `undo` and `status` belong to
no group and are always offered, because a phone whose grants are all Off must still be able to say
so.

**A refusal is a result, not a transport error.** `isError` stays `false`; the JSON carries `ok`,
`action`, `tier`, `reason` and `detail`. `ok:true` is only ever written after the platform said it
performed the action, and a refusal never carries an `id`, a success fact or an `undo`.

### The tiers

| tier | name | the promise | states |
|---|---|---|---|
| **0** | instant | executes now, private, and reversible *where reversal is real* | Off / Ask / **Allow** |
| **1** | reported | acts now when granted, is reported to the mind on the next turn, and is undoable | Off / Ask |
| **2** | approval | **never executes from this wire.** The result says `approval-required` and names the surface that owns it | Off / Ask |

### The reason vocabulary

| `reason` | meaning |
|---|---|
| `not_granted` | the action's group is Off; the action is not offered and was not performed |
| `approval-required` | the group is Ask, or the action is Tier 2; the owner's approval owns it and nothing was done |
| `permission` | the phone does not hold a permission or special access the action needs |
| `no_folder` | no folder has been granted with the system picker, so there is nothing to read inside |
| `bad_path` | the name is a path, contains `..`, or resolves outside the folder the owner granted |
| `not_text` | the file's bytes are not text; `file_read` returns text only |
| `too_big` | a whole-file `file_read` is above the 64 KiB default; ask again with `offset`/`length` to read it in windows |
| `unavailable` | the action was recognised and the platform refused, failed, or would not say |
| `invalid-arguments` | the arguments do not describe a runnable action (bounds and shapes are below) |
| `expired` | no undo is registered for that id any more |
| `unknown-action` | not a phone action; the detail lists what is offered right now |

Every action in the tables below can also return `not_granted`, `approval-required` and
`invalid-arguments`; the "refusals" column names only the ones **particular to that action**.

---

## Tier 0 — instant

Every result carries `ok`, `action` and `tier`; the "result fields" column lists what is added on
success (an `undo` object is added only when the row's undo column says it is real).

| action | group | arguments | result fields | undo | what it needs from the OS | refusals |
|---|---|---|---|---|---|---|
| `timer` | timers-alarms | `duration_ms` integer, 1 000 – 86 400 000 | `id`, `remaining_ms`, `duration_ms`, `undoable` | **yes** — cancels it; lives until it rings | none: `AlarmManager.setExact(…, OnAlarmListener, handler)`, the listener form the exact-alarm restriction exempts. Rings while the app is alive, which a call is | `unavailable` |
| `alarm` | timers-alarms | `at` `"HH:MM"`, 24-hour, strict | `id`, `at`, `in_ms`, `undoable` | **yes** — cancels it; lives until it rings | the same, at a wall-clock time (`RTC_WAKEUP`) | `unavailable` |
| `dnd` | dnd-volume | `on` boolean | `id`, `on` (the filter actually in effect), `previous`, `undoable` | **yes** — restores the previous filter; superseded by the next `dnd`, or 10 minutes | **Do Not Disturb policy access**, a special access only the owner can give in Android's own screen | `permission`, `unavailable` |
| `volume` | dnd-volume | `level` integer 0–100, **or** `direction` `up`/`down` (one stream step, not one percent) | `id`, `level` (read back), `previous`, `undoable` | **yes** — restores the previous level; superseded by the next `volume`, or 10 minutes | `MODIFY_AUDIO_SETTINGS`, normal, granted at install. Some devices refuse a stream change while Do Not Disturb owns the stream | `unavailable` |
| `play` | media | — | `dispatched` | **no.** This is the row to read twice: "play after pause" is not an undo, and the phone does not pretend it is. It cannot even see whether playback changed | `AudioManager.dispatchMediaKeyEvent` with `KEYCODE_MEDIA_PLAY`. **No permission, no notification-listener access.** The key goes to whichever session the platform considers active | `unavailable` |
| `pause` | media | — | `dispatched` | **no** — the same reasoning as `play` | the same call with `KEYCODE_MEDIA_PAUSE` | `unavailable` |
| `skip` | media | `direction` `next` (default) or `previous` | `direction`, `dispatched` | **no** — there is no "unskip" the phone can perform | the same call with `KEYCODE_MEDIA_NEXT` / `KEYCODE_MEDIA_PREVIOUS` | `invalid-arguments`, `unavailable` |
| `now_playing` | media | — | `known`, `playing`, `package`, `title`, and `note` when `known` is false | **no** — reporting only | **nothing beyond the ordinary APIs.** `playing` is `AudioManager.isMusicActive`. `package`/`title` need a `MediaSessionManager` session list, which is a notification-listener special access this app does **not** hold, so they are `null` and `known` is `false` | none of its own: it always answers, with `known:false` and `null` where it cannot see |
| `state` | device-state | — | `battery_percent`, `charging_source`, `charging_status`, `screen_on`, `network`, `tailnet_present`, `free_storage_bytes`, `media_volume`, `dnd`, `timezone`, `utc_offset_minutes`, `time` — each `null` when the platform will not say | **no** — read-only | battery and charging: the sticky `ACTION_BATTERY_CHANGED` intent; screen: `PowerManager.isInteractive`; network: `ACCESS_NETWORK_STATE`; tailnet: interface enumeration (`100.64.0.0/10`); storage: `StatFs` on the data directory; volume: media stream; `dnd`: the same special access `dnd` needs, else `null`; time: the device's own zone and offset. **No new permission** | none of its own: it always answers, and an unreadable field is `null` |
| `clip` | clipboard | `text` string, 1 – 2 000 characters | `chars` — the length only | **no** — a clipboard write cannot be reversed; the phone does not keep what it replaced | `ClipboardManager.setPrimaryClip`. **No permission**, but Android refuses a write from an app that is not in the foreground, which arrives as `unavailable` | `unavailable` |
| `undo` | — (always offered) | `id` | `undone`, `state` (`cancelled` / `restored`) | — | nothing: it reverses through the same platform calls the action used | `expired`, `unavailable` |
| `status` | — (always offered) | — | `offered`, `tiers`, `grants`, `running`, `notes`, `registry`, `dnd`, `volume` | **no** — reporting only | nothing | — |

`undo` and `status` are not a capability: `undo` only reverses something the owner already allowed,
and `status` only reports. They are therefore always advertised, even with every group Off.

### Media, precisely

`play`, `pause` and `skip` **dispatch a platform media key** and nothing else. They act on
whatever session Android considers active; the phone does not choose a player, does not start one,
and does not hold a notification listener. The result's `dispatched` is exactly that: the audio
service accepted the key event. It is **not** a claim that a track started, a track stopped, or a
track changed — the phone cannot see playback state without a listener, and it does not invent one.

`now_playing` is deliberately honest about the same limit:

```json
{"ok":true,"action":"now_playing","tier":0,"known":false,"playing":true,"package":null,
 "title":null,"note":"the phone can see that audio is playing, but not which app or what it is: that needs notification-listener access, which this app does not hold"}
```

- `known:true` (with a package and a title) is only ever returned when the platform will say, which
  today it will not; the field exists so a later lane can fill it without changing the shape.
- `playing` is `isMusicActive` — a boolean the phone is allowed to read, and `null` when even that
  cannot be read.
- `package` and `title` are `null` when `known` is `false`. **The phone never invents a title.**

`skip`'s `direction` defaults to `next` when omitted; any other value is `invalid-arguments`. It
shares the `direction` property with `volume`, so the schema lists `up`, `down`, `next` and
`previous`, and each action accepts only its own pair.

### Device state, and the null rule

`state` returns **one object with every field present**; a field the platform will not answer is
`null`, never a guess and never a zero:

| field | value | `null` means |
|---|---|---|
| `battery_percent` | 0–100 | the battery broadcast was unavailable |
| `charging_source` | `ac`, `usb`, `wireless`, `dock`, `none` | the platform would not say. `none` is a real answer: it was read, and the phone is not plugged in |
| `charging_status` | `charging`, `discharging`, `full`, `not-charging` | the platform would not say |
| `screen_on` | boolean | `PowerManager` would not answer |
| `network` | `wifi`, `cellular`, `ethernet`, `vpn`, `other`, `none` | the platform would not say. `none` is a real answer: connected to nothing |
| `tailnet_present` | boolean | the interfaces could not be enumerated |
| `free_storage_bytes` | integer | the data directory's free space could not be read |
| `media_volume` | 0–100 | the media stream could not be read |
| `dnd` | `on`, `off` | Do Not Disturb policy access is not held, so the filter is not readable |
| `timezone` | e.g. `Europe/Berlin` | there is no device zone (effectively never) |
| `utc_offset_minutes` | integer, east of UTC positive | the offset could not be read, which also makes `time` `null` |
| `time` | local `YYYY-MM-DDTHH:MM:SS`, built from the injected clock and that offset | the offset is unknown |

`state` is read-only and changes nothing. It reads no file, lists nothing, and reports no serial
number, IMEI, phone number or account.

### The clipboard writes, and never reads

`clip` puts `text` on the clipboard and reports only its length (`chars`).

The group's line — *It never reads what is already there* — is a rule, not a preference:

**the phone tool never reads the clipboard.**

`getPrimaryClip` appears nowhere in `PhoneTool`, `PhoneEngine`, `PhoneGrants`, `LocalActions`,
`PhoneMcp` or `PhoneMcpServer`, and `PhoneSurfaceTest` asserts exactly that at source level, so a
later lane cannot quietly add a read.

The audit row for a clip stores `text=NN chars`, never the text — the same rule as a note's body.

---

## Tier 1 — reported

Executes when the owner has put the group at **Ask** — turning a reported group on is the grant —
and is reported to the mind on the next turn through the presence context and in the audit;
undoable where the row says so.

| action | group | arguments | result fields | undo | what it needs from the OS | refusals |
|---|---|---|---|---|---|---|
| `note` | notes | `text` string, 1 – 500 characters | `id`, `text`, `reported_next_turn`, `undoable` | **yes** — deletes it; lives 24 hours, because the note outlives the turn | none: private app storage | `unavailable` |
| `file_list` | files | optional `folder` `Downloads` (the default) or `Documents`; optional `path`, a **relative** subdirectory (`""` = the folder itself); optional `depth` integer 1–5 (default 1); optional `pattern`, a case-insensitive glob on the file name (`*.pdf`); `offset` integer ≥ 0 (default 0) and `limit` integer 1–1000 (default 200) | `folder`, `path`, `depth`, `count`, `has_more`, `next_offset`, `entries` — each entry `name`, `path` (relative), `kind` (`file`/`dir`), `bytes` (null for a directory), `modified` (local ISO-8601, null when the provider will not say), `mime` (files, when offered) | **no** — listing reads directory metadata and changes nothing | **Storage Access Framework only**, inside the same granted tree as `file_read`; the child query is `DocumentsContract` metadata, never a file's contents. No storage permission, and `MANAGE_EXTERNAL_STORAGE` is never used | `no_folder`, `bad_path`, `unavailable` |
| `file_read` | files | `name` string (the file's name, not a path), optional `folder` `Downloads` (the default) or `Documents`, optional `offset` (bytes, ≥ 0) and `length` (bytes, ≥ 1; default 65 536) | whole file: `name`, `folder`, `bytes`, `chars`, `text`. A window: the same plus `offset`, `length` and `truncated` — with `bytes` the file's **total** size | **no** — reading changes nothing, and a read cannot be undone because it did not change anything | **Storage Access Framework only.** The owner grants one folder through `ACTION_OPEN_DOCUMENT_TREE`, the grant is persisted, and the sheet names the folder it got. No storage permission is declared, and `MANAGE_EXTERNAL_STORAGE` is never used. Text only | `no_folder`, `bad_path`, `not_text`, `too_big` (whole-file only), `unavailable` |
| `file_offer` | files | the same `name` and `folder` | `name`, `folder`, `mime`, `bytes`, `sha256`, `url`, `expires_at` | **no** — offering changes nothing on the phone | the same granted folder, then the existing file bridge ([FileOffer](../android/app/src/main/java/com/hermesvox/FileOffer.kt) / [FileStage](../android/app/src/main/java/com/hermesvox/FileStage.kt)) over the tailnet. The result is a capability URL, not a base64 blob | `no_folder`, `bad_path`, `too_big`, `unavailable` |
| `agenda` | calendar | — | `count`, `events` — each `start`, `end`, `title`, `calendar`, and `null` where the provider will not say | **no** — reading changes nothing | `READ_CALENDAR`, a runtime permission asked when the group is turned on; [CalendarContract](https://developer.android.com/reference/android/provider/CalendarContract) only | `permission`, `unavailable` |
| `event_create` | calendar | `title` string ≤ 200 characters, `start` local ISO-8601 `YYYY-MM-DDTHH:MM` (seconds optional), `duration_min` integer 1 – 1 440, optional `calendar` name | `id`, `title`, `start`, `end`, `duration_min`, `start_ms`, `end_ms`, `calendar`, `undoable` | **yes** — deletes the event it created; lives until the event starts, or ten minutes, whichever is longer | `WRITE_CALENDAR` (and `READ_CALENDAR`, to find a writable calendar), runtime permissions asked when the group is turned on; `CalendarContract` only | `permission`, `unavailable`, `invalid-arguments` |

**Files, precisely.** A file's name is a **name, not a path**: `..`, an absolute path, a separator and
a URI are refused `bad_path` before the platform is asked, and a document that resolves outside the
granted tree is refused the same way afterwards. A listing's `path` is the other shape: a **relative**
subdirectory inside the same tree, segment-checked before the provider is asked and re-checked against
the document id it answers with. On the primary shared volume the document id also names a real path,
so a symlink that leaves the tree is canonicalised and refused; for a provider with no filesystem path
(a cloud tree) the document-id check is the whole check, which is stated here rather than pretended
otherwise.

**Listing is inside the granted tree, depth-limited and paged — not a broad scan.** `file_list` walks
the folder the owner granted, and only that folder: no second tree, no `MANAGE_EXTERNAL_STORAGE`, and
no provider the owner did not pick. `depth` is 1 by default (that folder's own entries) and at most 5;
a deeper listing also lists each subdirectory's entries, and every entry carries the relative `path`
it was found at. Directories come before files at each level and each group is alphabetical, so the
order is stable across pages. `pattern` filters **files** by name and never hides a directory.
`offset` and `limit` are **pagination, not a cap on the truth**: the walk enumerates the ordered set
and a page is a window on it, so a folder with more entries than a page answers `has_more: true` and
the `next_offset` to ask for — it is never refused for being large and never silently cut. A missing
subdirectory is `unavailable`; a path, a name or a symlink that leaves the tree is `bad_path`.
**Listing never opens a file**: it reads names, kinds, sizes, modified times and MIME types through
the provider's child query, and `PhoneFileListTest` pins that at source level.

**The 64 KiB figure is a window size, not a refusal to read.** `file_read` returns at most
65 536 bytes of text in one answer. Above that, a whole-file read says `too_big` and names the way in
— `offset` and `length` — and a windowed read returns that slice with `truncated: true`, the `offset`
it actually started at, and the file's total `bytes`, so the mind can keep asking until it has what
it needs. `truncated` is false only when the returned text is the entire file. A window is cut at byte
offsets, so it snaps to UTF-8 character boundaries: up to three leading continuation bytes are skipped
and an incomplete trailing sequence is trimmed, and the result reports where the text really starts
rather than pretending the cut was clean. `file_offer` has no such cap because it does not put the
bytes in the result: it keeps the bridge's own 100 MB.

**Calendar, precisely.** `agenda` reads today's events in the phone's own zone and returns only
start, end, title and calendar name — no attendees, no description, no contact lookup. `start` is
local ISO-8601: a trailing `Z` or an offset is refused rather than converted, because "local" is
the contract and a conversion would move the event. `duration_min` is minutes; zero, negatives and
anything over a day are refused `invalid-arguments`. A phone with no writable calendar is
`unavailable`, which is the truth rather than a silent export. The undo deletes the event it
created and nothing else.

**Built in lane 2:** `files` and `calendar`. Their actions are advertised only while the group is
on, and both groups are reported: turning one on is the grant. `delete`, a file delete that cannot
be undone, remains a Tier 2 name and is deliberately not built or advertised.


---

## Tier 2 — approval

The phone **never executes** a Tier 2 action from the MCP wire. The result says
`approval-required` and names the surface that owns it — the gateway's approval cards, or the
owner's own hand. An MCP call carries no run id, so the phone cannot mint a card from this end;
building a second approval surface inside the MCP server is exactly what #421 rules out.

| action | group | arguments (the contract, when it exists) | result (when it lands) | undo | what it would need from the OS | refusals |
|---|---|---|---|---|---|---|
| `notifications` | notifications | — | the notifications currently on the phone | **no** | **notification-listener special access**, which also reads every code and message on the phone | `permission`, `approval-required` |
| `message` | messages-calls | `to` string, `text` string ≤ 1 000 | `to`, `sent` | **no** — a sent message cannot be unsent | `SEND_SMS`, and the default-SMS-role on modern Android | `permission`, `approval-required` |
| `call` | messages-calls | `to` string | `number`, `dialled` | **no** — a placed call cannot be unplaced | `CALL_PHONE`, a runtime permission | `permission`, `approval-required` |
| `send` | messages-calls | `to` string, `text` string | `to`, `sent` | **no** | the same as `message`, for a channel the owner picks | `permission`, `approval-required` |
| `email` | messages-calls | `to` string, `subject` string, `body` string | `to`, `sent` | **no** | a mail client the owner hands the draft to | `permission`, `approval-required` |
| `launch` | apps | `app` string (package or label) | `app`, `opened` | **no** — bringing an app to the front is not reversible in any useful sense | a launcher intent; no permission | `approval-required`, `unavailable` |
| `open` | apps | `app` string, `uri` string | `app`, `opened` | **no** | a deep link the target app claims | `approval-required`, `unavailable` |
| `install` | apps | `app` string | `app`, `installed` | **no** | the package installer's own confirmation, which the owner answers | `approval-required`, `unavailable` |
| `uninstall` | apps | `app` string | `app`, `removed` | **no** — an uninstall always needs the owner, and is never silent | `ACTION_DELETE` on the package, with the system's confirmation | `approval-required`, `unavailable` |
| `contact` | contacts | `name` string | `name`, `number` | **no** — reading changes nothing | `READ_CONTACTS`, a runtime permission; the lookup is by name and returns one number | `permission`, `approval-required` |

**Declared, not built:** `notifications`, `messages-calls`, `apps` and `contacts`. `PhoneGrants`
already names `message`, `call`, `send`, `email`, `launch`, `open`, `install` and `uninstall` in
those groups' `planned` lists, so a direct call is refused with the group's own name instead of
"unknown action"; none is advertised, because a promise in an `enum` is a capability the phone must
be able to keep.

---

## The twelve groups

| group | tier | actions | state |
|---|---|---|---|
| `timers-alarms` | 0 | `timer`, `alarm` | **built** |
| `dnd-volume` | 0 | `dnd`, `volume` | **built** |
| `notes` | 1 | `note` | **built** |
| `media` | 0 | `play`, `pause`, `skip`, `now_playing` | **built in lane 1** |
| `device-state` | 0 | `state` | **built in lane 1** |
| `clipboard` | 0 | `clip` | **built in lane 1** |
| `files` | 1 | `file_list`, `file_read`, `file_offer` | **built in lane 2; listing added by the listing lane** |
| `calendar` | 1 | `agenda`, `event_create` | **built in lane 2** |
| `notifications` | 2 | `notifications` | **declared, not built** |
| `messages-calls` | 2 | `message`, `call`, `send`, `email` | **declared, not built** |
| `apps` | 2 | `launch`, `open`, `install`, `uninstall` | **declared, not built** |
| `contacts` | 2 | `contact` | **declared, not built** |

---

## Undo: only where reversal is real

| action | undo does | lives | why it is not offered when it is not |
|---|---|---|---|
| `timer` / `alarm` | cancels it | until it rings | the action is the arming; cancelling is its exact reverse |
| `dnd` / `volume` | restores the previous filter / level | until the next change of the same setting, or 10 minutes | a second change makes the older "previous" untrue |
| `note` | deletes it | 24 hours | the note outlives the turn that made it |
| `file_list` / `file_read` / `file_offer` | — | — | listing, reading a file or handing it to the agent changes nothing on the phone, so there is nothing to reverse. A read cannot be undone, and the doc says so rather than inventing one |
| `agenda` | — | — | reporting changed nothing |
| `event_create` | deletes the event it created | until the event starts, or ten minutes, whichever is longer | deleting the one event the phone inserted is its exact reverse; it never touches another event |
| `play` / `pause` / `skip` | — | — | **`play` after `pause` is not an undo.** It is another key press into whatever the session is doing now; the phone has no "previous playback state" it could honestly restore, and it cannot see playback at all without a listener |
| `now_playing` / `state` | — | — | reporting changed nothing |
| `clip` | — | — | a clipboard write cannot be reversed: the phone would have to have kept what it replaced, and it deliberately does not read the clipboard |
| `undo` / `status` | — | — | neither is a capability |
| every Tier 1/2 write | as the row says | — | where the table says "no", there is no reversal and no invented one |

`undo` of an id with no live undo is `ok:false, reason:"expired"`. It never guesses.

---

## The audit

Every call the mind makes — success **and** refusal — writes one row through
[`PhoneAudit`](../android/app/src/main/java/com/hermesvox/PhoneAudit.kt): time, action, the
arguments summarised, the outcome, and the undo id when there is a real one.

Arguments are **summarised, never copied**: a `note` or `clip` `text` and an `event_create` `title`
are stored as `NN chars`; a duration, a clock time, a boolean, a level, a direction, an id, a file
name or a folder is stored as itself, clipped to one line. A `file_list` row is the folder, the
relative path and the count — `Downloads, 12 entries` — and **never a name it saw**, because the
names in a folder are the owner's. Nothing in this surface logs a secret, a clipboard payload, a
file's contents or a calendar's contents — a `file_read` row carries the name and folder it asked
for, never the text it got back, and an event row carries the title's length, never the title. The
audit's one summarising function is the place a future action has to pass through.

---

## What a device pass should show

1. **Every group Off (fresh install).** `tools/list` offers `undo` and `status` only; `play`,
   `state` and `clip` are all `ok:false, reason:"not_granted"` and the detail names Media, Device
   state and Clipboard.
2. **Media = Ask.** `play` is in the `enum`; a call returns `approval-required` and the music keeps
   playing or keeps stopping — nothing is sent.
3. **Media = Allow, audio paused.** `play` → `ok:true, dispatched:true`, and the player resumes.
   `pause` → the player stops. `skip` with no `direction` → the next track; with
   `direction:"previous"` → the previous one. Each result carries `undoable:false` and **no** `undo`
   object.
4. **`now_playing` on any phone.** `ok:true, known:false`, `package` and `title` `null`, `note`
   saying the phone cannot see which app or what it is; `playing` is `true` while audio is audible
   and `false` otherwise. The title must never be invented, and no "notification access" prompt
   appears.
5. **`state` on a charging phone.** `battery_percent` matches Android's own battery figure,
   `charging_source` is `ac`/`usb`/`wireless`, `charging_status` is `charging`, `screen_on` follows
   the screen, `network` is `wifi` or `cellular` (and `none` in aeroplane mode), `tailnet_present` is
   `true` with Tailscale up, `free_storage_bytes` matches Settings' free space within a rounding,
   `media_volume` matches the volume slider, and `time` is the phone's own clock.
6. **`state` without Do Not Disturb access.** `dnd` is `null`, and every other field is still
   filled. Nothing is guessed as "off".
7. **`clip` then paste by hand.** The text is on the clipboard, the result says `ok:true` with
   `chars` only, Recent actions shows `text=NN chars`, and the clipboard's previous contents are not
   anywhere in the app.
8. **`clip` from a backgrounded phone** (a call that lost focus). `ok:false,
   reason:"unavailable"`, and nothing was written.
9. **Files with no folder granted.** The sheet says *No folder granted*; `file_list`, `file_read` and
   `file_offer` are `ok:false, reason:"no_folder"` and say to pick a folder in Phone tools → Files.
   Turning Files on opens the system folder picker, and after picking, the sheet names the folder it
   got (`Granted · Download`). No storage permission prompt ever appears.
10. **Files with a folder granted.** `file_list` on a folder with subdirectories answers `folder`,
    `path:""`, `depth:1`, `count`, `has_more:false` and one entry per child: directories first
    (`kind:"dir"`, `bytes:null`), then files alphabetically with their sizes, `modified` when the
    provider gives it and `mime` for files. `depth:2` adds `"reports/2026"`-shaped paths for the
    children of each subdirectory; `depth:1` does not. `pattern:"*.pdf"` leaves the directories and
    only the PDFs. A folder of 500 files at `limit:100` answers `has_more:true` and
    `next_offset:100`, and walking the offsets returns every entry exactly once; it does not refuse.
    A `file_read` of a text file returns `text`, `chars` and `bytes` with no `undo`; a 70 KB file is
    `too_big` and names `offset`/`length`, and `offset:0, length:4096` returns the first 4 KiB with
    `truncated:true` and `bytes:70000`. A PNG is `not_text`; `name:"../secret.txt"`,
    `path:"../Documents"` and an absolute path are `bad_path` and never read. `file_offer` returns a
    `url`/`sha256` that the agent can fetch, with no bytes in the result.
11. **Calendar without permission.** The sheet says *Not granted* and names
    `android.permission.READ_CALENDAR`; `agenda` and `event_create` both refuse `permission`, and
    nothing is created. Turning Calendar on asks for both permissions and the sheet shows the
    answer.
12. **Calendar with permission.** `agenda` lists today's events with start, end, title and calendar
    only — no attendees, no description; `event_create` with `start:"2026-11-05T09:30"` and
    `duration_min:30` returns an `id`, and the event appears in the phone's own calendar app. Its
    `undo` deletes that event and nothing else; Recent actions shows the title as `NN chars`.
13. **Recent actions.** One row per call, successes and refusals, with the summarised arguments and
    the outcome; no row carries a note's text, a clipboard payload, a file's contents, an event's
    title or a file path's contents.
14. **Revoke mid-call.** Turn Media Off while the call is live: the next `tools/list` no longer
    lists `play`, and the next call is `not_granted` with no key dispatched.
15. **The declared groups stay refused.** With Messages and calls at Ask, `message` returns
    `approval-required` and nothing is sent; `file_list` and `file_read` do the same while Files is
    Off. No lane may make these advertise before the phone can keep the promise.
