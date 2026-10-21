# Plan — 1.0, the horizon

1.0 is the last thing in the sequence and it carries nothing on its own. It is not a bucket of ideas
that were waiting for a number; it is the point at which the app is finished enough to call general
release, and the only way to know that is to have been in people's hands long enough to learn it
(D22, amending D21).

**Therefore:** no issue carries `milestone/1.0`. The candidate work — continuity, the household,
entity calls, remote control, the wake word — is unnumbered in
[`PLAN-FUTURE.md`](PLAN-FUTURE.md), and the community decides which of it matters (#268). New
features and workflows proposed by people using the app belong on that same list, not on a calendar.

## What has to be true

- **Complete** — every item the community and the maintainer agree is essential has shipped, and
  nothing user-facing is described as coming next.
- **Stable** — a release cycle passes in the field with no severe regression that reaches users; the
  bug list is triaged rather than carried.
- **Wanted** — the answers in #268 are reflected in what was prioritised. If the direction and the
  answers disagree, the answers win, and the disagreement is written down.

## What 1.0 is not gated on

**F-Droid.** The store rail is independent of the feature set: it can ship with any release whose
recipe is ready, and it is running alongside 0.9.1 now (#165). A store listing is distribution, not a
statement about completeness, and holding it behind 1.0 would delay it for no reason.

**A date.** There is none, and naming one would convert a judgement into a promise.

## Cutting it

The same process as every other release — bump on `testing` first, notes, banner, merge to `main`,
tag, reproducible build, release, site dispatch — plus a re-run of the whole gate set on the
candidate: reproducible build, live gateway suite, behaviour matrix on both devices, performance
baseline, and no open severe bug without a written reason. `docs/RELEASE-PROCESS.md` carries the
mechanics; D15 carries the F-Droid evidence requirement if the rail is live by then.
