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
**Added by the handoff lane:** `messages`, `calls`, and the safe half of `apps` (`launch`, `open`).
**Added by the read lane:** `contacts` (`contact`) and `notifications` (`notifications`,
`notification_read`). Both are instant and both offer Allow; the notification listener is
call-scoped, and its summary-then-read split is the privacy design, not an implementation detail.
**Added by the approval lane:** `apps`' `uninstall` and `files`' `file_offer` — the two Tier 2
actions with cards on this phone, raised by
[_PhoneApproval_](../android/app/src/main/java/com/hermesvox/PhoneApproval.kt). The card's
notification is private and cannot be approved while the phone is locked.
**Declared, not built:** `apps`' `install`.

---

## One tool, and how an action is offered

There is **one** tool, `phone`, whose single required argument is `action`. The schema's `enum` is
built from [PhoneGrants](../android/app/src/main/java/com/hermesvox/PhoneGrants.kt) on every
`tools/list`, and from the same registry on every `tools/call`:

| grant | the schema | a call that names it |
|---|---|---|
| **Off** (the default for every group) | the group's actions are **absent** from the `enum` | `ok:false, reason:"not_granted"`, and the detail names the group the owner must grant |
| **Ask**, Tier 0 | offered | `ok:false, reason:"approval-required"`; the owner's approval card owns it and nothing is performed |
| **Ask**, Tier 2 `uninstall` or `file_offer` | offered | the two-step card handshake below: a request raises the card, then the same call plus `approval` executes |
| **Ask**, any other Tier 2 | offered | `ok:false, reason:"approval-required"`; no card exists for it and nothing is performed |
| **Ask**, Tier 1 (reported) | offered | executed, audited, and reported — turning a reported group on *is* the grant |
| **Allow** | offered | executed, audited, and reported |

**Allow exists for Tier 0 only.** A stored Allow on a Tier 1 or Tier 2 group is read back as Ask: a
grant that cannot be given is not a grant. **Ask is not one behaviour:** a Tier 0 group at Ask waits
for the owner's card, and a Tier 2 group at Ask waits for a card on every use; a Tier 1 group at Ask
runs, because the owner has already turned it on and sees what it did. The apps group is Tier 0
because `launch`/`open` are, but its `uninstall` action keeps its own Tier 2 rule: a grant to open
apps is not a grant to remove one. `undo` and `status` belong to no group and are always offered,
because a phone whose grants are all Off must still be able to say so.

**A refusal is a result, not a transport error.** `isError` stays `false`; the JSON carries `ok`,
`action`, `tier`, `reason` and `detail`. `ok:true` is only ever written after the platform said it
performed the action, and a refusal never carries an `id`, a success fact or an `undo`.

### The tiers

| tier | name | the promise | states |
|---|---|---|---|
| **0** | instant | executes now, private, and reversible *where reversal is real* | Off / Ask / **Allow** |
| **1** | reported | acts now when granted, is reported to the mind on the next turn, and is undoable | Off / Ask |
| **2** | approval | **never executes without the owner's card.** `uninstall` and `file_offer` have cards on this phone; the rest still answer `approval-required` | Off / Ask |

### Why these hand off

`message` and `dial` are not "send" and "call" with a confirmation. **Vox composes; the owner's own
app acts.** A text is `ACTION_SENDTO` with an `smsto:` URI — never `SEND_SMS`, and Vox never asks to
be the default SMS app. Two-factor codes therefore reach the agent the way the owner already handles
them: copy the code, and let the `clipboard` group read it. A call is `ACTION_DIAL` with the number
filled in — never `CALL_PHONE` — and because Vox never dials, no emergency-number special case is
needed or wanted. Dialling **ends Vox's own call at the handoff**, after one spoken line, because the
dialer is about to take the audio route; it does **not** end the conversation, which lives on the
gateway and is resumed on the next call. The same reasoning keeps `apps` split: `launch`/`open` are
instant, while `uninstall` is irreversible and has no app to hand it to. `file_offer` is the other
consequential one: it cannot be handed off either, because the agent is the recipient and the
export is the act. Those two are the actions with approval cards of their own (below).

### The reason vocabulary

| `reason` | meaning |
|---|---|
| `not_granted` | the action's group is Off; the action is not offered and was not performed |
| `approval-required` | the group is Ask, or the action is Tier 2; the owner's approval owns it and nothing was done. For `uninstall` and `file_offer` it carries `approval_id`, `expires_at` and `summary` |
| `approval_denied` | the owner answered an `uninstall` or `file_offer` card with no; nothing was removed or exported, and the id is burnt |
| `approval_expired` | the card was not answered within two minutes (silence is no); nothing was removed or exported |
| `approval_mismatch` | the id was approved for one app or file and the call names another; nothing was removed or exported |
| `approval_unknown` | no such approval id, or it has already been used; nothing was removed or exported |
| `approval_locked` | the card was answered while the phone was locked. The yes was refused, the id is burnt, and nothing was removed or exported. Unlock the phone and ask again |
| `approval_busy` | three cards were already waiting, so no fourth was raised; nothing was removed or exported |
| `permission` | the phone does not hold a permission or special access the action needs |
| `no_folder` | no folder has been granted with the system picker, so there is nothing to read inside |
| `bad_path` | the name is a path, contains `..`, or resolves outside the folder the owner granted |
| `bad_uri` | `open` was given a URI it will not hand to a viewer: `intent:`, `file:`, or no scheme at all. Nothing was resolved and no intent was built |
| `not_text` | the file's bytes are not text; `file_read` returns text only |
| `too_big` | a whole-file `file_read` is above the 64 KiB default; ask again with `offset`/`length` to read it in windows |
| `unavailable` | the action was recognised and the platform refused, failed, or would not say |
| `invalid-arguments` | the arguments do not describe a runnable action (bounds and shapes are below) |
| `expired` | no undo is registered for that id any more |
| `no_app` | nothing on the phone answers the intent: no `ACTION_SENDTO` app, no dialer, no launchable activity, or no app for the URI |
| `unknown-id` | `notification_read` was given an id that no summary of this call has shown, or the notification has left the phone since |
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
| `now_playing` | media | — | `known`, `playing`, `package`, `title`, and `note` when `known` is false | **no** — reporting only | `playing` is `AudioManager.isMusicActive`. `package`/`title` need a `MediaSessionManager` session list, which needs **notification-listener special access**; the sheet offers that grant and opens Android's screen. With it they are the session's own values and `known` is `true`; without it they are `null` and `known` is `false` | none of its own: it always answers, with `known:false` and `null` where it cannot see |
| `state` | device-state | — | `battery_percent`, `charging_source`, `charging_status`, `screen_on`, `network`, `tailnet_present`, `free_storage_bytes`, `media_volume`, `dnd`, `timezone`, `utc_offset_minutes`, `time` — each `null` when the platform will not say | **no** — read-only | battery and charging: the sticky `ACTION_BATTERY_CHANGED` intent; screen: `PowerManager.isInteractive`; network: `ACCESS_NETWORK_STATE`; tailnet: interface enumeration (`100.64.0.0/10`); storage: `StatFs` on the data directory; volume: media stream; `dnd`: the same special access `dnd` needs, else `null`; time: the device's own zone and offset. **No new permission** | none of its own: it always answers, and an unreadable field is `null` |
| `clip` | clipboard | `text` string, 1 – 2 000 characters | `chars` — the length only | **no** — a clipboard write cannot be reversed; the phone does not keep what it replaced | `ClipboardManager.setPrimaryClip`. **No permission**, but Android refuses a write from an app that is not in the foreground, which arrives as `unavailable` | `unavailable` |
| `clip_read` | clipboard | — | `text`, `chars` | **no** — reading changes nothing | `ClipboardManager.getPrimaryClip`. **No permission**, but **the platform decides**: since Android 10 an app that is not in front gets no clipboard, and an empty clip is no answer. Both are `unavailable` with the platform's own reason; the phone never returns empty text as though it were the clipboard | `unavailable` |
| `notifications` | notifications | — | `sensitive: true`, `count`, `apps` — each `app`, `count`, `latest_at` (epoch millis), and opaque `ids` | **no** — reading changes nothing | **Notification-listener special access**, shared with `now_playing`; the system grant is global, but the app's **use** of it is call-scoped: the board attaches when a call starts, detaches when it ends, and drops every record and id. **Vox's own notifications are excluded** (an app reading itself through the listener is a loop). Many notifications → many app rows, but **no title and no text** | `unavailable` (no live call), `permission` (listener access not granted) |
| `notification_read` | notifications | `id` — one of the ids a `notifications` summary returned | `sensitive: true`, `app`, `posted_at` (epoch millis), `title`, `text` | **no** — reading changes nothing | The same call-scoped listener. This is the deliberate **second step**: one notification, named by an id a summary already showed, never a browse or a vacuum. An id that arrived after the summary is `unknown-id` until a fresh summary shows it | `unavailable`, `permission`, `unknown-id` |
| `message` | messages | `to` — a phone number, or a contact name when Contacts is on; `text` 1 – 1 000 characters | `to`, `chars`, `handed_off: true` | **no** — Vox composes; the owner's app sends, and a sent text cannot be recalled | `ACTION_SENDTO` with an `smsto:` URI and `sms_body`. **No permission, no `SEND_SMS`, no default-SMS role.** The result says the draft was handed off, never that it was sent | `no_app`; `not_granted` naming **Contacts** when `to` is a name and that group is Off; `permission` when Contacts is on but `READ_CONTACTS` is not held |
| `dial` | calls | `to` — a number, or a contact name when Contacts is on | `to` | **no** — opening the dialer changes nothing; the owner's call is theirs | `ACTION_DIAL` with a `tel:` URI. **No `CALL_PHONE`, no emergency special case**, because Vox never dials: the owner presses call. With a live Vox call, the being says *dialling — ending our call*, the call is ended through the same teardown the hang-up uses, and then the dialer opens | `no_app`; `not_granted` naming **Contacts** for a name without that group |
| `launch` | apps | `app` — a package, or the label the launcher shows | `package`, `component` | **no** — bringing an app to the front is not a reversal | `PackageManager` resolution among **launchable** activities only (`ACTION_MAIN` / `CATEGORY_LAUNCHER`), then that activity is started. **No permission.** While a Vox call is live, PiP is entered first so the being stays visible; if it cannot float, the launch still happens | `no_app` |
| `open` | apps | `uri` with a scheme; optional `package` to restrict the target | `package`, `component` | **no** — opening a link changes nothing on the phone | `ACTION_VIEW` with the URI (and `setPackage` when named), resolved before it is started. **No permission.** The same PiP-first rule as `launch` while a call is live. `intent:` (explicit component, arbitrary extras and flags), `file:` (a path on disk) and a string with **no scheme** are refused `bad_uri` before the intent exists — nothing is guessed into `https://`; `content:` and `http(s):` pass through | `invalid-arguments`, `bad_uri`, `no_app` |
| `contact` | contacts | `name` — a full or partial name, case-insensitive, 1 – 200 characters | `count`, `matches` — each `display_name` and `numbers`, each number `{label, number}` | **no** — reading changes nothing | `READ_CONTACTS`, a runtime permission asked when Contacts is turned on; **[ContactsContract](https://developer.android.com/reference/android/provider/ContactsContract) only**. The projection is a display name and the numbers with their labels; **no note, address, photo or email** is read. Up to 20 matches; no match is `ok:true, count:0` | `permission` (naming `android.permission.READ_CONTACTS`), `unavailable` |
| `undo` | — (always offered) | `id` | `undone`, `state` (`cancelled` / `restored`) | — | nothing: it reverses through the same platform calls the action used | `expired`, `unavailable` |
| `status` | — (always offered) | — | `offered`, `tiers`, `grants`, `running`, `notes`, `registry`, `dnd`, `volume` | **no** — reporting only | nothing | — |

`undo` and `status` are not a capability: `undo` only reverses something the owner already allowed,
and `status` only reports. They are therefore always advertised, even with every group Off.

### Media, precisely

`play`, `pause` and `skip` **dispatch a platform media key** and nothing else. They act on
whatever session Android considers active; the phone does not choose a player and does not start
one. The result's `dispatched` is exactly that: the audio service accepted the key event. It is
**not** a claim that a track started, a track stopped, or a track changed.

`now_playing` asks the phone, and reports what the phone will say:

```json
{"ok":true,"action":"now_playing","tier":0,"known":true,"playing":true,
 "package":"com.example.player","title":"A Track"}
```

- **With notification-listener access**, the active session's own package and title are returned,
  and `known` is `true`. The sheet shows whether that access is held and offers **Let Vox see what
  is playing**, which opens Android's notification-access screen — the same shape as the Do Not
  Disturb row. Turning the Media group on offers it too.
- **Without it**, the platform will not name the session, so the result is the honest refusal below:
  `known:false`, `package` and `title` `null`, with a sentence saying what the phone cannot see.
  There is no prompt hidden behind a tool call; the grant is the owner's to give in the sheet.

```json
{"ok":true,"action":"now_playing","tier":0,"known":false,"playing":true,"package":null,
 "title":null,"note":"audio is playing, but the phone cannot see which app it is or what it is: that needs notification-listener access, which this app does not hold"}
```

- `playing` is `isMusicActive` — a boolean the phone is allowed to read, and `null` when even that
  cannot be read.
- `package` and `title` are `null` when `known` is `false`. **The phone never invents a title.** When
  the access is granted but no app is publishing a session, the note says *that* rather than
  blaming a missing grant.

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

### The clipboard: a write, and a read only when Android allows it

`clip` puts `text` on the clipboard and reports only its length (`chars`).

`clip_read` returns the clipboard's `text` and `chars` — when the platform allows the read. The
boundary is Android's, not ours: since Android 10 an app that is not in the foreground cannot read
the clipboard, and an empty clipboard is no answer either. Both cases are
`ok:false, reason:"unavailable"` with the platform's own explanation; the tool never returns stale
or empty content dressed up as what the clipboard holds. The owner granted the `clipboard` group, so
the read is offered with the write, and the OS decides whether this call may use it.

The audit row for a clip stores `text=NN chars`, never the text — the same rule as a note's body.
A `clip_read` row stores no payload at all: the result carries the text, the audit carries the
action and its outcome.

### Notifications: a summary now, content only on purpose

**The grant is permanent; our use of it is not.** `NotificationListenerService` access is a special
access the owner gives once in Android's notification-access screen, and the system may keep the
service bound between calls. The app therefore separates the *grant* from the *listener's
lifetime*: `PhoneNotifications` attaches when a Vox call starts and detaches when it ends — the same
lifetime rule as the MCP server — and `detach` drops every record and every id. Outside a call both
actions refuse `ok:false, reason:"unavailable"` and say the listener is only open during a call. A
summary of a phone with no live call is not "0 notifications"; it is a refusal that names the
lifetime.

**A summary is counts, and nothing to read aloud.**

```json
{"ok":true,"action":"notifications","tier":0,"sensitive":true,"count":12,
 "apps":[{"app":"Messages","count":6,"latest_at":1762345678901,"ids":["n7","n4","n2"]},
         {"app":"Mail","count":3,"latest_at":1762345600000,"ids":["n6","n3","n1"]},
         {"app":"Slack","count":3,"latest_at":1762345500000,"ids":["n5","n8","n9"]}]}
```

There is no `title` and no `text` field in a summary, at any depth. The counts, the app names and
the times answer "what's going on?" without putting a verification code on the transcript. `ids`
are opaque handles (not content) so the next call can name one notification; they are not a
browsing affordance, and the audit never stores them.

**The read is a second, deliberate step.**

```json
{"ok":true,"action":"notification_read","tier":0,"sensitive":true,"app":"Messages",
 "posted_at":1762345678901,"title":"Messages","text":"Your code is 123456"}
```

`notification_read` takes one `id` that a `notifications` summary of **this call** returned. An id
that was never shown — including one that arrived after the summary — is
`ok:false, reason:"unknown-id"`. That is what keeps a vague "what's going on?" from vacuuming the
lock screen: the owner's agent must ask for the summary, then deliberately name one.

**`sensitive: true`, and what the agent is told.** Both results carry `sensitive:true` at the top
level. The tool's `description` carries the rule that gives it meaning, because the description is
what the agent reads on every turn:

> Notifications and their contents may include verification codes, private messages and financial
> alerts. Do not read them aloud unless the owner asked you to, do not repeat them back, and do not
> store them in a note.

**Nothing is retained.** A notification's title and text live only in the in-memory board between
the post and the call's end. There is no cache file, no preference, no log line with a title or a
body, and no id written anywhere. The audit row for a summary is `notification_count`; the row for a
single read is `app` and `notification_count=1`. **Never a title, never a body, never an id the
owner could not see.**

**Vox does not list itself.** A notification whose package is this app is dropped before it reaches
the board — an app reading its own notification through the listener is a loop and a confusion. The
foreground-call notification and the model-download notification are therefore never part of a
summary.

`POST_NOTIFICATIONS` is **not** what this uses: it is the app's own permission to show the call and
timer notifications. Notification *reading* is the listener special access, shared with Media's
`now_playing`; the sheet has one **Notification access** row for it and opens Android's screen when
Notifications is turned on.

### Contacts: a number by name, and nothing else

`contact` takes `name` — a full or partial name, case-insensitive — and returns up to **20**
matching people from `ContactsContract`. Each match is a `display_name` and its `numbers`, each
number with the owner's `label` (`mobile`, `home`, `work`, …):

```json
{"ok":true,"action":"contact","tier":0,"count":1,
 "matches":[{"display_name":"Mom","numbers":[{"label":"mobile","number":"+15551234567"},
                                             {"label":"home","number":"555-0100"}]}]}
```

**The projection is the scope.** The group's stated purpose is "look up a contact's number by
name", and that is what is read: no note, postal address, photo or email is projected, returned or
audited. A name that matches nobody is not an error — `ok:true, count:0` is the true answer. A
missing `READ_CONTACTS` is `ok:false, reason:"permission"` naming
`android.permission.READ_CONTACTS`; the sheet asks for it when Contacts is turned on and shows
whether it landed. `message` and `dial` use the first number of the first match when their `to` is
a name, and the audit keeps only the lookup's `count` — never the query, a display name or a number.

---

## Tier 1 — reported

Executes when the owner has put the group at **Ask** — turning a reported group on is the grant —
and is reported to the mind on the next turn through the presence context and in the audit;
undoable where the row says so.

| action | group | arguments | result fields | undo | what it needs from the OS | refusals |
|---|---|---|---|---|---|---|
| `note` | notes | `text` string, 1 – 500 characters | `id`, `text`, `reported_next_turn`, `undoable` | **yes** — deletes it; lives 24 hours, because the note outlives the turn | none: private app storage | `unavailable` |
| `file_list` | files | optional `folder` `Downloads` (the default) or `Documents`; optional `path`, a **relative** subdirectory (`""` = the folder itself); optional `depth` integer 1–5 (default 1); optional `pattern`, a case-insensitive glob on the file name (`*.pdf`); `offset` integer ≥ 0 (default 0) and `limit` integer 1–1000 (default 200) | `folder`, `path`, `depth`, `count`, `has_more`, `next_offset`, `entries` — each entry `name`, `path` (relative), `kind` (`file`/`dir`), `bytes` (null for a directory), `modified` (local ISO-8601, null when the provider will not say), `mime` (files, when offered) | **no** — listing reads directory metadata and changes nothing | **Storage Access Framework is the deliberate default**, and only inside the same granted tree as `file_read`: the child query is `DocumentsContract` metadata, never a file's contents, one directory at a time, and no storage permission is declared for it | `no_folder`, `bad_path`, `unavailable` |
| `file_read` | files | `name` string (the file's name, not a path), optional `folder` `Downloads` (the default) or `Documents`, optional `offset` (bytes, ≥ 0) and `length` (bytes, ≥ 1; default 65 536) | whole file: `name`, `folder`, `bytes`, `chars`, `text`. A window: the same plus `offset`, `length` and `truncated` — with `bytes` the file's **total** size | **no** — reading changes nothing, and a read cannot be undone because it did not change anything | **Storage Access Framework is the deliberate default.** The owner grants one folder through `ACTION_OPEN_DOCUMENT_TREE`, the grant is persisted, and the sheet names the folder it got. No storage permission is declared for it. A future "all files" opt-in would use Android's own all-files access screen (`MANAGE_EXTERNAL_STORAGE`), with its own warning and its own grant — the owner's decision to make, not a capability withheld on their behalf. Text only: a whole read is capped (below), a window is not | `no_folder`, `bad_path`, `not_text`, `too_big` (whole-file only), `unavailable` |
| `file_offer` | files | the same `name` and `folder`; `approval` only on the second call | `name`, `folder`, `mime`, `bytes`, `sha256`, `url`, `expires_at` | **no** — offering changes nothing on the phone, but it exports the owner's file to the agent, so it is **Tier 2** and every offer needs the card | the same granted folder, then the existing file bridge ([FileOffer](../android/app/src/main/java/com/hermesvox/FileOffer.kt) / [FileStage](../android/app/src/main/java/com/hermesvox/FileStage.kt)) over the tailnet. The result is a capability URL, not a base64 blob. The folder grant makes the export possible, not intended | `no_folder`, `bad_path`, `too_big`, `unavailable`, `approval-required`, `approval_denied`, `approval_expired`, `approval_mismatch`, `approval_unknown`, `approval_locked`, `approval_busy` |
| `agenda` | calendar | — | `count`, `events` — each `start`, `end`, `title`, `calendar`, `description`, `location`, `attendees` (names only), and `null` where the provider will not say | **no** — reading changes nothing | `READ_CALENDAR`, a runtime permission asked when the group is turned on; [CalendarContract](https://developer.android.com/reference/android/provider/CalendarContract) only | `permission`, `unavailable` |
| `event_create` | calendar | `title` string ≤ 200 characters, `start` local ISO-8601 `YYYY-MM-DDTHH:MM` (seconds optional), `duration_min` integer ≥ 1, optional `calendar` name | `id`, `title`, `start`, `end`, `duration_min`, `start_ms`, `end_ms`, `calendar`, `undoable` | **yes** — deletes the event it created; lives until the event starts, or ten minutes, whichever is longer | `WRITE_CALENDAR` (and `READ_CALENDAR`, to find a writable calendar), runtime permissions asked when the group is turned on; `CalendarContract` only | `permission`, `unavailable`, `invalid-arguments` |

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

**Calendar, precisely.** `agenda` reads today's events in the phone's own zone and returns what the
calendar holds: start, end, title, calendar name, description, location, and attendee **names**.
Attendee addresses are never projected, so they cannot leak into a result; a field the provider will
not give is `null`, never an invented one. `start` is local ISO-8601: a trailing `Z` or an offset is
refused rather than converted, because "local" is the contract and a conversion would move the
event. `duration_min` is minutes and must be at least 1; zero and negatives are refused
`invalid-arguments`. There is **no upper bound of ours** — the calendar provider's own limits apply,
and a duration it will not store is `unavailable` rather than pre-empted by a cap. A phone with no
writable calendar is `unavailable`, which is the truth rather than a silent export. The undo deletes
the event it created and nothing else.

**Built in lane 2:** `files` and `calendar`. Their actions are advertised only while the group is
on, and both groups are reported: turning one on is the grant. `delete`, a file delete that cannot
be undone, remains a Tier 2 name and is deliberately not built or advertised.

**Built by the read lane:** `contacts` and `notifications`, both instant and both Allow. Contacts is
a `ContactsContract` lookup that returns names, numbers and labels only. Notifications is a
call-scoped listener with a summary that carries no content and a deliberate second call for one
id. Neither group stores its payload in the audit.


---

## Tier 2 — approval

Tier 2 is for what cannot be undone and cannot be handed to another app. The phone **never
executes** one on the first call. `uninstall` and `file_offer` have cards on this phone; the rest
still answer `approval-required` and name the surface that owns them.

`file_offer` is here because offering a file **exports** it: the bytes leave the granted folder,
travel over the tailnet, land in the conversation and stay. The folder grant makes that export
possible, not intended, so it takes a card on every use even though the Files group is reported.

### `uninstall` and `file_offer`: two steps, on the same tool

An MCP call carries no run id, so the approval cannot park a host run the way the gateway's
`approval.request` does. The protocol is a handshake on the same action:

1. **Request.** Call `uninstall` with `package`, or `file_offer` with `name` and `folder`, and
   **no** `approval`. Nothing is removed or exported. The phone raises the card and returns:

   ```json
   {"ok":false,"action":"uninstall","tier":2,"reason":"approval-required",
    "detail":"the owner must approve removing Firefox; …",
    "approval_id":"ap-7","expires_at":1767225600000,"summary":"uninstall Firefox"}
   ```

   ```json
   {"ok":false,"action":"file_offer","tier":2,"reason":"approval-required",
    "detail":"the owner must approve offering report.pdf (PDF, 240 KB); …",
    "approval_id":"ap-8","expires_at":1767225600000,
    "summary":"offer report.pdf (PDF, 240 KB)"}
   ```

   `summary` names the app and its label, or the file, its type and its size; `expires_at` is epoch
   millis, two minutes after the card was raised. A file above the 100 MB the bridge will serve, a
   missing file and a name outside the grant are refused **before** a card is raised.

2. **Execute.** Call **the same action with the same canonical arguments** plus
   `approval:"ap-7"`. It runs only when the id is unexpired, unburnt, and the action and its
   canonical arguments hash to the value the card was raised for.

   ```json
   {"action":"uninstall","package":"org.mozilla.firefox","approval":"ap-7"}
   ```

   ```json
   {"action":"file_offer","name":"report.pdf","folder":"Downloads","approval":"ap-8"}
   ```

   The call executes only after the owner has approved. If the card is still up it returns
   `approval-required` again with the same id; it does not burn the id. If the owner denied it the
   answer is `approval_denied`; after two minutes `approval_expired`; a different package or file
   is `approval_mismatch`; a reused or unknown id is `approval_unknown`; an answer given while the
   phone was locked is `approval_locked`.

**Every refusal burns the id.** A denied, expired, locked or mismatched id reports its reason once,
then a second use is `approval_unknown`. **One card at a time**, three may wait behind it; the
fourth request is `approval_busy` and raises nothing. A card that is dismissed without an answer is
a deny; silence at expiry is a deny too.

### The canonicalisation, and why it cannot be confused

The card is bound to the text `action` + `\n` + every argument except `approval`, sorted by key,
with string values trimmed and internal whitespace collapsed. That text is stored beside its
SHA-256 digest, and the execution call must match **both**. Key order and whitespace therefore do
not matter; a different package, file name or folder cannot hash the same by accident, and even a
digest collision would still have to match the stored text. The package is validated as a dotted
package name, and the file name by the same path rules as `file_read`/`file_list`, before any card
is raised.

### The card

One line, in the owner's language. For an app: **“Your agent wants to remove Firefox. Approve?”**
For a file: **“Your agent wants to read `report.pdf` (PDF, 240 KB). Approve?”** The name and type
come from the platform and the size is the file's own; the tool's name never appears. The size is
on the card because handing over a 40 MB file is a different act from handing over a note. Two ways
to answer:

- **Tap** **APPROVE** or **DENY** on the card.
- **Voice**, using `core/Approval.kt`'s allowlists — the same yes/no vocabulary as the gateway's
  cards. A hedged answer (“I'm not sure”) is not an answer: the card stays up and is re-asked once
  (“say approve or no”). Silence at two minutes is a no.

The card is **spoken when a call is live**. When Vox has no screen, a full-screen dialog cannot be
started from the background, so the card is a **heads-up notification with Approve and Deny
actions** that opens Vox when tapped. If neither surface can be shown, the request fails
`unavailable`, saying the owner could not be asked — never a silent timeout dressed as a denial.

### The lock screen is not an approval surface

The notification is built **`VISIBILITY_PRIVATE`**, so the lock screen does not show the file's name
or the app being removed. Its buttons can still be tapped while locked, so the answer path checks
the keyguard **before** recording a yes. A locked phone refuses with `approval_locked`, burns the
id, and posts a private “unlock and ask again” notice that names nothing; the owner sees **Locked**
in the in-app card and the agent gets the reason. Denying is always allowed, because a no cannot
authorise anything.

A pending card may outlive a screen-off, but it can never be approved from the lock screen: the
only yes is given on an unlocked device, and the id's two-minute expiry still governs. If the phone
is locked when the agent makes its second call, the card is still pending and the answer is
`approval-required` until the owner unlocks and answers; if it was answered while locked, the id is
already burnt and the answer is `approval_locked`.

### Why Android's own confirmation still runs

After our card says yes, `ACTION_DELETE` opens Android's uninstall confirmation. That is not double
gating: the two screens ask different questions. Ours asks **“did you mean this app?”** — it carries
the label, the voice answer and the two-minute window that the platform screen knows nothing about.
Android asks **“may this app be removed?”** — the system-level consent that no app can give on the
owner's behalf. Vox therefore reports `platform_confirmation:true`, not "removed": it cannot see the
owner's answer on Android's own screen.

| action | group | arguments | result | undo | what it needs from the OS | refusals |
|---|---|---|---|---|---|---|
| `uninstall` | apps | `package` string (a dotted package name); `approval` only on the second call | `package`, `label`, `platform_confirmation`, `note` | **no** — an uninstall cannot be undone | the app's label from `PackageManager`, then `ACTION_DELETE` and Android's own confirmation | `approval-required`, `approval_denied`, `approval_expired`, `approval_mismatch`, `approval_unknown`, `approval_locked`, `approval_busy`, `unavailable` |
| `file_offer` | files | `name` and `folder`; `approval` only on the second call | `name`, `folder`, `mime`, `bytes`, `sha256`, `url`, `expires_at` | **no** — the export cannot be recalled | the file's own name, type and size from the granted tree, then the file bridge over the tailnet | `no_folder`, `bad_path`, `too_big`, `unavailable`, `approval-required`, `approval_denied`, `approval_expired`, `approval_mismatch`, `approval_unknown`, `approval_locked`, `approval_busy` |
| `install` | apps | `app` string | `app`, `installed` | **no** | the package installer's own confirmation, which the owner answers | `approval-required` |

**Declared, not built:** `apps`' `install`. `PhoneGrants` names `install` in the apps group's
`planned` list, and `PhoneTiers` still names it, so a direct call is refused as
`approval-required`; it is never advertised, because a promise in an `enum` is a capability the
phone must be able to keep. `call`, `send` and `email` are gone with the old `messages-calls`
group: calls are `dial`, messages are `message`, and there is no other path.

**Why the reads are not Tier 2.** `notifications` and `contact` change nothing and can be undone by
doing nothing, so they are instant and offer Allow. Their privacy is designed into their scope
instead of being deferred to a card: the listener is only open during a call, a summary has no
content, content needs a second call for one id, contacts return numbers only, and neither group
writes its payload to the audit. The owner still sees the grant and every use.

### What the approval card enforces

These are the behaviours the shipped card implements, not requirements for a later lane.

1. **`file_offer` is in the approval tier.** Handing a file to the agent exports it: the folder
   grant makes that export possible, not intended. It is Tier 2, asks for the card on every use,
   one card per file, and the card shows the file's name, type and size. Its first call writes no
   bytes to the bridge; the second call, with the matching id and the same name and folder, does.
   `file_read`, `file_list` and `file_offer`'s own `no_folder`, `bad_path`, `too_big` and
   `unavailable` refusals are unchanged.
2. **The card is not actionable on a locked phone.** Its notification is `VISIBILITY_PRIVATE`, so
   the lock screen does not show what is being approved, and the answer path checks the keyguard
   before recording a yes. A locked phone refuses with `approval_locked`, burns the id, and shows
   the owner a private “unlock and ask again” notice. A pending card can outlive a screen-off, but
   it cannot be approved from the lock screen, and its expiry still runs.

---

## The thirteen groups

| group | tier | actions | state |
|---|---|---|---|
| `timers-alarms` | 0 | `timer`, `alarm` | **built** |
| `dnd-volume` | 0 | `dnd`, `volume` | **built** |
| `notes` | 1 | `note` | **built** |
| `media` | 0 | `play`, `pause`, `skip`, `now_playing` | **built in lane 1** |
| `device-state` | 0 | `state` | **built in lane 1** |
| `clipboard` | 0 | `clip`, `clip_read` | **built in lane 1** |
| `files` | 1 | `file_list`, `file_read`, `file_offer` | **built in lane 2; listing added by the listing lane; `file_offer` is Tier 2 and card-gated** |
| `calendar` | 1 | `agenda`, `event_create` | **built in lane 2** |
| `notifications` | 0 | `notifications`, `notification_read` | **built by the read lane; listener call-scoped** |
| `messages` | 0 | `message` | **built by the handoff lane** |
| `calls` | 0 | `dial` | **built by the handoff lane** |
| `apps` | 0 | `launch`, `open`, `uninstall` — `install` planned | **built; `uninstall` is Tier 2 and card-gated** |
| `contacts` | 0 | `contact` | **built by the read lane; numbers only** |

---

## Undo: only where reversal is real

| action | undo does | lives | why it is not offered when it is not |
|---|---|---|---|
| `timer` / `alarm` | cancels it | until it rings | the action is the arming; cancelling is its exact reverse |
| `dnd` / `volume` | restores the previous filter / level | until the next change of the same setting, or 10 minutes | a second change makes the older "previous" untrue |
| `note` | deletes it | 24 hours | the note outlives the turn that made it |
| `file_list` / `file_read` / `file_offer` | — | — | listing and reading change nothing on the phone; offering changes nothing there either, but it exports the file and an export cannot be recalled. A read cannot be undone, and the doc says so rather than inventing one |
| `agenda` | — | — | reporting changed nothing |
| `event_create` | deletes the event it created | until the event starts, or ten minutes, whichever is longer | deleting the one event the phone inserted is its exact reverse; it never touches another event |
| `play` / `pause` / `skip` | — | — | **`play` after `pause` is not an undo.** It is another key press into whatever the session is doing now; the phone has no "previous playback state" it could honestly restore, and it cannot see playback at all without a listener |
| `now_playing` / `state` | — | — | reporting changed nothing |
| `clip` | — | — | a clipboard write cannot be reversed: the phone would have to have kept what it replaced, and a clip keeps no history of the clipboard |
| `clip_read` | — | — | reading the clipboard changes nothing, so there is nothing to reverse |
| `notifications` / `notification_read` | — | — | reading changes nothing; dropping the board at the end of the call is not an undo, it is the lifetime |
| `contact` | — | — | a lookup changes nothing, and there is no address-book write to reverse |
| `message` | — | — | Vox only composed a draft. A sent text cannot be recalled, and nothing was sent |
| `dial` | — | — | the dialer opened; the owner's call is not Vox's to unplace |
| `launch` / `open` | — | — | bringing an app or a link to the front is not a reversal, and there is no "previous screen" to restore |
| `uninstall` | — | — | a removed app cannot be put back by this phone; the platform's installer owns a reinstall, and the card says so rather than inventing an undo |
| `undo` / `status` | — | — | neither is a capability |
| every Tier 1/2 write | as the row says | — | where the table says "no", there is no reversal and no invented one |

`undo` of an id with no live undo is `ok:false, reason:"expired"`. It never guesses.

---

## The audit

Every call the mind makes — success **and** refusal — writes one row through
[`PhoneAudit`](../android/app/src/main/java/com/hermesvox/PhoneAudit.kt): time, action, the
arguments summarised, the outcome, and the undo id when there is a real one.

Arguments are **summarised, never copied**: a `note`, `clip` or `message` `text` and an
`event_create` `title` are stored as `NN chars`; a duration, a clock time, a boolean, a level, a
direction, an id, a file name, a folder or a recipient is stored as itself, clipped to one line.
A `message` row therefore shows who and how many characters, never the body; an `open` row shows the
URI it asked for, so the owner can see the destination. A `file_list` row is the folder, the
relative path and the count — `Downloads, 12 entries` — and **never a name it saw**, because the
names in a folder are the owner's. Nothing in this surface logs a secret, a clipboard payload, a
file's contents or a calendar's contents — a `file_read` row carries the name and folder it asked
for, never the text it got back, and an event row carries the title's length, never the title. An
An `uninstall` or `file_offer` card writes **three** rows: the request, the decision (`approved`,
`denied`, `locked` or `expired`, and whether by tap or voice), and the execution. An app row names
the package and the label; a file row names the file and its type/size, never its contents. The
decision row carries `via=tap|voice|silence`; the canonical arguments and the hash are never
stored, because they are the guard, not something the owner needs to read.

**The two read groups are narrower still.** A `notifications` row is `notification_count` alone; a
`notification_read` row is `app` and `notification_count=1`. Neither keeps a title, a body, or an
id the owner could not see. A `contact` row is `count` alone: never the query name, a display name
or a number. These are the owner's data, not facts the audit list may keep. The audit's one
summarising function is the place a future action has to pass through.

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
4. **`now_playing` without notification access.** `ok:true, known:false`, `package` and `title`
   `null`, `note` saying the phone cannot see which app or what it is; `playing` is `true` while
   audio is audible and `false` otherwise. The title must never be invented, and no prompt appears
   behind a tool call. The sheet's *Now playing access* row says **Not granted** while Media is on,
   and **Let Vox see what is playing** opens Android's notification-access screen.
5. **`now_playing` with notification access granted.** The same call returns `known:true`, the real
   package and title of the active session, and no `note`. With nothing publishing, `known:false`
   and a note that says the phone can see sessions and none is publishing — not that the grant is
   missing. The sheet's row says **Granted**.
6. **`state` on a charging phone.** `battery_percent` matches Android's own battery figure,
   `charging_source` is `ac`/`usb`/`wireless`, `charging_status` is `charging`, `screen_on` follows
   the screen, `network` is `wifi` or `cellular` (and `none` in aeroplane mode), `tailnet_present` is
   `true` with Tailscale up, `free_storage_bytes` matches Settings' free space within a rounding,
   `media_volume` matches the volume slider, and `time` is the phone's own clock.
7. **`state` without Do Not Disturb access.** `dnd` is `null`, and every other field is still
   filled. Nothing is guessed as "off".
8. **`clip` then paste by hand.** The text is on the clipboard, the result says `ok:true` with
   `chars` only, Recent actions shows `text=NN chars`, and the clipboard's previous contents are not
   anywhere in the app. A following `clip_read` in the foreground returns that same text and its
   `chars`.
9. **`clip` and `clip_read` from a backgrounded phone** (a call that lost focus). `clip` is
   `ok:false, reason:"unavailable"`, and nothing was written. `clip_read` is `ok:false,
   reason:"unavailable"` with Android's own explanation that a foreground app is required — and no
   `text` field, so no empty or stale clip is mistaken for the clipboard. With the clipboard empty
   in the foreground, `clip_read` also refuses, saying the clipboard is empty.
10. **Files with no folder granted.** The sheet says *No folder granted*; `file_list`, `file_read`
    and `file_offer` are `ok:false, reason:"no_folder"` and say to pick a folder in Phone tools →
    Files. Turning Files on opens the system folder picker, and after picking, the sheet names the
    folder it got (`Granted · Download`). No storage permission prompt ever appears.
11. **Files with a folder granted.** `file_list` on a folder with subdirectories answers `folder`,
    `path:""`, `depth:1`, `count`, `has_more:false` and one entry per child: directories first
    (`kind:"dir"`, `bytes:null`), then files alphabetically with their sizes, `modified` when the
    provider gives it and `mime` for files. `depth:2` adds `"reports/2026"`-shaped paths for the
    children of each subdirectory; `depth:1` does not. `pattern:"*.pdf"` leaves the directories and
    only the PDFs. A folder of 500 files at `limit:100` answers `has_more:true` and
    `next_offset:100`, and walking the offsets returns every entry exactly once; it does not refuse.
    A `file_read` of a text file returns `text`, `chars` and `bytes` with no `undo`; a 70 KB file is
    `too_big` with nothing cut short, and the result names `offset`/`length`, while
    `offset:0, length:4096` returns the first 4 KiB with `truncated:true` and `bytes:70000`. A PNG
    is `not_text`; `name:"../secret.txt"`, `path:"../Documents"` and an absolute path are `bad_path`
    and never read. A `file_offer` of a 240 KB PDF is `approval-required` with
    `summary:"offer report.pdf (PDF, 240 KB)"` and the card *Your agent wants to read report.pdf
    (PDF, 240 KB). Approve?*; nothing is staged. After **APPROVE**, the same `file_offer` with the
    id returns a `url`/`sha256` that the agent can fetch, with no bytes in the result. A different
    file with that id is `approval_mismatch` and nothing is served.
12. **Calendar without permission.** The sheet says *Not granted* and names
    `android.permission.READ_CALENDAR`; `agenda` and `event_create` both refuse `permission`, and
    nothing is created. Turning Calendar on asks for both permissions and the sheet shows the
    answer.
13. **Calendar with permission.** `agenda` lists today's events with start, end, title, calendar,
    description, location and attendee **names** — no attendee addresses — and `null` for anything
    the provider does not give. `event_create` with `start:"2026-11-05T09:30"` and
    `duration_min:30` returns an `id`, and the event appears in the phone's own calendar app. A
    `duration_min` above 1 440 is accepted by the tool and either stored by the provider or refused
    by it as `unavailable` — never pre-empted by a cap of ours. Its `undo` deletes that event and
    nothing else; Recent actions shows the title as `NN chars`.
14. **Recent actions.** One row per call, successes and refusals, with the summarised arguments and
    the outcome; no row carries a note's text, a clipboard payload, a file's contents, an event's
    title or a file path's contents.
15. **Revoke mid-call.** Turn Media Off while the call is live: the next `tools/list` no longer
    lists `play`, and the next call is `not_granted` with no key dispatched.
16. **Messages = Allow, then `message` to a number.** The owner's messaging app opens on a draft
    with the number and body filled in; **nothing is sent**, and the result is `ok:true` with `to`,
    `chars` and `handed_off:true`. The owner's send button is the only thing that sends.
17. **Calls = Allow, then `dial` while a Vox call is live.** The being says *dialling — ending our
    call*; the Vox call ends through the same teardown the hang-up uses; the dialer opens with the
    number filled in and nothing is placed. The conversation is still on the gateway when the next
    call resumes it. `dial` with no dialer is `no_app`, and no call is ended for nothing.
18. **Apps = Allow, then `launch` while a call is live.** The window shrinks to PiP first, then the
    app opens; the being is still visible and Stop still works. If PiP cannot be entered, the app
    opens anyway. `launch` of a package with no launcher entry is `no_app`. `open` of
    `https://example.com` lands in the browser and reports its `package` and `component`; a bare
    address is `bad_uri`.
19. **`uninstall` requests, approves, executes.** With Apps on, `uninstall` of Firefox is
    `ok:false, reason:"approval-required"` with `approval_id`, `expires_at` and
    `summary:"uninstall Firefox"`, and the card says *Your agent wants to remove Firefox.
    Approve?* Nothing is removed. Tapping **APPROVE** (or saying *yes* on a live call) and calling
    again with the same package plus `approval:"<id>"` opens Android's own confirmation; the result
    is `ok:true` with `platform_confirmation:true`. **DENY**, a different package, or a second use
    of the same id refuses and removes nothing. Let a card sit for two minutes: it expires, the
    next call is `approval_expired`, and the audit shows request → expired. With Vox in the
    background the same card is a heads-up notification with Approve/Deny actions; with Vox in
    front it is the in-app card.
20. **The card on a locked phone.** Lock the phone, then ask for an `uninstall` or a `file_offer`
    from the agent. The heads-up arrives, but its lock-screen content is hidden
    (`VISIBILITY_PRIVATE`): no file name and no app label. Tap **APPROVE** without unlocking: the
    card does not approve, the app is not removed and the file is not served; the phone shows a
    private *Locked — nothing was approved. Unlock your phone, then ask again.* notice, the audit
    says `locked`, and the agent's next call with that id is `approval_locked`. Unlock the phone and
    the same request raises a fresh card that can be approved normally. Screen-off alone does not
    extend the id: after two minutes it is `approval_expired`.
21. **Contacts without permission.** Set Contacts to Allow but deny the runtime dialog. The sheet's
    *Contacts permission* row says **Not granted** and names
    `android.permission.READ_CONTACTS`; `contact` refuses `permission` with that exact string and
    reads nothing. Grant it in the sheet and the row says **Granted**.
22. **A partial contact name.** `contact` with `name:"mo"` returns up to 20 people whose display
    name contains it, case-insensitively, each with numbers and labels (`mobile`, `home`, `work`)
    and **no** note, address, photo or email. `name:"zzzz"` is `ok:true, count:0`, not an error.
    Recent actions shows `count` alone — not the name and not a number. `message`/`dial` to
    `"Mom"` now opens the owner's app with the first number of the first match.
23. **Notifications while a call is live.** Notifications = Allow, then `notifications`. The result
    is `sensitive:true` with `count` and one row per app (`app`, `count`, `latest_at`, opaque
    `ids`) and **no `title` and no `text` anywhere**. The counts match what is on the phone, minus
    Vox's own call/download notifications. If there are twelve notifications, the answer reads
    *"12 notifications: Messages 6, Mail 3, Slack 3"* — and nothing else.
24. **The second step, on purpose.** `notification_read` with an id from that summary returns that
    one notification's `app`, `posted_at`, `title` and `text`, with `sensitive:true`. An id that no
    summary showed — including one that arrived after the summary — is `unknown-id`; a fresh
    summary makes it readable. Recent actions shows `app` and `notification_count=1`, never the id,
    title or body.
25. **Hang up, then ask.** With no live call, both `notifications` and `notification_read` are
    `ok:false, reason:"unavailable"` and the detail says the listener is only open during a call.
    Start a new call: the board is empty (the previous call's records and ids were dropped), and an
    old id is `unknown-id`.
26. **The listener's grant is not the listener's lifetime.** The sheet's *Notification access* row
    may say **Granted** while the phone is idle, and the actions still refuse `unavailable`; that is
    the design, not a bug. Turning Notifications on offers Android's notification-access screen and
    the row shows whether it landed.
27. **The remaining declared capability stays refused.** `install` is **absent** from the `enum`
    and a direct call returns `approval-required`; `file_list` and `file_read` do the same while
    Files is Off, and no lane may make them advertise before the phone can keep the promise.
