# Governance — Hermes Vox

**Status: adopted in part, 2026-11-11.** Six decisions are in force and recorded as
D41–D46 in `docs/DECISIONS.md`: the confirmed human run, readiness rather than a
calendar, the veto with its clock and lapse, the evidence rule and the closed
tracker as blocking gates, and consensus on a major. The clauses that were not
among the six are still marked, because a document that overstates what was
decided is the same failure it was written to prevent.

Every clause carries one of these markers:

- **ADOPTED / IN FORCE** — decided, with its D-number, or already true.
- **RECOMMENDED** — Torc's proposal. Needs a yes or a no, and nothing depends on it
  being read as decided.

The reason this exists: a release went out on enthusiasm, two defects reached
users, and the process that should have caught them was green. The purpose of
what follows is to make promises smaller and the process more predictable — not
to add ceremony to a project one person maintains.

## 1. What this governs

This governs **the release**: how a version is decided, gated, cut, published,
and withdrawn. It is the standing agreement between the maintainer, the
contributors, and the people who install the result.

It does not govern the licence (GPL-3.0, unchanged), the CLA (contributors keep
their copyright; the grant is under GPL-3.0 only), the code of conduct in the
community space (`docs/COMMUNITY.md`), or the design of the product.

## 2. Where authority sits

- **The maintainer holds the release.** The signing key, the tag, the veto. No
  release exists without his hand on it. **IN FORCE.**
- **A contributor owns their patch.** It is merged or declined with a reason, and
  a decline names the standard it failed, not a taste. **RECOMMENDED** as the
  written rule; it is how the project has behaved.
- **The community holds voice, not the tag.** It reports defects, it is consulted
  before a major, and its objection is recorded. It does not merge, tag or veto.
  **RECOMMENDED.**
- **Severance is guaranteed by the licence.** Anyone may fork and take the code
  with them; nobody's contribution can be relicensed away from them. This is not
  a clause the project grants — it is the reason the project can ask for trust at
  all, and it is worth stating in a governance document so that no future reader
  mistakes the maintainer's authority for ownership of the work. **IN FORCE.**
- **Every power above has a lapse.** The veto must record its ground within 24
  hours or it is withdrawn (§5). A decision of the maintainer that nobody can
  check is not a decision, it is weather.

## 3. Cadence — readiness, not a calendar

**ADOPTED, 2026-11-11 (D42).** There is **no monthly release date**. A release ships
when its gates are green.

A release still has a beginning and an end, and both are written down:

- It **begins** when its scope is published as the cut tracker: what is in, and
  what is explicitly not.
- It **ends** when every gate in §4 is green and the tracker is closed.

Point releases ship as needed. A security fix, or a regression that blocks a gate,
is answered **within 72 hours** as a point release; the human run (G4)
still applies, which is why 72 hours is a target and not a promise. Nothing in
public copy names a date for a release.

**No release waits on a stranger for more than seven days.** A report, a retest or a confirmation
with no reply in a week closes with its limitation recorded and a link to the issue — the scope of a
release is never held open by somebody else's inbox. **ADOPTED, 2026-11-11.** Three of the first
release's five items were waiting on other people, which is not a scope, it is a hope.

**Why readiness and not the calendar.** This was the owner's decision on
2026-11-11, taken against Torc's recommendation of an 18th/25th/29th/1st month —
recorded here so that a later reader does not re-propose a calendar as if it had
never been considered, and so the dissent is on the record. The failure in 0.9.1
was not that a calendar existed; it was that the only thing setting the date was
enthusiasm. A date cannot be un-excited. A gate can be made to hold, and the
confirmed human run is that gate. What readiness gives up is predictability, and
that cost is accepted rather than denied: users cannot know when the next release
comes, only what it will have had to pass.

## 4. The gates

A gate is a thing that can say no. Each line below names what it proves and what
it cannot see.

**G0 — the scope question.** When the cut begins: *what does this touch that a
phone can see?* Every change that touches audio, the service lifecycle, the
network or the UI names its path in the regression set, or says in writing that it
has none. **RECOMMENDED, and this is the one gate that did not exist in any form
on 2026-11-07.** `#517` would have had to answer it.

**G1 — the build gate.** `scripts/gate.sh` prints `GATE-GREEN`: unit tests, the
doc-truth ledger, the app-skill claims, stream parity, the mTLS fixture check.
Proves the logic. **Cannot see** what a streamed `AudioTrack` does with a reply
too short to fill it. **IN FORCE.**

**G2 — the witnesses.** The constants and behaviours a later change is most
likely to break, asserted directly: a start threshold of a quarter-second or less,
a drain bounded by the audio owed, a mood marker that never reaches `tts-speak`, a
dated log line. Cheap, and the failure message names the defect. **IN FORCE.**

**G3 — the device smoke.** `scripts/regression-smoke.sh` for the half adb can
decide, then the twelve manual paths in `docs/REGRESSION.md`, with the evidence
line recorded in the release document. Ten minutes with a phone. Proves the
release works for a person. **IN FORCE as a document, and it has been walked
once.** It is not yet a gate until a cut has been blocked by it.

**G4 — the human run.** At least one person installs the candidate and **uses it in
ordinary course**, and says so: a real session, on a real device, with the paths in
`docs/REGRESSION.md` among what they do, and the reviewer's name and what they did
recorded in the release document. **ADOPTED, 2026-11-11 (D41).**

It **defaults to the owner** and passes to a volunteer the moment one exists.
Anyone who installs a testing release and reports what they saw is a candidate for
the role; the first volunteer who reviews a release takes the gate from the author
permanently, and from then on the author's own run does not count. **The honest
state today:** the reviewer and the cutter are the same person, which is a real
run but not an independent one — strictly weaker than a second pair of eyes, and
the release document says which it was rather than letting a tick imply the
stronger thing.

**G5 — the evidence rule.** No claim in a release note that was not measured on
that build or confirmed by the person who reported the issue; and no number
without the file it came from, the rule that counted it, and the script that
reproduces it. **ADOPTED, 2026-11-11 (D44), as a gate that blocks a tag.** The 0.9.1 cleanup
note quoted three different counts for the same defect from the same log; the
review found the third one does not reproduce. A rule that catches prose and
misses arithmetic would not have caught it.

**G6 — the record.** The cut tracker (`#548`-style) is closed before the tag, and
the release page links it. An issue the release fixes is closed by the release,
with the evidence line. **ADOPTED, 2026-11-11 (D44), as a gate that blocks a
tag.** At the 0.9.1.3 tag, four items in the
tracker's own gate list were unticked, two of them already done, one of them the
only record of a device proof.

**A rule about the gates themselves.** A gate that fails for a reason unrelated
to the change is worse than no gate, because it teaches everyone to re-run until
green. `#565` is the precedent: a reconnect asserted at 199 ms against a 200 ms
minimum. A flaky assertion is a defect and is fixed like one. **RECOMMENDED.**

**No tag with an unexplained red.** Any red is fixed, or deferred in writing with
a reason and a linked issue. **IN FORCE** in `docs/REGRESSION.md`.

## 5. The veto

The owner retains a quality veto over any release. It is worth having only if
everyone can see what it means, so it gets a criterion, a procedure, and a
consequence.

**Grounds — the complete list.** The veto may be exercised when one of these is
true:

1. a gate is red, or unexplained;
2. a claim in the release note is not measured on that build (§G5);
3. a defect reported against the RC has a reproduction and is unresolved;
4. **the owner used the RC and it is not ready.** He cannot be required to name a
   gate for this one. Every serious defect in this project so far was found by a
   person using the thing, and a criterion that excludes the only instrument that
   has ever worked would be an expensive kind of tidiness.

Outside those four, a release date may still move — but for capacity, and it is
called that, not a veto.

**Procedure.** The ground is written into the cut tracker when the veto is used.
Within **24 hours** the reason is published where the release would have been
announced. Within **7 days** a new date is set.

**Consequence.** The build is not tagged. The fix ships as its own cut, by the
normal route. **The schedule moves; the gate does not.** A veto never lowers a
gate, and it can never be used to ship something a gate refused.

**Lapse.** A veto with no ground recorded in 24 hours is withdrawn and the cut
proceeds. This is the clause that keeps the veto a standard rather than weather,
and it is the reason a published criterion is worth more to the owner than an
unlimited one.

**Limit.** The veto is on the release, never on a person. The critique is of the
plan, and the record says so in those words.

**ADOPTED, 2026-11-11 (D43):** grounds 1–4 as the list, 24 hours to record the
ground, 7 days to set a new date, and an unrecorded veto lapses. The 24-hour clock
was the owner's, taken against Torc's offer of 48.

## 6. How consensus on a major is expressed

**What counts as a major.** A version that changes a default people rely on,
removes a capability, changes the licence or the CLA, or opens the Alpha gate for
a feature by default. Not "a big release"; a release that changes what someone
already depends on.

**The four conditions.**

1. A design note published at least **14 days** before the release candidate is
   cut.
2. A consensus call open at least **7 days** in the community space, with the
   design note as its only subject.
3. **No sustained objection that names a defect or a broken promise.** Consensus
   is not unanimity and it is not a vote: it is the absence of an unanswered
   technical objection after the window closes. Silence is consent, and the
   window is announced in advance so that silence is a choice.
4. The outcome is recorded in `docs/DECISIONS.md` as the next D-number, **with the
   dissent and why it did not prevail.**

**Who decides.** The maintainer decides, having published the argument. This is
not a compromise between consensus and authority — it is authority made
answerable, which is the only version of it that survives contact with a
community.

**What consensus cannot do.** It cannot amend the licence or the CLA. It cannot
overrule the evidence rule. It cannot be bought: a membership, a sponsorship or a
bounty never carries a vote, or the consensus becomes pay-to-play. **ADOPTED,
2026-11-11 (D45), including the clause that money never buys a vote** — the one to
keep if only one is kept.

## 7. Amendments

- The maintainer amends with a published reason, recorded as the next D-number.
- Anyone may propose an amendment in the community space. A proposal with no
  objection in 14 days is adopted; one with an objection is answered in writing.
- **The veto (§5) and the evidence rule (§4, G5) need 14 days' notice before they
  are amended.** They are the two clauses that protect people who are not in the
  room: the person who installs the APK, and the person who reports the bug.
- If this document stops matching practice, it is corrected to match practice or
  practice is corrected to match it. A governance document that describes a
  process nobody follows is worse than none, because it launders the actual
  process.

## 8. What this document does not do

- It does not change the licence or the CLA. There is no relicensing.
- It does not create an entity, a board, a membership class or a treasury.
- It does not make commercial activity a condition of anything. Work inside
  GPL-3.0 — support, integration, training, hosting and operations, a separate
  fleet console, marks and partnership, memberships, bounties, grants — is not
  governed by this document and cannot change a release decision.

## What is adopted, and what is still open

**Adopted 2026-11-11**, and recorded as D41–D46 in `docs/DECISIONS.md`:

1. **The human run** replaces a soak duration: one person uses the candidate and
   says what they saw. It defaults to the owner until a volunteer takes the role
   (G4, D41, amended the same day).
2. **No calendar.** A release ships when its gates are green (D42).
3. **The veto:** four grounds, a 24-hour clock to record the ground, a new date
   within 7 days, and an unrecorded veto lapses (D43).
4. **The evidence rule and the closed tracker block a tag**, and a number that
   cannot be reproduced blocks it rather than being corrected afterwards (D44).
5. **Consensus on a major:** 14 days' design note, 7 days' call, no unanswered
   objection naming a defect, silence as consent, dissent recorded, and money never
   buys a vote (D45).
6. **The community** is Discord, with no bridge and no migration plan; the platform
   question reopens only if someone brings a compelling alternative
   (`docs/COMMUNITY.md`, D46).

**Still open, and marked as such in the clauses above:**

- **G0, the scope question** — the one gate that addresses the first failure in the
  review, and the only one still RECOMMENDED through no fault of its own: it was
  not among the six.
- **Contributor ownership and community voice** (§2) — proposed, not voted on.
- **The flaky-assertion rule** (§4) — proposed.
- **The code of conduct, the moderator, and the name** — `docs/COMMUNITY.md`.
