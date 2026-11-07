# Voice models screen (#219)

The Voice models screen is a presentation of one catalogue (`ModelCatalog.blessed`)
and one download state machine (`ModelDownloads`, fed by `ModelDownloader`). #219
changed how those facts are laid out, not what they are.

## Grouping: by job, in the order a person needs it

`ModelShelf` (pure, JVM-testable) shelves the catalogue by `ModelSpec.kind`:

| Shelf | Catalogue kinds | What the blurb says |
| --- | --- | --- |
| **Hearing** | `stt`, `vad` | Turns your speech into text, and hears when you start and stop talking. |
| **Voice** | `tts` | Speaks Hermes' replies aloud, fully on-device. |
| **Being** | `express` | Runs the optional on-device Presence layer — the being, its asides and its voice. |

- The three shelves always appear in that order.
- A kind that is not one of those three is **not hidden**: it gets its own shelf
  named `Other — <kind>`, after the three known shelves, sorted by kind. (The
  shipped catalogue uses only the three named shelves; the fallback exists so a
  future kind cannot silently disappear from the screen.)
- Within a shelf, `recommended` models come first, then the catalogue's `order`.
- Every input spec appears exactly once (tested).

The ★ **Recommended** chip is `ModelSpec.recommended`, unchanged. It is the same
set as `ModelCatalog.required`, which is what the header, the Settings badge and
the "install the missing required models" button count.

## Row anatomy, in reading order

1. **Name.**
2. **★ Recommended** chip, when `recommended`.
3. **What it does** — `ModelSpec.purpose` (the one plain-language sentence), never
   the catalogue's internal `desc`. Falls back to `desc` only if a future entry has
   no purpose.
4. **Size** — `ModelRowState.sizeLabel(spec.sizeMB)`: the catalogue's decimal MB,
   one decimal, no re-rounding (`82.0 MB`, `2588.1 MB`).
5. **State** — see the table below. Always text; the colour is only a hint.
6. **Licence badge** — only when `ModelSpec.licence != null` (a non-permissive
   licence). Its text says what tapping does, and tapping opens the same
   `showLicence()` dialog the Download button uses. There is one acceptance path.
7. **Progress bar and action button.**

All of these are in a vertical stack with `wrap_content` heights, so the largest
system font wraps rather than clips the size or the state. The state TextView is an
`accessibilityLiveRegion="polite"`, and the action button carries a
`contentDescription` of `"<action> <model name>"`; the purpose, size and state are
ordinary TextViews, so a screen reader reads the row in the same order as the eye.

## State table

`ModelRowState.of(state, licence, licenceAccepted)` takes the exact
`ModelDownloads.State` the screen observes and the exact acceptance
`ModelLicences.isAccepted` / `ModelDownloader` enforce. There is no second copy of
the state logic; the mapping is exhaustive over the sealed state and the phases.

Precedence: **Installed** first (a model that is on disk is installed, whatever a
stale licence record says), then **needs licence**, then the download state.

| Facts from the download path | Stage | State line (plain language) | Button | Progress |
| --- | --- | --- | --- | --- |
| `Installed` | `INSTALLED` | Installed and ready | Installed (disabled) | hidden |
| not installed + licence not accepted | `NEEDS_LICENCE` | Needs a licence accepted — *licence name* | Review licence | hidden |
| `Idle`, no unaccepted licence | `NOT_INSTALLED` | Not installed | Download | hidden |
| `Queued` | `QUEUED` | Waiting — downloads run one at a time | Cancel | indeterminate |
| `Running(DOWNLOAD, done, total>0)` | `DOWNLOADING` | Downloading — *done* of *total* MB | Pause | determinate (0 % is shown honestly) |
| `Running(DOWNLOAD, done, total<=0)` | `DOWNLOADING` | Downloading — no progress reported yet (plus MB so far if any) | Pause | indeterminate |
| `Running(WAITING_NETWORK, done, *)` | `WAITING_NETWORK` | Waiting for network — *done* MB kept, resumes automatically | Pause | indeterminate |
| `Running(VERIFY, *)` | `VERIFYING` | Verifying the download… | Verifying… (disabled) | indeterminate |
| `Running(UNPACK, *)` | `UNPACKING` | Unpacking the files… | Unpacking… (disabled) | indeterminate |
| `Paused(done, total, reason != null)` | `CANNOT_START` | Cannot start — *the reason* (plus MB kept) | Resume | determinate if bytes exist |
| `Paused(done>0, *, reason == null)` | `PAUSED` | Paused — *done* MB kept, resumes where it stopped | Resume | determinate |
| `Paused(done<=0, *, reason == null)` | `PAUSED` | Paused — nothing downloaded yet | Resume | hidden |

Notes:

- **Cannot start** is the failure arm of `Paused`: `ModelDownloader.run()` returns
  an error string (for example "not enough storage", a sha256 mismatch, or a
  retry-exhausted stream error) and `ModelDownloads` publishes it as
  `Paused(reason)`. The reason is shown verbatim; it is not reworded or guessed.
- There is no separate "unsupported device" state in the state machine today, so
  none is invented. A model that cannot run on a device would currently have to
  fail at load time in the pipeline, which this screen does not observe.
- The exact size is the *download* size (catalogue decimal MB). Installed bytes
  on disk can differ once unpacked, and the screen does not claim otherwise.
- The licence badge is shown whenever the licence is non-permissive, including
  after acceptance; its wording changes from "Licence needed: … — tap to review
  and accept" to "Licence: … — tap to review".

## Advanced controls

One disclosure at the bottom, collapsed by default: **Advanced — download source**.
It holds the model-source override (`ModelCatalog.KEY_SOURCE`, read by
`ModelCatalog.source()` and therefore by `ModelDownloader`). Saving normalises the
value through the existing `ModelCatalog.resolveSource()` rule, so a non-`http(s)`
address falls back to the default exactly as the downloader would.

**No destructive per-model action exists in this tree.** There is no
delete-a-downloaded-model command in the catalogue or the download state machine,
so the disclosure contains no delete control: adding one would have required
changing the state machine (its `states` cache, and the installed check) rather
than moving an existing control. If a delete action is added later, it belongs in
this same disclosure.

## The decision I was least sure of

**Calling the failure arm "Cannot start" instead of "Stopped".** The state machine
publishes one `Paused(reason)` shape for both a user pause (`reason == null`) and a
transfer that failed (`reason != null`); there is no separate failure state. I
split them so a genuine failure reads as a blocker ("Cannot start — not enough
storage …") while a deliberate pause keeps its reassuring "resumes where it
stopped" wording. The alternative — one neutral "Stopped" state for both — would
have been fewer branches, but it would hide the difference between "you paused it"
and "it could not begin/continue", which is exactly what a user arriving from
onboarding needs to know. The wording can be revisited; the underlying split is
cheap to change because it lives in one pure function with a test per branch.

A second, smaller judgement: the purpose line uses `ModelSpec.purpose` and falls
back to `desc` only when purpose is blank. The rejected alternative was to rewrite
the copy in the screen; that would have created a second catalogue of model
descriptions that could drift from the model card.
