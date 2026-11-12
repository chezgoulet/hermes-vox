# Community — the space, the rules, the first month

**Status: adopted 2026-11-11 (D46) — Discord first.** The platform is decided. The
code of conduct, the moderator and the server name are still open, and are marked
where they sit.

## Why a space at all

GitHub is where the work is recorded; it is a poor place to be helped. The 0.9.1
field report (`#538`) took two days and four exchanges because an issue thread
cannot ask a question and get an answer in a minute. A real-time room would not
have prevented the defect. It would have shortened the time between "this is
broken" and "here is what is broken", which in that case was the whole cost.

There is a second reason, and it is the owner's: a project that has users owes
them somewhere to stand. An issue tracker is a queue. A room is a place.

## The platform, and the cost the project now carries

**Discord.** Chosen on 2026-11-11, against a Matrix server on House infrastructure
(Torc's recommendation) and against Mattermost, which is already running. **There is
no bridge and no migration plan:** the owner's words were that he does not like
Matrix and will stay on Discord until, and unless, somebody brings a compelling
other. So the platform question is closed, and it reopens by someone making a case
to him — not by a standing option in a document.

The trade, written down so nobody discovers it later:

- **The community lives somewhere the project does not own.** Discord can change its
  terms, its client and its API; the project cannot fork the server, and a member
  cannot take their history with them. A federated, self-hosted room could have been
  forked, which was the argument for it, and that argument lost to reach and to the
  owner's own preference.
- **The record is split.** Issues stay on GitHub, conversation lives on Discord,
  Discord is not searchable from outside, and its history is not in the repository.
  The rule that follows is in `#field-reports` below: a report that turns into work
  is mirrored to an issue by whoever triages it, so the durable record stays
  somewhere the project controls.
- **Nothing to run, and that is the real gain.** No homeserver to patch, no
  registration policy to defend, no backup to own. For one maintainer that is not a
  small thing, and it is why the reach argument wins.
- **If the server ever moves, the export and the new address come first.** Not a
  plan and not a promise — a rule for the day it happens: nobody is asked to move
  into an empty room.

The name is still **"Sorcelot Community"** pending a domain, organisation and
trademark check. If that check fails, the server is renamed before any invite goes
out.

## The channels

Seven to start. A channel with no traffic is worse than no channel.

- **`#announcements`** — releases only. Read-only except for maintainers. The
  channel a user joins and mutes.
- **`#support`** — questions and help. The channel that justifies the whole thing.
- **`#field-reports`** — bugs with logs attached. A pinned template: device,
  Android version, Vox version, how it was installed, what you did, what happened,
  and the exported log. **Every report that turns into real work is mirrored to a
  GitHub issue by whoever triages it.** That mirror is what keeps the record
  somewhere the project controls, and it is the standing answer to choosing a
  platform the project does not own.
- **`#development`** — work in progress, and the design note that opens a consensus
  call on a major (`docs/GOVERNANCE.md` §6).
- **`#governance`** — `docs/GOVERNANCE.md`, its amendments, and the argument visible.
- **`#voice-and-hearing`** — the translation and speech threads, which are the
  busiest part of this project that is not code: PierreCsn's three contributions
  started in one.
- **`#off-topic`** — because otherwise the other six become off-topic.

## Moderation

**Who.** The owner, plus one moderator he appoints and can remove. Two is enough for
twenty people and is the right number for two hundred; more is a bureaucracy with no
work. **The moderator is still to be named.** A moderator's authority is over
conduct, never over technical opinion — holding the ban button does not make you
right about the code.

**The tools, honestly.** Discord gives automod, roles, an audit log and raid
defence; the project gives the rules. Roles: `moderator`, `contributor` (a merged
pull request), `reporter` (a field report that led to a fix), `member`. In the first
month the invites are single-use and `#announcements` is the only channel a raid
would be worth caring about.

**The ladder.** Nothing here is novel; the point is that it is written down.

1. A quiet word, in the channel or by direct message.
2. A public note naming the behaviour, not the person.
3. A temporary mute, with its length and its reason stated.
4. Removal from the server, by the owner, with the reason recorded in the mod log.
5. A ban, which is the only step that needs the owner alone.

**Appeal.** To the owner, in writing, and answered in writing. His decision is final
but not secret.

**What moderators may not do.** Edit or delete a technical disagreement; remove a
field report because it is inconvenient; act on a report about themselves; delete
the mod log.

**What the project can still do about exit.** It cannot offer a fork of the server.
It can keep every decision, issue and release note in the repository, keep the
`#field-reports` mirror, and — if the community ever moves — run the export and
stand the new place up *before* announcing the address rather than after.

## The code of conduct

Short enough to read, specific enough to enforce. **Still open:** adopt as written,
or amend in the owner's words.

**Expected.** Argue about the code, not the person. Assume a report is honest until
it is shown otherwise — the 0.9.1 report was right, and cost two days because it was
read as user error. Say what you want to happen, not only what is wrong. Credit the
person who found it.

**Not acceptable.** Harassment, including sustained disruption of a thread after
being asked to stop. Personal attacks, including about competence. Bigotry of any
kind, which is not a debate this project will host. Publishing anyone's private
information. Sexual content or imagery. Using the server to promote a fork as the
official one, or the marks without permission. Anything the platform's own terms
forbid — Discord's minimum age is thirteen, and the project will not host what the
platform will not.

**Not a conduct matter.** Disagreeing with the maintainer. Saying a release was bad.
Reporting the same defect three times because it was not fixed. Being wrong in
public about how the code works.

**Reporting.** A direct message to a moderator, or a private report to the address
in `SECURITY.md` if the matter is a security one. Reports are confidential; the
person reported is told what they are alleged to have done, because a rule enforced
in secret is not a rule.

**Consequences.** The ladder above. The project will not litigate intent; it will
respond to effect, and it will say which it is doing.

## The first month

**Mode: invite-only, and quiet.** A dozen single-use invites:

- the contributors who have shipped code — PierreCsn, whose three contributions
  shaped 0.9.1;
- the reporters who found real defects — blueorignal, whose log found the worst one
  in the release;
- anyone else who has opened a substantive issue in the last sixty days.

**Why invite-only first.** A code of conduct and a moderation ladder are theories
until a server has had an argument in it. A month with a dozen people is enough to
learn whether the rules survive contact, and small enough that a mistake is not
public. On Discord it also keeps the invite graph small while the moderation habits
are still forming.

**Open at day 30** if, and only if: at least one question was answered by someone who
is not the owner, and no moderation incident is unresolved. Otherwise the month
repeats, which is cheaper than opening a server that has to be closed.

**What is measured, not promised.** Messages per week, unique posters, unanswered
questions older than 48 hours, and field reports that arrived in the server before
they arrived on GitHub. Four numbers, one page, published with the release notes.

## The name

**"Sorcelot Community"** was proposed by the owner and is not yet checked: it needs a
domain, an organisation and a trademark search before it appears in public copy, and
the umbrella is meant to cover more than this project. Do not publish it until that
check is done — on Discord the server name is public the moment the first invite is
accepted. No alternative is proposed here; the name is the owner's.

## What this costs

Nothing to run, and something owed: the record is split between GitHub and a platform
the project does not control, and the answer to that is discipline in the
`#field-reports` mirror rather than infrastructure. Keeping the record is the
project's job; keeping the platform is not. That trade was made knowingly on
2026-11-11 and is written here so a later reader knows it was a choice.

## What is still open

1. **The moderator** — one named person beside the owner.
2. **The code of conduct** — adopted as written, or amended.
3. **The name** — Sorcelot Community after the check, or still open.
4. **Whether "open at day 30" is a rule or a target.**
