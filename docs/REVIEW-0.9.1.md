# The 0.9.1 review

**Written 2026-11-11**, the day `0.9.1.3` was published. It covers the release cut
on 2026-11-07 and the four days that followed it, and it is written to be checked:
every number below names where it came from, and the two that could not be
reproduced are marked as such.

The owner's own words frame it: he rushed the release out because he was excited
to have users; the project stopped being a private toy; he owes it more care.
This document is the list of what that cost, in order, and what now stands where
it happened.

## What shipped, and when

All times UTC, read from the release pages and the tags.

| Version | Published | Code | What it was |
|---|---|---|---|
| `0.9.1` | 2026-11-07 16:47 | 147 | Presence, profiles, look and sound — the feature release |
| `0.9.1.1` | 2026-11-09 19:52 | 148 | The profile list read in the shapes an agent writes |
| `0.9.1.2` | 2026-11-09 20:48 | 149 | Corrections to 0.9.1.1's own default rule |
| `0.9.1.3` | 2026-11-11 13:10 | 150 | The cleanup cut: the two defects, the readiness gate, Piper recommended |

Four releases in four days. Two of them fifty-six minutes apart, because the
first patch's rule was wrong. `#517` — the playback buffer change that caused the
worst defect — merged at 13:00 on 2026-11-07, five hours before the tag that
shipped it.

`#538`, the field report, was opened at 00:17 on 2026-11-09, about thirty-two
hours after 0.9.1 was published. Two further releases went out while that report
was open and unresolved.

## The two defects, as a user met them

**A short reply hung the call.** Any reply with less than four seconds of speech
never began playing, and the turn sat waiting for audio that would never arrive
until the 120-second drain ceiling expired. The screen said the entity was
working; the microphone took no turn; barge-in had nothing to hear. `/new`
appeared to fix it because the next reply was usually longer. There was no error
message anywhere, which is why the field report took two days and a log to
resolve.

**A re-warmed voice poisoned the reply it was warming for.** A replacement engine
starts with its audio fence closed; the open reply had only ever opened the old
engine's fence, so every later chunk of that reply was read as a failure. One bad
phrase could spend the whole voice budget: re-warm, then the system voice, then
no speech at all until `/new`.

Both are fixed in `0.9.1.3`. The first by a quarter-second start threshold on
API 31+ and a drain bounded by the audio owed; the second by adopting the
re-warmed engine into the open stream and naming the outcome of every admission.

## The five failures

### 1. Scope drift inside a patch release

`0.9.1` carried `#517`, a change to how streamed audio is buffered and played,
and `#373`/`#499`, a change to the voice engine's lifecycle. Both are transport
and media work. Both were reviewed as part of a release whose visible subject was
profiles.

The evidence is in the merged pull request: `#517`'s own body describes a rewrite
of the chunker and a buffer capacity change, and its tests assert chunk
boundaries, not playback. Nothing on the path it changed could fail when a reply
was too short to fill the track.

**What now prevents it:** nothing structural. `docs/REGRESSION.md` gives the path
a catcher, which is the right repair, but the decision to put transport work in a
feature cut is still made by judgement. A release whose scope names features will
keep absorbing plumbing unless the cut checklist asks one question — *what does
this touch that a phone can see?* — and that question is not yet on the list.
This is the one failure with no answer in the tree.

### 2. No per-cut device regression set existed

`docs/REGRESSION.md` was written on 2026-11-11, four days after the release it
exists to prevent. Before it, the manual acceptance cards (`#375`) existed as a
full matrix but were not a gate on any particular cut, and `scripts/gate.sh` —
which was green, and honestly green — tests pure logic, parsers, budgets and
parity. The unit suite was green exactly where the defect lived, because the
defect lived in what a streamed `AudioTrack` does on a device.

**What now prevents it:** the twelve-path regression set and
`scripts/regression-smoke.sh`, which decides the automatable half and prints
`UNKNOWN` for anything it did not observe. It is written down, it is per cut, and
it has been walked once. It has not yet been walked on a cut that had no defect
in it, which is the only way to learn whether it catches one.

### 3. Claims outran evidence

The 0.9.1 notes describe work that was real and unproven. Two lines are worth
quoting because they are the shape of the failure, not its size:

- *"Speech-end to first-sound measured"* — measured in isolation, on a bench,
  not on the path a field reply takes.
- *"60-second turn ceiling"* — the drain's ceiling was `finishStreaming(timeoutMs
  = 120000)` at `v0.9.1`. The note says sixty; the code says a hundred and twenty.
  Nobody was lying; the number was remembered rather than read.

The cost was trust, not code. The reporter spent two days testing settings that
were never the cause.

**What now prevents it:** the rule at the head of `docs/REGRESSION.md` and in
`docs/RELEASE-PROCESS.md` — no claim ships in a release note that was not
measured on that build or confirmed by the person who reported the issue. It is a
good rule. It is also, on the evidence below, not yet strong enough.

### 4. The field log was misread, twice, and the third reading did not agree either

The reporter's log had no date on any line, and the merged file carries two
builds ordered by nothing in particular. It was read once by the steward as a
single continuous session, which produced nonsense durations; once by an agent
lane, which named a different cause; and once more to write the cleanup release.

The third reading is the one to look at, because it is in the repository now, and
it does not reproduce.

- `docs/RELEASE-0.9.1.3.md` says: *"40 replies carried under four seconds of
  audio and 22 of them ran to 126.8–127.1 s… Of the 83 replies of four seconds or
  more, 9 ran that long."*
- `StreamDrain.kt`'s own comment says: *"28 of the 29 replies under 4 s waited
  until that ceiling or until the caller hung up; every reply of 4 s or more
  drained in under 6 s."*

Two documents, one log, different counts. I recounted it, and the count is in
`scripts/field-log-turns.py` so that the next person does not have to take my
word for it. The only log that survives is
`/home/robot/Downloads/hermes-vox-merged.log`; the `0.9.1` block in it can be
identified because `speechEndToRenderDone` exists in `LatencyStats` at `v0.9.1`
and not at `v0.9.0.3`, so the field appears at that build's boundary (strictly:
"0.9.1 or later" — 0.9.1.1 and 0.9.1.2 carry it too). Counting from the first
line that carries it (`--since-field speechEndToRenderDone`, line 48958):

```
turns 149 · gateway time: fast (<4 s) 78 · slow 42 · absent 29
reply audio (est. chars / 15.3 per s): short (<4 s) 44 · long 74 · no length 31
short replies at the 120 s budget: 12 (14–50 chars, render 126.8–128.8 s, all stream-done)
short replies held >15 s after their text arrived: 25 (12 at the budget, 11 stop, 2 focus-loss)
long replies with render >= 120 s: 5 (1,506–2,694 chars: playback, not a stall)
```

*Corrected 2026-11-11.* The first version of this section, and of the script,
counted `fullReply` as "seconds of audio". It is the time from turn launch to the
gateway's `done` (`VoiceController.kt`, `pushFullReply` on the done event), so its
"78 short" was 78 *fast gateway* answers; and it counted `turn-dropped` lines as
turns. It also read the five long renders past two minutes as contradicting the
code comment. They do not: they are replies of 1,506–2,694 characters, roughly
100–175 seconds of speech, and a render-done time includes playing it. The
stall count did not change — twelve, under every definition — because the stalled
replies are both short and fast.

So: **twelve** short replies ran to the ceiling, not twenty-two; about
forty-four were short, by the character estimate (the log records no audio length
for a streamed reply); and whether every longer reply drained in under six
seconds, as the code comment claimed, **cannot be measured from this log** — it
predates `event=tts-drain`. If the whole merged file is counted as one build instead, the figures are
different again — which is the point: the file cannot tell you which build a line
came from, and no document in the tree says which rule produced the number it
quotes.

**What this does not change:** the defect is real and the mechanism is confirmed
twelve times over, with a signature that cannot be anything else — replies of
14 to 50 characters (roughly one to three seconds of speech), a render that ended
at 126.8 to 128.8 seconds, and no error.
The fix is right. **What it does change:** the release document that was written
*after* the fact, to end this class of failure, repeated it. The evidence rule
needs a second clause — a quoted number names the file it came from, the rule
that counted it, and the script that counts it — or the rule catches prose and
misses arithmetic.

**What now prevents it:** the log is dated from `0.9.1.3` onward, which fixes the
merge problem at the source. `scripts/field-log-turns.py` now prints its
denominator, names what each field measures, and is pinned by
`scripts/test_field_log_turns.py` in the gate. Nothing yet requires either to be used.

### 5. Excitement set the date, and nothing in the process could say no

The owner says the date was enthusiasm, not a gate. The question the review was
asked to answer is whether anything in the process could have resisted it.

The honest answer is no, and the reason is specific: **the only gate that existed
was a build gate, and a build gate that is green reads like proof.** `gate.sh`
passed, CI passed, two reproducible builds hashed identically, the tag pointed at
the commit that was built, the APK installed over the previous one. Every
mechanical fact about 0.9.1 was sound. None of them could see a reply that was
too short to start a stream. A green build gate does not merely fail to catch a
device defect; it actively supplies the confidence that ships it.

Nothing else was positioned to object. The acceptance cards were not a per-cut
gate. The nightly builds the same commit and is green for the same reasons.
Nobody's role included *use the release candidate and say whether it is good*,
and the person who would have said so was the person who was excited.

**What now prevents it:** a per-cut device pass (path 4 in the regression set is
literally *a short reply speaks and ends promptly*), and — from 2026-11-11 —
**the human run** (D41): before a tag, someone installs the candidate and uses it in
ordinary course, and the release document records whose run it was and what they
did. The owner set the gate as a *run* rather than a soak duration, which is the
sharper instrument — a duration asks how long a build has sat, and the run asks
whether it was used at all. **The honest gap:** he also set it to default to
himself until a volunteer takes the role, so today the reviewer and the cutter are
the same person. That is a real run and not an independent one, and the document
says which it was rather than letting a tick imply the stronger thing. In 0.9.1
nobody had run it but its author, which is exactly the state this gate is meant to
end and does not yet end.

There is also no calendar any more (D42). A release ships when the gates are green,
which removes the pressure this failure names at the source rather than trying to
survive it. That trade was made knowingly — see D42 for what it gives up.

## What the tree claimed and the tracker did not

Three smaller failures of record, all cheap to fix and all the same defect —
a document asserting a state that the issues disagree with:

1. **The cut tracker was not closed at the tag.** `#548` is 0.9.1.3's own
   checklist, and its gate paragraph says the cut is made when the checklist is
   met. At the tag, four items were unticked: the voice witnesses on a real
   Supertonic call, `#566`, `#249`, `#209`. `#566` and `#249` were in fact done,
   and `#209`'s baseline is quoted in the release note. So the tracker is neither
   a record of what was done nor of what was not — and a reader cannot tell
   which. The one item that matters — witnesses seen on a real call — is the one
   nothing else records.
2. **`#560`, the presence unload race, is claimed in `#563`'s list and stayed
   open until 14:15 on 2026-11-11**, an hour after the tag. Either it shipped
   with a known-open defect, or the fix and the issue were never joined. Both are
   possible from the record; that is the failure.
3. **`#202` is marked done on the reasoning that it "waits on a field report, not
   code".** The field report is `#538`, in the same tracker, four lines above. The
   three deciding lines exist; nobody has yet read them against the log that
   prompted them.

**The change that now prevents it:** none yet. The governance draft that follows
this review makes the closed tracker and the issued release note the last two
items before the tag, for exactly this reason.

## What is still open, and what it costs

Not fixed, and named here so that no release note can quietly imply otherwise:

- **The recogniser runs slower than real time on the field device** (`#569`):
  RTF 1.0–1.8 against 0.68–0.76 measured in isolation. This is why turns feel
  uneven, and it is the largest defect in the product that nobody has fixed.
- **Supertonic drops words** (`#559`). Piper is recommended and Supertonic is
  labelled experimental, which is honest and is not a fix.
- **One handset** (`#208`). Everything measured in this project has been measured
  on a Pixel; the second-device matrix is still a line in a document.
- **Audio without a device.** There is no injected-audio harness. Every audio
  defect in this release was found by a human hearing it.

## The lesson, in the form that is easy to forget

The release was not late, sloppy or dishonest. It was **correct and unverified**:
every mechanism worked as designed, the tests asserted what they were written to
assert, and the two things a user does first — ask a short question, and let the
voice re-warm — were not among them. Verification is not the absence of red. It
is the presence of a check on the path a person actually takes, on the build that
person will install.

## What was decided, and what is still open

**Decided 2026-11-11**, at the governance review this document asked for, and recorded
as D41–D46 in `docs/DECISIONS.md`:

1. **No soak duration — a human run** of the candidate before a tag (D41). It
   defaults to the owner and passes to a volunteer when one exists. It replaces the
   proposed RC hold and is the only gate here that spends anything other than
   compute.
2. **No release calendar.** A release ships when its gates are green; the 72-hour
   lane carries security and gate-blocking regressions (D42).
3. **The veto:** four grounds, a 24-hour clock to record the ground, 7 days to set a
   new date, and an unrecorded veto lapses (D43).
4. **The evidence rule and the closed tracker block a tag**, and a number that cannot
   be reproduced blocks it rather than being corrected afterwards (D44).
5. **Consensus on a major:** 14 days' note, 7 days' call, silence as consent, dissent
   recorded, money never buys a vote (D45).
6. **The community starts on Discord**, bridged to Matrix later as an intention
   (D46) — reach over ownership, with the cost written down.

*(D47 later removed the day counts from 3 and 5: the owner replaced every fixed waiting window with the
test walkthrough and the functional suite, and what cannot be verified before a cut ships marked
pending. The grounds, the 24-hour lapse and the dissent requirement are unchanged.)*

**Still open:** the scope question (G0) — *what does this touch that a phone can
see?* — which was not among the six and remains the one gate aimed squarely at the
first failure above; and the community's code of conduct, moderator and name
(`docs/COMMUNITY.md`).
