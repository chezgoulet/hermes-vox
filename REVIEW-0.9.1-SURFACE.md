# Review — 0.9.1 phone surface (`68f2eb7..2b6c78d`)

Tree reviewed: **`2b6c78d`** (= `origin/testing` at review time; 212 commits after `68f2eb7`; working tree clean).
Centre of gravity: `PhoneTool`, `PhoneGrants`, `PhoneEngine`, `PhoneFiles`/`PhoneFileAccess`, `PhoneApproval`/`PhoneApprovals`,
`LocalActions.Platform`, `DialHandoff`/`CallWindow`/PiP in `MainActivity`, `PhoneNotifications`, `ContactsAccess`, `PhoneAudit`,
`PhoneMcpServer`/`PhoneMcp`/`PhoneSetup`, and the tests and docs that describe them.
Upstream checked: `~/.hermes/hermes-agent` at `f0d94882e8` (2026-11-02); Android developer docs (SAF tree restrictions, fetched).

No file in the tree was edited. Suggested fixes below are proposals.

**Pending lanes.** Findings marked **[closure]** touch code the approval-closure lane (`file_offer` into the approval tier,
lock-screen card) is about to change; **[anchor]** marks findings whose fix moves lines the skill cites.

---

## Findings

### BLOCKER

#### B1 — The default folder, Downloads, cannot be granted on Android 11+, and any other folder that *can* be granted is unreachable
`PhoneFileAccess.kt:143` (`labelOf`), `PhoneFiles.kt:155` (`normalizeFolder`), `PhoneEngine.kt:895-899` (`folderFor`), `PhoneTool.kt:309-317` (`folder` enum)

- Android 11+ (API 30+; the app targets 34) **does not let `ACTION_OPEN_DOCUMENT_TREE` select the `Download` directory**, the internal-storage root or a reliable SD root (developer.android.com, *Access documents and other files*, "Access restrictions"). The picker shows "Can't use this folder".
- So the owner must pick a folder *inside* Download, e.g. `Download/Work`. `labelOf` stores the last segment of the tree id (`Work`). `folderFor` only accepts a tree whose label normalises to `download` or `document`, and the `folder` argument is an enum of `Downloads`/`Documents`. **A granted `Work` folder can never be named.** Every call gets `no_folder`, and the detail tells the agent to "Name that folder", which the schema does not allow.
- Net effect on a current phone: `file_list`/`file_read`/`file_offer` work only when the owner happens to pick the **Documents root** itself. The default (`Downloads`) never works. MCP-SURFACE device-pass step 10 ("the sheet names the folder it got (`Granted · Download`)") cannot happen on API 30+.
- Suggested fix: make grants first-class. Key each tree by its own id, not by the word "Downloads"/"Documents". Advertise `folder` as an enum of the **granted labels**, built per `tools/list` the way the action enum is. Default to the only grant when there is exactly one, and refuse `invalid-arguments` naming the choices when there are several. In the sheet, set `EXTRA_INITIAL_URI` to Download and say plainly that a subfolder must be picked. Pin this with a test that grants `primary:Download/Work` and reads from it.

#### B2 — Every handoff (`message`, `dial`, `launch`, `open`, `uninstall`) can report `ok:true` when Android silently blocked the launch
`LocalActions.kt:640` (`dial`), `:687` (`startApp`), `:717` (`startUri`), `:751` (`uninstall`), `:776` (`startHandoff`)

- All five call `app.startActivity(... FLAG_ACTIVITY_NEW_TASK)` from the **application context**, and treat "no exception" as success.
- Android 10+ background-activity-launch rules block the start **without throwing** when the app has no visible window. The foreground microphone service does not exempt it. `startActivity` returns normally, so `true`/`Done`/`Confirming` is reported, and the engine renders `ok:true`.
- Situations that hit this:
  - A call with the screen off or locked (the phone in a pocket — the normal case for a voice call).
  - **`uninstall` approved from the heads-up notification.** That surface exists precisely because Vox is not in front, so the follow-up `ACTION_DELETE` is a background start. The result is `ok:true, platform_confirmation:true` with no confirmation screen anywhere.
  - On `dial` the Vox call has already been torn down before the blocked start (see H1).
- This breaks the surface's central invariant: "`ok:true` only after the platform confirmed" (`PhoneTool.kt:46-51`, MCP-SURFACE §"A refusal is a result"). The code comment at `LocalActions.kt:~745` claims the opposite: "A background activity launch the platform refuses becomes `unavailable`, never a silent success".
- Severity: **blocker** for `uninstall` via the notification path (that path cannot work). High for the others. Needs a device to confirm (see *Could not verify*).
- Suggested fix: add a single `canStartActivity()` gate on the platform: "an activity of ours is resumed, **or** in PiP and visible". Check it **before** resolving or ending anything, and refuse `unavailable` with "Vox is not on screen; Android blocks opening apps from the background". For `uninstall`, open Vox (full-screen) from the notification's Approve action and fire `ACTION_DELETE` from the activity, or make the approve tap the thing that opens the confirmation. **[closure]**

### HIGH

#### H1 — `dial` during a call: `ok:true` is returned before anything happens, a dial failure after the teardown is lost, the spoken line is cut, and a PiP window is left floating
`DialHandoff.kt:80-88`, `PhoneEngine.kt:1404-1427`, `MainActivity.kt:1091-1102` (`announceDial`), `MainActivity.kt:819-845` (`endCall`)

- `run()` returns `ANNOUNCED_END_THEN_DIAL` immediately. The `host.dial(number)` boolean inside the `after` lambda is discarded. If `startActivity` throws after `endCall()`, the owner is left with no Vox call and no dialer, and the agent was told `ok:true`. This is the "half-torn-down" case; nothing tests it (`PhoneHandoffTest` hosts call `after` synchronously and never fail).
- `onPlayed` fires on **TTS start** (`VoiceController.kt:3347`). `after` then runs `endCall()` → `liveController.stop()` → `silenceAll()`, which cuts the TTS that has just started. If synthesis takes longer than `ANNOUNCE_FALLBACK_MS` (900 ms), the teardown runs before the line starts at all. Either way "dialling — ending our call" is almost never heard in full, though D36 calls it the owner's notice.
- `endCall()` does not leave PiP; only the PiP *Stop* receiver calls `finish()`. A `dial` issued after a `launch` (Vox already in PiP) leaves an idle floating window, against CallWindow's own rule "An idle app must never float".
- Suggested fix:
  - Fire `after` on the utterance's **done** callback, keeping a bounded fallback measured from the start.
  - Pre-flight the BAL gate from B2 before announcing.
  - Report `ok:true` with `handoff:"pending"`, so it is not a claim of success, or log and audit the post-teardown dial result as its own row.
  - Make `endCall()` call `finish()`, or `moveTaskToBack`, when `isInPictureInPictureMode`.

#### H2 — `file_read`'s window has no upper bound: one call reads the whole file into memory on the main thread
`PhoneTool.kt:729-737`, `PhoneTool.kt:357-363` (schema `length`: `minimum` only), `PhoneFileAccess.kt:292`, `:~430` (`readRange`)

- `length` is only checked `>= 1`, and `readWindow` caps it at `total - start`. So `{"offset":0,"length":9e18}` reads the entire file into a `ByteArrayOutputStream`, decodes it, and returns it in one JSON-RPC response. A 500 MB text log means an OOM in the app process, mid-call, on the main thread (see H5).
- That makes the documented promise false: "`file_read` returns at most 65 536 bytes of text in one answer" (MCP-SURFACE:330, `PhoneFiles.kt:23-27`). The whole-file `too_big` cap is bypassed simply by asking for a window.
- `countBytes` (`PhoneFileAccess.kt` near `:455`) also streams the whole file on the main thread when the provider hides `COLUMN_SIZE`.
- Suggested fix: in the schema add `"maximum": MAX_READ_BYTES` to `length`; in parse add `if (v > PhoneFiles.MAX_READ_BYTES) return bad(action, "length is at most 65536 bytes; read the rest with a later offset")`. Keep `coerceAtMost(MAX_READ_BYTES)` in `readWindow` as a second line of defence.

#### H3 — Contacts at **Ask** is bypassed by `message`/`dial` with a name
`PhoneTool.kt:944-958` (`recipient`), `PhoneEngine.kt:1661-1692`

- `recipient()` lets a name through whenever Contacts is **not Off**. Contacts is Tier 0, and Ask on Tier 0 means "waits for a card; nothing is performed" (`PhoneTool.kt:603-615`). So with Contacts=Ask and Calls/Messages=Allow:
  - `contact` is refused `approval-required`;
  - but `dial {"to":"Mom"}` reads the address book and returns the resolved number in `to`.
- The engine comment claims "The Contacts group's grant is checked before this runs", which is only true for Off.
- Suggested fix: `if (PhoneGrants.stateOf(grants, contacts) != PhoneGrants.State.ALLOW) → refuse` (`not_granted` for Off, `approval-required` naming Contacts for Ask). Add a test: Contacts=Ask, Calls=Allow, `dial "Mom"` → no lookup.

#### H4 — `status` and `undo` ignore every grant: with all groups Off the agent can read every note, cancel the owner's own alarms, and delete notes
`PhoneGrants.kt:251-262`, `PhoneEngine.kt:1702-1775`, `LocalActions.kt:106` (`restoreNotes`)

- `status` returns `notes` with full `text`, `running` timers and alarms with their ids, the live volume and the DND grant, whatever the grants are. The registry is shared with the **owner-initiated** path (`LocalActions.run`), so notes and alarms the owner dictated by voice are included.
- `undo` accepts any registered id. `restoreNotes` re-registers a 24-hour delete-undo for **every** stored note on every process start.
- So "a phone whose grants are all Off" lets the mind list the owner's private notes, cancel the 6 am alarm they set by voice, and delete their notes. MCP-SURFACE:132 ("`undo` only reverses something the owner already allowed") is false for owner-initiated actions.
- Suggested fix:
  - Record the initiator (`owner`/`mind`) on each registry entry.
  - `undo` from the MCP path may reverse only `mind` entries whose group is still not Off.
  - `status` filters `running`/`notes` by the same rule and never returns note text unless Notes is on.
  - Owner undo (the sheet tap) stays unrestricted.

#### H5 — Every MCP action runs on the main thread under the engine lock; a timeout says "nothing was performed" while the action still runs
`LocalActions.kt:146-170` (`phone`), `PhoneEngine.kt:513` (`synchronized(lock)`), `PhoneFiles.kt:366-439`

- `phone()` posts the whole dispatch to the main looper and waits `CALL_TIMEOUT_MS = 4 s`. Several things then run on the main thread:
  - SAF child queries for a depth-5 walk;
  - `readRange`, `countBytes` and `FileStage.stage`, which copies up to 100 MB;
  - Calendar and Contacts provider queries.
- `PhoneFiles.list` materialises **the entire walk** before paging, so every page re-walks the whole tree. Paging protects the response size, not the work. A large Downloads at depth 5 blocks the UI and the voice loop and risks an ANR, mid-call.
- When the latch times out, `refusal()` returns and audits `ok:false, unavailable, "the phone did not answer in time"`. The posted work still completes: the file is offered, the event is created, the dial goes out. It is audited a second time with its real outcome. The agent was told nothing happened. This contradicts "a refusal means nothing was performed" (`PhoneTool.DESCRIPTION`).
- Suggested fix:
  - Run the engine on a dedicated single-thread executor. Only the calls that truly need the main thread (startActivity, PiP) post to it.
  - Make the listing walk lazy, and stop once `offset+limit+1` rows are known.
  - On timeout return `reason:"unavailable", "outcome_uncertain":true` ("may still complete; call status"), never the "nothing was performed" wording.

#### H6 — The uninstall card surface loses the card or fails open to "unavailable" in ordinary states
`PhoneApprovals.kt:73-89`, `:113-131`; `MainActivity.kt:48-70`, `:440`

- **Tapping the notification body loses the card.** The PendingIntent carries `EXTRA_ID`, but `MainActivity` never reads it (no reference to `PhoneApprovals.EXTRA_ID`). `setAutoCancel(true)` removes the notification. The in-app card is never shown, because `raise()` already chose the notification. The owner opened Vox to answer and sees nothing. The card then expires as a deny. MCP-SURFACE:424-425 and D37 promise "tapping the body opens Vox" as a way to answer.
- **Dismissing is not a deny.** There is no `setDeleteIntent`, so a swipe does nothing and the card waits out its two minutes. "A card that is dismissed without an answer is a deny" (MCP-SURFACE:401, D37, notification text "Dismissing or silence is a no") is false. The outcome is still a deny, but two minutes later and audited as `silence`.
- **No fallback when the in-app surface declines.** If `AppFocus.inFront` is true but `ui` is null or `show` returns false, `raise` returns false and the request is `unavailable`. This happens with Settings, Models, Onboarding or the Phone tools sheet in front, or with MainActivity not yet created. The notification is never tried.
- In PiP the activity is paused, so the card goes to the notification. During a live call it is therefore **not spoken**, though the voice answer path still listens for "yes".
- Suggested fix:
  - `raise = ui?.show(card) == true || notify(card)`.
  - Handle `EXTRA_ID` in `onCreate`/`onNewIntent` by re-showing `engine.currentApproval()`. On `onResume`, re-show any waiting card.
  - Add a `setDeleteIntent` that answers `deny` via `"dismiss"`.
- **[closure]**: the lock-screen lane rewrites `notify()`. Sequence this after it, or fold it in.

### MEDIUM

#### M1 — `decide()` fails open: any choice other than the exact string `deny` is an approval
`PhoneApproval.kt:263`

`val decided = if (choice.equals("deny", true)) "deny" else "once"`. Today's callers pass `once`/`deny`. But a future `"dismiss"` (the delete intent H6 needs), `"no"`, `""` or a typo becomes **approve**. For an irreversible action the default must be deny.
Fix: `val decided = when (choice.lowercase()) { "once", "approve" -> "once"; else -> "deny" }`. Add a test: `decide(id, "dismiss", ...)` → denied.

#### M2 — A queued card's two minutes start when it is *requested*, not when it is *shown*
`PhoneApproval.kt:231-239`, `:350-359`

`expiresAtMs = now + timeoutMs` is set in `request()` even when the card is queued behind another. A second card raised after the first was answered at 1:50 is shown with about 10 s left, while the card text and the spoken line say "Two minutes". It can also expire before it is ever shown, audited as `silence` although the owner was never asked. Fix: set `raisedAtMs`/`expiresAtMs` in `raiseNext`/`request` at the moment `surface.raise` succeeds (Card is a data class; `copy()` it).
Related doc error: "One card at a time, three may wait behind it; the fourth request is `approval_busy`" (MCP-SURFACE:400, D37). With `MAX_PENDING = 3` including the shown card, **two** wait and the fourth is busy, as the KDoc at `PhoneApproval.kt:47` says.

#### M3 — `notification_read` returns content that arrived *after* the summary, under an id the summary showed
`PhoneNotifications.kt:114-139`, `:176-179`

`record()` reuses the id for an updated notification with the same key, and `read()` only checks `id in summarised`. Messaging apps update one notification per conversation, so a new message, or a fresh 2FA code in the same thread, is readable through an old id without a fresh summary. MCP-SURFACE:242-245 ("An id that arrived after the summary … is `unknown-id`") describes the privacy design this bypasses. Fix: mint a new id on every post (keep `byKey` → latest id) and drop the old id from `summarised`, or store `postedAt` at summary time and refuse a record whose `postedAt` is newer.

#### M4 — A contact name resolves to the first alphabetical *substring* match and its first number of any type
`ContactsAccess.kt:66-75`, `:110-111`

`firstNumber` = `LIKE %name%` ordered by display name, then the first number. `dial "Dan"` reaches "Aidan Smith" before "Dan", and the number can be a fax or a landline for an SMS. For `dial`, the Vox call is ended for the wrong person. Fix: prefer an exact (case-insensitive) display-name match, then a prefix match. If more than one person remains, refuse `invalid-arguments` and list the candidates (names only), so the agent asks. Prefer `TYPE_MOBILE` for `message`.

#### M5 — `clip` refuses writes Android allows, and both clipboard actions fail in PiP
`LocalActions.kt:580-593`, `:794-800`, `AppFocus.kt`

- Android 10's clipboard restriction is on **reading**: only the focused app or the default IME may read. Writing through `setPrimaryClip` from the background is permitted (AOSP `ClipboardService.clipboardAccessAllowed`, `OP_WRITE_CLIPBOARD`: "Writing is allowed without focus" — from memory of AOSP source, not re-fetched).
- `clip` refuses whenever no activity is resumed. In PiP the activity is **paused**, so after any `launch`/`open` both `clip` and `clip_read` are `unavailable` for the rest of the call.
- Claims that are false as written: MCP-SURFACE:120 and :574-576 ("Android refuses a write from an app that is not in the foreground"), `PhoneEngine.kt:125`, `LocalActions.kt:~575`.
- Fix: drop the focus gate from `clip`. For `clip_read`, rely on the platform's own null/`SecurityException`, keeping the `AppFocus` check only as a better message.

#### M6 — `launch`/`open` via PiP report `ok:true` before anything is started; `open` can "resolve" to the system chooser
`CallWindow.kt:53-60`, `PhoneEngine.kt:1620-1653`, `MainActivity.kt:1074-1080`, `LocalActions.kt:700-735`

- On the `PIP_THEN_LAUNCH` path the start is posted 250 ms later, and the result is `ok:true` with `package`/`component` regardless. `pipHost.post` silently drops the action if the activity is finishing. The comment admits it ("the window's own failure is logged rather than turned into a claim"), but the result *is* the claim.
- `resolveActivity(..., MATCH_DEFAULT_ONLY)` returns the platform `ResolverActivity` when several apps claim a link and none is default. `startUri` then starts that class explicitly, and the result reports `package:"android"` as the app that opened it.
- Fix: return `ok:true, "started":"after_pip"` (an honest pending state), or wait for the posted start under a short bound. Detect `resolved.activityInfo.packageName == "android"` and either start the implicit intent (letting the chooser run) or refuse with "several apps can open this; name `package`".

#### M7 — One oddly named file makes a whole listing `bad_path`; a pattern can backtrack catastrophically on the main thread
`PhoneFiles.kt:394`, `:333-345`

- The walk applies `badPath(e.name)` to provider-reported child names. A real file called `notes..txt`, `v1..2.zip` or `Report...pdf` aborts the **entire** listing with "resolves outside the folder you granted". That is false, and the folder becomes unlistable. The child id is already checked by `contained()`. Only names containing `/`, a NUL, or exactly `.`/`..` should be refused.
- `matches()` turns `*` into `.*` with no limit. A 255-character pattern like `*a*a*a…*b` against long names is polynomial or exponential backtracking, run per entry on the main thread. Fix: a linear glob matcher (two-pointer `*`/`?` match), or collapse consecutive `*` and cap the number of wildcards.

#### M8 — Tier 0 **Ask** advertises actions that can never succeed, and tells the agent it is waiting for an approval that does not exist
`PhoneTool.kt:603-615`, `PhoneGrants.kt:12-18`, `:287-290`, MCP-SURFACE:36

Ask on any Tier 0 group except `apps.uninstall` is offered in the enum but always refused, with the detail "this use waits for the owner's approval on the phone". No card is ever raised for it. The agent may wait, retry, or tell the owner to approve something that is not on screen. This contradicts PhoneGrants' own rule: "a promise in an `enum` is a capability the phone must be able to keep" (`PhoneGrants.kt:37-39`). Fix: either drop Ask from Tier 0 until a card exists (Off/Allow), or keep it unadvertised and word the refusal "the owner set this group to Ask; there is no approval card for it yet — ask the owner to change it in Phone tools".

#### M9 — The setup prompt tells the agent the phone cannot do what it now does
`PhoneSetup.kt:97`

"What it cannot do: read messages, place calls, send email, share files, launch apps, delete anything … this server refuses them." This is now false: `notification_read` reads message content, `file_offer` shares files, `launch`/`open` launch apps, `uninstall` removes apps after a card, and the "can do" list omits ten groups. The agent writes this into its understanding of the phone. Fix: generate the can/cannot sentence from `PhoneGrants.GROUPS` (group labels plus "only what the owner grants"), not prose. `PhoneSetupTest.the_prompt_says_what_the_phone_can_and_cannot_do` enshrines the stale sentence (see Tests).

#### M10 — The audit summariser is a denylist: a contact name and any unexpected argument are stored verbatim
`PhoneAudit.kt:66-79`

- Only `text`/`title` are reduced to lengths. `to:"Mom"` is stored, which contradicts MCP-SURFACE:289 ("the audit keeps only the lookup's count — never the query, a display name or a number"). MCP-SURFACE:523 says the opposite ("a recipient is stored as itself"), so the document contradicts itself.
- `parse` ignores unknown keys for every action except `uninstall`, despite the schema's `additionalProperties:false`. A smuggled `{"action":"clip","text":"x","secret":"<payload>"}` lands in Recent actions.
- Fix: an allowlist per action (`duration_ms`, `at`, `on`, `level`, `direction`, `id`, `name`, `folder`, `path`, `depth`, `pattern`, `offset`, `limit`, `length`, `start`, `duration_min`, `uri`, `package`, `app`). `to` becomes `"number"` / `"contact name"` and never the value. Reject unknown keys in `parseArguments` as `invalid-arguments` (the schema already says so).

#### M11 — A file window starts up to 3 bytes *before* the requested offset and ends up to 3 bytes after
`PhoneFileAccess.kt:293-306`, `PhoneFiles.kt:462-475`

The slice is `[start-3, start+want+3)`. `windowText` only skips leading continuation bytes, so preceding ASCII is included, and the trailing slack is kept whenever it decodes. An agent stitching by the offsets it asked for duplicates up to 3 characters at every seam. The reported `offset`/`length` are honest; the doc ("up to three leading continuation bytes are skipped and an incomplete trailing sequence is trimmed", MCP-SURFACE:335-337) describes inward snapping, which is not what happens. The unit test exercises `windowText` on hand-cut slices and never this arithmetic. Fix: read `[start-3, start+want+3)`, then trim to the first character boundary `>= start` and the last boundary `<= start+want`.

### LOW

- **L1** `PhoneEngine.kt:1414-1417` — `DIAL_FAILED` is reported as `no_app`. The dialer resolved but would not start, which is `unavailable` by the surface's own vocabulary.
- **L2** `PhoneFileAccess.kt` `hold()` (`MAX_OPEN_OFFERS = 4`) — a fifth `file_offer` closes the first offer's server before the `expires_at` its result promised. **[closure]**
- **L3** `PhoneApproval.kt:369-374` — an *approved* card the agent never executes is audited `expired via=silence`, as if the owner had not answered. Use `via=unused`.
- **L4** `PhoneApproval.kt:140-147` — `canonical()` joins `key=value` with no escaping (`{"a=b":"c"}` ≡ `{"a":"b=c"}`), and `null` ≡ `""` ≡ whitespace. This is harmless today because `uninstall` takes only `package` and `parse` rejects other keys. But the function is documented as general ("cannot be confused"), and the closure lane is about to reuse it for `file_offer`. Encode it as canonical JSON (sorted keys, NFC-normalised strings) before reuse. **[closure]**
- **L5** `PhoneTool.kt:691` — `skip` accepts `"prev"`, which is neither in the schema enum nor in the docs.
- **L6** Tier 1's "reported to the mind on the next turn" (MCP-SURFACE:296, `PhoneTiers` KDoc) is true only for `note`. `PresenceContext`'s `local_actions:` line carries timers, alarms, DND, volume and notes, never a file read, a listing, an agenda read or an `event_create`.
- **L7** `PhoneGrants.instantAll` is unused. If it is ever wired up, it grants Notifications, Contacts and Apps (and therefore the uninstall request path) in one tap. Either delete it or exclude the privacy-read groups.
- **L8** `MainActivity.kt:48-83` — the phone card and the gateway card share one view. A phone card raised over a pending gateway card replaces it, and its `withdraw` hides the gateway card while that card is still pending.
- **L9** `LocalActions.kt:106` — `restoreNotes` restarts each note's 24-hour undo on every process start (see H4).
- **L10 (note, opinion)** — Accepting the connection's API key as a second bearer (#452) means every client holding `API_SERVER_KEY` (relay, desktop, any profile sharing the key) can drive the phone. The grants are also device-wide, not per profile. This is a deliberate trade, but worth one sentence in MCP-PHONE's threat model.

---

## Tests that prove nothing

| Test | Why it proves nothing | What would make it mean something |
|---|---|---|
| `McpSurfaceContractTest` (both tests) | Named "contract". It only asserts that `docs/MCP-SURFACE.md` contains certain sentences. It never checks a doc claim against code, so all the doc errors above (three waiting, 64 KiB, background clip, dismiss = deny, step 26) pass it. | Parse the action tables and assert each listed action is in `PhoneGrants.GROUPS[*].actions` and each listed refusal is a `PhoneReason` constant the engine can return for that action. Assert the enum equals the table. |
| `PhoneFileListTest.a_symlink_that_leaves_the_granted_tree_is_bad_path` | The symlink case runs against the **test's own** `Tree.reader`, which re-implements the canonical check (`:138-146`). Production `PhoneFileAccess.children` is only checked by `listing.contains("canonicalWithin")`. Delete the check from production and the test still passes as long as the word appears. | Extract `children()`'s id/canonical decision into a pure function taking `(dirId, childId, root)` and test it with a real temp-dir symlink. |
| `PhoneFileListTest.the_listing_path_never_opens_a_file` | A substring blacklist over a source region. A helper call (`FileStage.stage`, `readRange`) or a renamed API passes it. | Keep it if wanted, but the real guarantee is the `Reader` interface's shape. Assert `PhoneFileAccess.list` uses only `query`, via a fake `ContentResolver` that fails on `openInputStream`/`openFileDescriptor`. |
| `PhoneFileListTest.a_window_snaps_to_character_boundaries…` | Tests `windowText` on slices the test cut, not the `[start-3, start+want+3)` slicing production does (M11). | Drive `readWindow`'s arithmetic, factored into a pure function over a byte array, and assert `text` begins at the first boundary `>= offset`. |
| `PhoneFileListTest.a_window_read_returns_the_slice…` / `a_window_that_is_the_whole_file…` | The fake platform returns a `Window` the test constructed, so this pins the engine's field copying. The unbounded `length` (H2) passes. | Add a parse test where `length > MAX_READ_BYTES` is refused, and a platform-arithmetic test. |
| `PipWiringTest` (all three) | `src.contains("…exact line of MainActivity…")`, restating the implementation. The guarded line can be moved into dead code and the test stays green. | Test `enterPip`'s decision through `CallWindow.Host`/`Pip` (already pure), and drop the line-match assertions. |
| `PhoneHandoffTest.dial_ends_a_live_call_in_the_order_announce_end_launch` | The fake `announce` calls `after` synchronously and `dial` always succeeds, so the asynchrony, the discarded `dial()` result and the cut announcement (H1) are invisible. | Use a fake host that defers `after`, fails `dial`, and records when `ok` was returned relative to `end`/`dial`. |
| `PhoneHandoffTest.message_never_calls_a_send_api` (and the `dial`/`placeCall` source scans) | Source-substring absence checks. They are a reasonable tripwire, but they prove nothing about whether launches succeed (B2). | Keep them. Add a platform test that a not-visible host refuses before `startActivity`. |
| `PhoneApprovalTest.one_card_at_a_time_three_wait_and_the_fourth_is_busy` | Its name says "three wait"; its body shows two waiting behind one. It pins the code, and its name repeats the doc's error. | Rename it, and add a test that a queued card gets a full two minutes from the moment it is raised (M2). |
| `PhoneApprovalTest` (whole file) | No test that a non-`deny` choice is denied (M1), that dismissal denies, or that a card cannot be answered after `withdraw`. | Add those three. |
| `PhoneSetupTest.the_prompt_says_what_the_phone_can_and_cannot_do` | **Enshrines a false claim.** It requires the prompt to say the phone cannot "read messages" or "delete", while `notification_read` and `uninstall` ship. | Assert that the prompt's capability sentence is derived from `PhoneGrants.GROUPS` (every group label appears) and that it names no capability absent from the registry. |
| `PhoneSetupTest.the_prompt_writes_the_documented_mcp_config_shape` (`:88`, `:90`) | **Asserts external facts that are false.** "no per-server tool filter exists in the reference" and "`trust:` is implemented but not documented". Upstream `website/docs/reference/mcp-config-reference.md` at `f0d94882e8` documents both (`tools: include/exclude` at `:36-38` and `:116-119`; `trust` at `:72`), and `tools/mcp_tool_registration.py:245-260` implements `tools.include`. `PhoneMcp.kt:385-386` says the opposite of this test. The behaviour (omitting the keys from the prompt) is fine; the stated reasons are invented. | Keep the `assertFalse` lines if wanted, but change the messages to the real reason ("the prompt keeps to the minimal shape"), or delete them. Never cite "the reference" without a pinned upstream line. |
| `PhoneGovernanceTest`/`PhoneToolTest` grant tests | They cover Off/Ask/Allow per action but never the **cross-group** path. Contacts=Ask + Calls=Allow (H3) and all-Off `status`/`undo` (H4) are untested. | Add both. |

**About the premise in the brief.** It says `tools.include` "does not exist". In the upstream checkout reviewed (`f0d94882e8`) it **does** exist and is documented, as shown above. The setup prompt's omission of it is harmless. The false claim is the test's, and it runs in the other direction.

---

## Claims that are not true

| Where | Claim | Reality |
|---|---|---|
| `PhoneTool.kt:46-51`, MCP-SURFACE:51, D34 | `ok:true` only after the platform performed the action | Not for handoffs under BAL (B2), `dial` during a call (H1), or PiP launches (M6). Not for the timeout path, which says the opposite of what happens (H5). |
| `LocalActions.kt` `uninstall` KDoc | "A background activity launch the platform refuses becomes `unavailable`" | BAL refusals do not throw (B2). |
| MCP-SURFACE:330, `PhoneFiles.kt:23` | "`file_read` returns at most 65 536 bytes of text in one answer" | `length` is unbounded (H2). |
| MCP-SURFACE:335-337 | windows snap inward | They snap outward (M11). |
| MCP-SURFACE:302-303, :579-582 (device pass 10) | Downloads granted through the picker, "`Granted · Download`" | Not grantable on API 30+ (B1). |
| MCP-SURFACE:120, :574-576; `PhoneEngine.kt:122-126` | Android refuses a background clipboard **write** | Android restricts reads, not writes (M5). |
| MCP-SURFACE:132; `PhoneGrants.kt:247-249` | `undo` "only reverses something the owner already allowed" | It reverses the owner's own actions too (H4). |
| MCP-SURFACE:36, `PhoneGrants.kt:15`, `PhoneTool.kt:610` | Tier 0 Ask: "the owner's approval card owns it" / "waits for the owner's approval" | No card exists (M8). |
| MCP-SURFACE:242-245 | an id cannot reach content that arrived after the summary | It can, through an updated notification (M3). |
| MCP-SURFACE:289 vs :523 | audit never keeps the contact query / audit stores the recipient as itself | The code does the latter; the doc contradicts itself (M10). |
| MCP-SURFACE:400, D37 | "three may wait behind it" | Two (M2). |
| MCP-SURFACE:401, D37, notification text | dismissing the card is a deny | No delete intent (H6). |
| MCP-SURFACE:424-425, D37 | tapping the notification opens Vox to the card | Opens Vox without the card, and the notification is gone (H6). |
| MCP-SURFACE:661-663 (pass 26) | "`file_list` and `file_read` do the same [`approval-required`] while Files is Off" | Off is `not_granted` (`PhoneTool.kt:585-593`). |
| MCP-SURFACE:296 | Tier 1 actions are "reported to the mind on the next turn through the presence context" | Only `note` is (L6). |
| `PhoneSetup.kt:97` | the phone cannot read messages, share files, launch apps, delete anything | All shipped (M9). |
| `PhoneTool.kt:985-988` | `intent:` is refused because "Android starts an explicit component from it" | That is true only of `Intent.parseUri`. `startUri` uses `Uri.parse` + `ACTION_VIEW`, so an `intent:` URI would just be data handed to whatever claims the scheme (typically a browser, which applies its own rules). The refusal is fine; the stated reason is wrong. |
| D34 (no amendment) | the tool enum is seven actions; "Tier 2 (share, message, call, email, delete, purchase, launch, send) never executed"; Tier 2 is routed to the gateway's approval surface; the setup turn carries the bearer token | Superseded by D35–D37 and #452. D35 got an amendment line; D34 did not. |
| D35:"The two things we will not build" | notifications reading is "approval-tier … no 'just this once' that skips the card" | The read lane shipped notifications as Tier 0 with Allow and no card. D35's amendment mentions only the group count. |
| D36 (no amendment) | "`uninstall` … is never advertised"; "Contacts lookup itself is declared, not built, so a granted name still refuses `unavailable`" | Both superseded by D37 and the read lane. |
| `docs/INDEX.md:4` | "Every file under `docs/` … is classified here" | `docs/MCP-SURFACE.md`, the contract document, is not listed. `:29` says "D1–D35"; the log has D36 and D37. The header pins `2dd7e56`. |
| `skills/hermes-vox-app/SKILL.md:1093-1099` | "No consequential action from the agent" | Tier 0 Allow lets the agent read notification content and the clipboard, end the owner's call (`dial`), and export files (`file_offer`, Tier 1, no card). The citation `PhoneTool.kt:621 PhoneTiers.APPROVAL ->` is the refusal branch for *groupless* Tier 2 names, not evidence that Off actions are absent from the enum. |
| SKILL.md:758 | `event=phone-mcp action=<timer/alarm/dnd/volume/note/undo>` | Twenty more actions log this line. |

Not found to be false: the constant-time bearer check (`PhoneMcpToken.matches` folds length and bytes; `authorized()` always computes both comparisons); the single-address bind with the any-local refusal; `isError:false` on refusals; the `${API_SERVER_KEY}` header shape (upstream `_interpolate_env_vars` resolves it from the profile secret scope, and `_require_rendered_remote` fails closed on an unresolved reference, as the prompt says); the `uninstall` package regex; the canonical binding as used today; and all 385 `file:line` references in SKILL.md resolve within their files.

---

## What I could not verify

- **Anything on a device.** Specifically:
  - B2: whether each handoff is silently blocked with the screen locked, or from a notification-approved `uninstall`, on the owner's Pixel/Android version. The platform rule is documented; its exemptions vary by version.
  - H1: whether the announcement is audible at all before the teardown.
  - H6: the notification tap and swipe behaviour.
  - PiP: entry, expand without reconnect, Stop. By code reading, expand is a repaint (`leaveCompact`), `configChanges` prevents recreation, and no second controller is created. Not exercised.
- **The clipboard write rule (M5)** is from memory of AOSP `ClipboardService`. I did not re-fetch the source.
- **Android 15+ sensitive-notification redaction.** Untrusted `NotificationListenerService`s receive OTP notifications with content redacted. If the device is on 15+, `notification_read` of a code may return a placeholder, and the group's warning ("codes … go to your agent") and the D36 sentence about 2FA codes are partly moot. Needs a device.
- **Voice self-approval.** The phone card's spoken line ends in "Approve?". If echo cancellation leaks and STT segments that word alone, `ApprovalVoice` (whole-utterance match on `approve`) would approve. Needs a device with continuous listening on.
- **Symlink escape on the primary volume.** Emulated storage on 11+ generally refuses symlink creation, and `canonicalFile` under FUSE may fail or return the lexical path. Whether `canonicalWithin` ever triggers, or wrongly refuses, needs a device.
- **Gateway behaviour when `dial` ends the call mid-turn.** Whether Hermes keeps the tool result and the rest of the run for the next call ("the conversation is resumed") was not checked against the gateway.
- **Out of depth.** The turn path (`/v1/runs` fallback, Stream, ResponsesClient), sessions browse/resume, diagnostics, presence reports and `DECISIONS.md` D29–D33 were only skimmed, as the brief asked. No findings are claimed there.
- **The gate.** I did not run `scripts/gate.sh`, to avoid touching the build directories of a shared worktree. `scripts/check-doc-truth.py` reports 35 invariants hold. It does not cover any of the doc claims above.

---

## Work items (lanes)

Each lane can be fixed, gated and merged on its own. Order matters where noted.

1. **Grant governance** — H3, H4, M8, L7.
   Files: `PhoneTool.kt` (`recipient`, Ask refusal), `PhoneEngine.kt` (`status`, `undo`, registry initiator), `PhoneGrants.kt`, `LocalActions.kt` (`restoreNotes`, owner-path tagging), `PhoneGovernanceTest`/`PhoneToolTest`/`PhoneEngineTest`.
   Goes **first**: it changes what the other lanes may advertise.

2. **Files: grant, path and window** — B1, H2, M7, M11.
   Files: `PhoneFileAccess.kt`, `PhoneFiles.kt`, `PhoneTool.kt` (`folder` enum from grants, `length` maximum, unknown keys), `PhoneEngine.kt` (`folderFor`, `noFolder`), `PhoneToolsSheet.kt` (picker initial URI, label copy), `PhoneFileListTest`/`PhoneFilesCalendarTest`, MCP-SURFACE files sections.
   After lane 1. Touches the `file_offer` arguments the closure lane will gate **[closure]**, so coordinate the schema.

3. **Off-main-thread engine and honest timeouts** — H5, plus the walk half of M7.
   Files: `LocalActions.kt` (`phone()`, executor, main-thread posts for startActivity/PiP only), `PhoneEngine.kt` (lock scope), `PhoneFiles.kt` (lazy walk).
   After lane 2, which changes the walk it makes lazy.

4. **Handoff truth: launches, dial, PiP lifecycle** — B2, H1, M6, L1.
   Files: `LocalActions.kt` (Platform `dial`/`startApp`/`startUri`/`uninstall`/`startHandoff`, visibility gate), `PhoneEngine.kt` (`dial`, `handOff`), `DialHandoff.kt`, `CallWindow.kt`, `MainActivity.kt` (`announceDial` on done, `endCall` leaves PiP), `PhoneHandoffTest`/`CallWindowTest`/`PipWiringTest`.
   After lane 3, because the main-thread posting changes. The `uninstall` half overlaps **[closure]**.

5. **Approval desk and surface** — H6, M1, M2, L3, L4, L8.
   Files: `PhoneApproval.kt`, `PhoneApprovals.kt`, `MainActivity.kt` (`EXTRA_ID`/`onResume` re-show, card view separation), `PhoneApprovalTest`.
   **After the approval-closure lane lands**: it rewrites `notify()` (lock screen, `VISIBILITY_PRIVATE`) and adds `file_offer` cards, which need M2's per-card timer and L4's canonical JSON.

6. **Read-group privacy** — M3, M4, M10.
   Files: `PhoneNotifications.kt`, `ContactsAccess.kt`, `PhoneAudit.kt` (allowlist), `PhoneTool.kt` (reject unknown keys, if not done in lane 2), `PhoneNotificationsTest`/`PhoneContactsTest`/`PhoneAuditTest`.
   Independent; any time after lane 1.

7. **Clipboard** — M5.
   Files: `LocalActions.kt` (`clip`, `readClip`), `AppFocus.kt` (comment), MCP-SURFACE clipboard section, `PhoneSurfaceTest`.
   Independent.

8. **Docs, prompt and test truth** — M9, the "Claims that are not true" table, and the "Tests that prove nothing" table.
   Files: `PhoneSetup.kt` + `PhoneSetupTest.kt`, `McpSurfaceContractTest.kt` (make it check code), `docs/MCP-SURFACE.md`, `docs/DECISIONS.md` (amend D34/D35/D36), `docs/INDEX.md` (add MCP-SURFACE, D36–D37, re-pin), `skills/hermes-vox-app/SKILL.md` (1093-1099, 758) **[anchor]**.
   Goes **last**: every other lane moves lines and changes behaviour the docs describe. The anchor-format lane should land before it, so re-anchoring happens once.
