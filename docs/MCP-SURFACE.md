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
**Declared, not built:** `files`, `calendar`, `notifications`, `messages-calls`, `apps`, `contacts`.

---

## One tool, and how an action is offered

There is **one** tool, `phone`, whose single required argument is `action`. The schema's `enum` is
built from [PhoneGrants](../android/app/src/main/java/com/hermesvox/PhoneGrants.kt) on every
`tools/list`, and from the same registry on every `tools/call`:

| grant | the schema | a call that names it |
|---|---|---|
| **Off** (the default for every group) | the group's actions are **absent** from the `enum` | `ok:false, reason:"not_granted"`, and the detail names the group the owner must grant |
| **Ask** | offered | `ok:false, reason:"approval-required"`; the owner's approval card owns it and nothing is performed |
| **Allow** | offered | executed, audited, and reported |

**Allow exists for Tier 0 only.** A stored Allow on a Tier 1 or Tier 2 group is read back as Ask: a
grant that cannot be given is not a grant. `undo` and `status` belong to no group and are always
offered, because a phone whose grants are all Off must still be able to say so.

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
| `now_playing` | media | — | `known`, `playing`, `package`, `title`, and `note` when `known` is false | **no** — reporting only | **nothing beyond the ordinary APIs.** `playing` is `AudioManager.isMusicActive`. `package`/`title` need a `MediaSessionManager` session list, which is a notification-listener special access this app does **not** hold, so they are `null` and `known` is `false` | `unavailable` |
| `state` | device-state | — | `battery_percent`, `charging_source`, `charging_status`, `screen_on`, `network`, `tailnet_present`, `free_storage_bytes`, `media_volume`, `dnd`, `timezone`, `utc_offset_minutes`, `time` — each `null` when the platform will not say | **no** — read-only | battery and charging: the sticky `ACTION_BATTERY_CHANGED` intent; screen: `PowerManager.isInteractive`; network: `ACCESS_NETWORK_STATE`; tailnet: interface enumeration (`100.64.0.0/10`); storage: `StatFs` on the data directory; volume: media stream; `dnd`: the same special access `dnd` needs, else `null`; time: the device's own zone and offset. **No new permission** | `unavailable` |
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

`clip` puts `text` on the clipboard and reports only its length (`chars`). The group's line — *It
never reads what is already there* — is a rule, not a preference: **no code path in the phone tool
reads the clipboard**, and `getPrimaryClip` appears in none of `PhoneTool`, `PhoneEngine`,
`PhoneGrants`, `LocalActions`, `PhoneMcp` or `PhoneMcpServer`. `PhoneSurfaceTest` asserts that at
source level, so a later lane cannot quietly add a read.

The audit row for a clip stores `text=NN chars`, never the text — the same rule as a note's body.

---

## Tier 1 — reported

Executes only when the owner has put the group at Ask and approved that use; reported to the mind on
the next turn through the presence context; undoable.

| action | group | arguments | result fields | undo | what it needs from the OS | refusals |
|---|---|---|---|---|---|---|
| `note` | notes | `text` string, 1 – 500 characters | `id`, `text`, `reported_next_turn`, `undoable` | **yes** — deletes it; lives 24 hours, because the note outlives the turn | none: private app storage | `unavailable` |
| `read` | files | `path` string, relative to Downloads or Documents | `path`, `chars`, `text` | **no** — reading changes nothing | **Storage Access Framework / a user-picked document.** No broad storage permission is declared; the owner picks the file, the app reads that one file | `permission`, `unavailable`, `invalid-arguments` |
| `share` | files | `path` string | `path`, `name` | **no** — offering a file to the agent's own server changes nothing on the phone | the same file access, plus the file-offer server's tailnet bind | `permission`, `unavailable` |
| `delete` | files | `path` string | `path` | **no** — a delete is irreversible and this action does not exist to pretend otherwise; if it lands, it is Tier 2 and permanently non-undoable | the same file access, and a delete is **not** done from Tier 1 | `permission`, `unavailable` |
| `events` | calendar | `day` `today` (default) | `events` (list of `start`, `end`, `title`, `id`) | **no** — reading changes nothing | `READ_CALENDAR`, a runtime permission | `permission`, `unavailable` |
| `event` | calendar | `title` string, `at` `"HH:MM"`, `duration_min` integer | `id`, `at`, `title` | **yes** — deletes the event it created; lives until it starts | `WRITE_CALENDAR`, a runtime permission | `permission`, `unavailable`, `invalid-arguments` |

**Declared, not built:** `files` and `calendar`. The group, its tier, its `lets`, its warning (files)
and its refusal exist in `PhoneGrants`; no action above is advertised or executable, and a direct
call is refused as `not_granted` (Off) or `approval-required` (Ask). The action names are the ones
the next lane implements; they are written here so that lane has a contract rather than a guess.

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
| `files` | 1 | `read`, `share`, `delete` | **declared, not built** |
| `calendar` | 1 | `events`, `event` | **declared, not built** |
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

Arguments are **summarised, never copied**: a `note` or `clip` `text` is stored as `NN chars`; a
duration, a clock time, a boolean, a level, a direction, an id or a path is stored as itself, clipped
to one line. Nothing in this surface logs a secret, a clipboard payload or a file's contents — there
is no argument here that is one, and the audit's one summarising function is the place a future
action has to pass through.

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
9. **Recent actions.** One row per call, successes and refusals, with the summarised arguments and
   the outcome; the three new groups' rows never carry a title, a clipboard payload or a storage
   path's contents.
10. **Revoke mid-call.** Turn Media Off while the call is live: the next `tools/list` no longer
    lists `play`, and the next call is `not_granted` with no key dispatched.
11. **The declared groups stay refused.** With Messages and calls at Ask, `message` returns
    `approval-required` and nothing is sent; `read` does the same while Files is Off. No lane may
    make these advertise before the phone can keep the promise.
