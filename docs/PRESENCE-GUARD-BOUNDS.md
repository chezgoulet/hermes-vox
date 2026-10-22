# Presence guard bounds — measured, then split into content and shape

The working lane's guard is [ErSoulTurn.workingLine][wl]/[workingReject][wr]. This document began as
a measurement of what that guard refused over a plausible corpus; **D20** then changed the split.
It is kept because D20 requires every remaining bound to name the failure it answers.

**Before D20 (measured 2026-10-20): the guard policed formatting.** 25 of 51 renders were refused,
and 10 of those 25 (40%) were `too-many-words` — every 8-to-11-word line. An eleven-word line is
not a lie, so D20 moved shape into repair: an over-long line is cut at a clause end and spoken,
markdown is stripped, a fence/title/courtesy is removed, and a number the client wrote into the
directive is grounded. What remains refused is content.

## The corpus and what it measures now

The corpus is unchanged: 51 renders a small model plausibly returns for the working directive, plus
the ways it overshoots. `PresenceGuardBoundsTest` pins the counts.

| Verdict | Before D20 | After D20 |
|---|---|---|
| spoke | 26 | **27** |
| `too-many-words` | 10 | **9** |
| `digits-unvouched` | 4 | 4 |
| `digits-or-markup` | 2 | 2 |
| `not-spoken` | 3 | 3 |
| `empty` | 3 | 3 |
| `repeat` | 2 | 2 |
| `too-long` | 1 | 1 |
| **total** | **51** | **51** |

The one line that moved is *"Just a quick update, I'm still checking everything for you"*: its
comma is inside the budget, so it is spoken as *"Just a quick update…"*. The other over-long corpus
lines have no clause end inside the budget, so D20 drops them rather than speaking a fragment —
that is the decision, and `PresenceRepairTest` pins both directions.

Bound order (first match wins), after D20: `not-spoken`/`empty` → `digits-unvouched` →
`digits-or-markup` → `result-unbacked` → `too-many-words` (over budget, no clause end) → `too-long`
(over the character backstop after the cut) → `repeat`. Content now precedes shape: a line that
invents a number is refused for the invention even when it is also too long. One test pins it.

## What D20 repairs, and what it still refuses

| Render | Before | After |
|---|---|---|
| more words than the budget, with a clause end inside it | refused `too-many-words` | **cut there and spoken** |
| more words, no clause end inside the budget | refused `too-many-words` | **dropped**, same reason (never a mid-phrase fragment) |
| markdown emphasis / bullets / backticks | spoken **with markers** | **stripped**, then spoken |
| a fence, a title line, a trailing courtesy | spoken with the wrapper | **removed**, then spoken |
| a number the directive carried | refused (except #218's wait) | **spoken** |
| a number nobody supplied | refused `digits-unvouched` | refused `digits-unvouched`, unchanged |
| a claimed result ("I've booked it") | **spoken** — there was no check | **refused `result-unbacked`** |

`result-unbacked` is D20's one new token. Every other token keeps its name, including
`digits-or-markup`, which now covers only bracket markup (`< > [ ]`) because emphasis is repaired.

## Every bound, and what it cites

D20's rule: a bound names the recorded failure it answers, or it is labelled a hypothesis with the
condition that would retire it. Where there is no field record, this says so instead of inventing
one.

| Bound | Citation | Status |
|---|---|---|
| `MAX_OPENER_WORDS = 7` | No field failure recorded. Value comes from the handover margin (a line must finish before the mind's first token); this corpus only shows what it *would* cut. | **Hypothesis.** Retire the exact value when a session shows a 7-word cut colliding with the mind's first text, or when the bench (#256) measures a longer budget as safe. |
| `MAX_OPENER_CHARS = 60` | No field failure recorded. One corpus line (long words) is un-speakable with fewer than seven words. | **Hypothesis.** Retire when a field session set logs no `too-long`, or when the voice's duration rather than its characters is what gets bounded. |
| bracket markup (`< > [ ]`) | No field failure recorded. Model output plausibly wraps lines in `[thinking]`/`<aside>`, and this corpus refuses both. | **Hypothesis.** Retire when a field session set logs none, or once the render prompt stops producing them. |
| `digits-unvouched` | #218 and D20: a number the model invents is a fact it cannot know; a number the *client wrote into the directive* is grounded. Measured: the four digit corpus lines. | Kept, narrowed: "no new numbers". |
| `result-unbacked` | D20: claiming a result that does not exist has an asymmetric cost. The old code had no check; the table above records that defect. | New. Content, never repaired. |
| `repeat` | 09-10 field session: one sentence spoken seven times in thirty-nine seconds. | Kept, documented field failure. |

The two earlier "what looks wrong" findings are now answered rather than carried: the markdown set
gap is closed by stripping (`*`, `_`, backticks and bullets never reach the voice), and the
directive/guard mismatch is gone because the directive states no word cap — the five rules ask for
"one line, no longer than a breath", and rule four's bound is a cut in code.

## #218 — the number the client can vouch for, generalised

D20 turns #218's single voucher into a rule: **any number the client wrote into the directive this
run is grounded** (`ErSoulTurn.vouchedNumbers(directive)`), and a number that appears nowhere in the
directive is refused as `digits-unvouched`.

- **Two numbers are still refused**, even when both are grounded: a line doing arithmetic is the
  model guessing, not the client measuring (#218).
- **The comparison is the digit run as written**: "090" is not "90", and "1:30" is two numbers.
- **The beat is untouched.** `parse()` composes a beat through `opener` with no voucher, so a number
  in a beat still costs the beat; the turn/beat lane is frozen.
- **Spelled-out numbers are not policed** and never were: the rule is digit-shaped. A lexicon would
  refuse legitimate lines the corpus pins ("One second", "Just a moment"). The honest limit is that
  a spelled number is not checked against the directive.

[wl]: ../android/app/src/main/java/com/hermesvox/ErSoulTurn.kt
[wr]: ../android/app/src/main/java/com/hermesvox/ErSoulTurn.kt
