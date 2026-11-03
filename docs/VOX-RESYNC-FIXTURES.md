# VOX.md resync fixtures (#220)

The corpus behind `VoxSoul.resync`: 28 plausible entity replies to the authoring
directive, written as plain files under `testdata/vox-resync/`. Each file is a reply
exactly as an entity might emit it (prose, fences, CRLF and all), and
`android/app/src/test/java/com/hermesvox/VoxResyncCorpusTest.kt` pins three things:
the file exists, the verdict matches the table below, and the reason or repair text
names the thing that was missing or removed.

## The workflow is per profile — and only runs with Presence on

The mirror is no longer one device-wide file. Each profile owns its own document:

- **Where.** `filesDir/vox/<profile>/VOX.md`. The profile is identified by
  `Profiles.PREF_ACTIVE` — the saved profile's **name**, the field the app already treats as
  "the active connection" (it is what the switcher marks, what `Profiles.headerLabel` prints
  beside the agent's name, and what a switch writes). The name is the right key because two
  profiles can share one gateway URL and still be different entities (each `/p/<name>` has
  its own key and scope). The name is encoded into one filesystem-safe directory component
  (`VoxMirror.dirName`): `[A-Za-z0-9_-]` passes through, every other UTF-8 byte becomes
  `%XX`, so the mapping is injective and no profile name can escape `vox/`. An install that
  has never saved a profile uses the `@` bucket.
- **Frame.** `GemmaExpress.persona` reads the ACTIVE profile's file on every render, and the
  warm LiteRT-LM conversation is rebuilt when that persona string changes; a profile switch
  re-primes it eagerly (`GemmaExpress.reprime`) so the next render is the new entity, not the
  one just left.
- **Migration.** The pre-profile `filesDir/vox/VOX.md` becomes the active profile's file on
  the first run with Presence on, once. The copy is verified and the legacy file is removed
  only after it lands; an existing per-profile mirror is never overwritten, and a failed copy
  leaves the original where it was (`VoxMirror.migrate`, pinned by `VoxMirrorTest`).
- **First call per profile.** With Presence on, a profile with no valid mirror gets one
  before Presence may speak: the first call asks the entity over the same directive turn
  (`VoxSync.obtain` → `VoxSync.ask` → `VoxSoul.resync`), and only a document the validator
  accepts is written. If the ask fails, nothing is written, Presence stays quiet for that
  call, and the Settings row says "not mirrored for <profile>".
- **Presence off.** With Presence off nothing fetches, mirrors, prompts for or reports on
  VOX.md — the Settings rows are hidden and no request is made.

**What did not change.** The document's format and the validator are exactly as they were:
the Contract bytes, the no-memory rule and the refusal of a `Memory` section are untouched,
and a document that fails validation is still never written (the reason is shown verbatim).
The corpus below therefore still describes every reply the client can receive.

## What a device session must show

1. A fresh install with one profile and Presence on: the first call waits with
   "Presence · asking the entity for its VOX.md…", then speaks as the entity.
2. Two profiles populated from the host, each with Presence on: switching profiles and
   calling again greets you in the NEW profile's voice — and the second call does not ask
   again for a mirror it already has.
3. Presence off: Settings shows no VOX.md rows, and a call makes no request on its behalf.
4. Upgrade from a build before this change: the old working mirror is still what the soul
   speaks, with no re-authoring needed.

The entity in the corpus is **Cinder**, invented for these fixtures. No real entity,
person or machine appears in the corpus, and no credential that works: the one secret
case is a made-up `sk-` string, present precisely so the validator can refuse it.

## Verdicts

| verdict | meaning |
|---|---|
| **accepted** | the reply already was the document; mirrored byte-for-byte |
| **repaired** | the document was there behind a fence, a preamble, a heading level, CRLF or a trailing courtesy line; the envelope was removed or normalized and the document mirrored on the **first** attempt |
| **rejected** | no document in the reply, or the document's structure is wrong; nothing is written and the dialog shows the reason verbatim |

Repairs only ever touch the envelope. A repair never rewords, reorders, trims or
invents a Soul value or a Contract rule — if the *content* is wrong, the reply is
rejected, not fixed.

## The corpus

| # | fixture | what an entity actually sent | verdict |
|---|---|---|---|
| 01 | `01-clean` | the document exactly as the directive asks, nothing around it | accepted |
| 02 | `02-clean-extra-soul-field` | the document plus one extra Soul line (`Voice:`) the parser ignores | accepted |
| 03 | `03-clean-blank-lines-in-contract` | the document with blank lines between some Contract rules | accepted |
| 04 | `04-fenced-plain` | the document inside a bare ` ``` ` fence | repaired — fence removed |
| 05 | `05-fenced-markdown` | the document inside a ` ```markdown ` fence | repaired — fence removed |
| 06 | `06-fenced-md-prose-both` | "Here you go…" + ` ```md ` fence + "That's everything…" | repaired — fence removed, prose dropped with it |
| 07 | `07-crlf` | the document with CRLF line endings | repaired — newlines normalized |
| 08 | `08-heading-level` | `## Contract` and `### Soul` instead of `#` | repaired — heading level normalized |
| 09 | `09-bold-fields` | `**Name**: …` on every Soul line | repaired — bullets/bold removed from the field keys |
| 10 | `10-one-line-preamble` | one line of prose, then the document, unfenced | repaired — preamble dropped |
| 11 | `11-title-line` | a `# VOX.md` title above the Contract | repaired — title dropped |
| 12 | `12-unterminated-fence` | the document inside a fence the reply ran out before closing | repaired — unterminated fence accepted, document kept |
| 13 | `13-trailing-commentary` | the document with "Let me know if anything needs changing." after it | repaired — trailing commentary dropped |
| 14 | `14-draft-then-final` | a short draft in one fence, then the complete document in a second | repaired — the last document-bearing block wins |
| 15 | `15-tilde-fence` | the document inside `~~~text` fences | repaired — fence removed |
| 16 | `16-prose-only` | a paragraph saying VOX.md was written and what is in it | rejected — "the reply has no VOX.md in it" |
| 17 | `17-missing-register` | the document without the `Register` line | rejected — no `'Register'` line, and the reason lists the required fields |
| 18 | `18-truncated-soul` | a reply cut off inside the Essence, before Register/Relationship | rejected — no `'Register'` line |
| 19 | `19-truncated-contract` | a reply cut off after rule 4, before `# Soul` | rejected — no `'# Soul'` heading |
| 20 | `20-reworded-contract` | the document with rule 3 paraphrased | rejected — "Contract rule 3 drifted", quoting the canonical line |
| 21 | `21-empty-register` | `Register: ` with nothing after it | rejected — `'Register'` is empty |
| 22 | `22-soul-before-contract` | the two sections in the wrong order | rejected — the Soul block comes before the Contract |
| 23 | `23-secret` | a Memory line carrying an invented `sk-live-…` key | rejected — looks like a credential |
| 24 | `24-essence-too-long` | a valid document with an 839-character Essence | rejected — `'Essence'` field is 839 characters |
| 25 | `25-non-document-fence` | "here is the directory" + a ` ```bash ` block | rejected — no VOX.md in the reply |
| 26 | `26-empty-reply` | whitespace only | rejected — the reply was empty |
| 27 | `27-headings-only` | `# Contract` then `# Soul` with no content | rejected — the Contract has 0 numbered rules |
| 28 | `28-long-register` | a valid document with a 479-character Register line | rejected — `'Register'` is 479 characters |

13 of the 28 are refusals. That is deliberate: a corpus of replies the code already
accepts would pass no matter what the code did.

## What the fixtures do not prove

The replies are written by hand. They cannot tell us the *distribution* of real
entity replies — how often a model fences, adds a title, or drifts a Contract rule —
which only a live gateway and its logs can show. What they can prove is that every
shape we have seen or can plausibly expect is either mirrored on the first attempt or
refused with a reason that says what to change, and that no refusal happens for a
reply that is a correct document.

Two things were also impossible to establish here and are worth watching on a device:

- whether the **revised** skeleton in `VoxSoul.AUTHOR_DIRECTIVE` actually raises the
  first-attempt rate (that needs real completions, not fixtures);
- whether the **retry** still lands when the first reply is genuinely prose — that is
  a gateway/model behaviour, not a client one.

## Adding a case

Drop a `<nn>-<slug>.txt` file in `testdata/vox-resync/`, add a row to the table in
`VoxResyncCorpusTest`, and add a line here. `VoxResyncCorpusTest` fails if a fixture
has no expectation, if a promised verdict or reason changes, if this document stops
naming a case, or if `07-crlf.txt` loses its CRLF line endings — so the corpus, the
code and this table cannot drift apart silently. A fixture whose exact bytes matter
(`07-crlf.txt`) is pinned in the repo's `.gitattributes` so a checkout cannot normalize
it away.
