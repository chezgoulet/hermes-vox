# Presence guard bounds — measured

The working lane's guard is [ErSoulTurn.workingReject][wr]. This is what it actually refuses,
measured over a plausible corpus, not recalled from the bounds. Pinned by
`PresenceGuardBoundsTest`. **No bound is changed here** — the field has logged 0 guard refusals,
so there is nothing yet to fix.

## The corpus

51 renders a small model plausibly returns for the working directive: what it asks for (one
short line, under seven words, no digits, no markup), plus the ways a model overshoots — a
reassuring sentence one or two words too long, long words, a number, markup, markdown emphasis,
a line already said, and silence in its forms. Full list in the test.

## Which bound refuses first

| Verdict | Count |
|---|---|
| spoke (passed every bound) | 26 |
| `too-many-words` | 10 |
| `digits-or-markup` | 6 |
| `not-spoken` | 3 |
| `empty` | 3 |
| `repeat` | 2 |
| `too-long` | 1 |
| **total** | **51** |

Refused: 25. Of those, **10 of 25 (40%) are `too-many-words`** — every 8-to-11-word line in
the corpus. The four digit lines and the two markup lines are `digits-or-markup`; the rest are
silence or the same line twice.

Bound order (first match wins): `not-spoken`/`empty` → `too-long` (>60 chars) → `digits-or-markup`
→ `too-many-words` (>7) → `repeat`. A line that breaks several at once reports the earliest;
that order is pinned by `the_order_of_the_bounds_is_length_then_content_then_count_then_repeat`.

## What looks wrong for the lines this feature wants

1. **`MAX_OPENER_WORDS = 7`** (`ErSoulTurn.kt:192`) is the one that refuses real lines. A calm
   line about working is naturally 8–10 words — *"I'm just checking on that for you now"*,
   *"I am still reading through the logs for you"*. The directive asks for *"under 7 words"*, so
   the model is told to be terse and still overshoots by one or two. **Exact change I would
   make: `MAX_OPENER_WORDS` 7 → 10.** Risk: the constant is shared with `opener()`, which also
   bounds the *beat* on every mind turn (`ErSoulTurn.kt:238`), so widening it widens beats the
   field already tuned; and a 10-word line is ~2.5–3 s of speech, which can collide with the
   mind's first token and be cut mid-clause — exactly the handover the 7-word margin protects.
   Not applied.
2. **The markup set is incomplete, and this is the defect I would fix first if a line were
   refused for it.** `opener` rejects only `< > [ ]` and digits (`ErSoulTurn.kt:205`), so
   markdown emphasis is *spoken with its markers*: `**Checking** on it` passes the guard and
   reaches the voice as `Checking** on it`. That is a character-set gap, not a constant — the
   fix is to add `*` and `_` (and backtick) to the markup test and to the trim set. Low risk,
   but still a behaviour change, so it waits for evidence too.
3. **`MAX_OPENER_CHARS = 60`** (`ErSoulTurn.kt:193`) refused 1 of 51 and is nearly dead at a
   7-word limit (7 words average well under 60 characters); it only bites on long words. I would
   **not** change it: it is a backstop for the word bound, and lowering or raising it would not
   move any line in this corpus except the one that is genuinely un-speakable.
4. **The directive is stricter than the guard**, which is the wrong way round: it says *"under 7
   words"* (≤ 6) while the guard accepts 7. If the margin is meant to be 7, the directive should
   say so; if 6, the guard is one word too generous. Text/constant pair, not applied.

## Why nothing is applied

The field logged **0** guard refusals in six sessions. Either no working slot was ever refused
by the guard, or the build behind those logs predated the `event=er-presence detail` line (it is
the newest change in this tree). Either way there is no field evidence of a good line being
discarded: the one working slot that did not speak was skipped as `silent`, which is the soul
declining (or a blank render), not the guard refusing a line.

A corpus is a hypothesis. The numbers above say which bound *would* refuse, not that it ever
did. The change waits on a field session with real `why=` values from `event=er-presence detail`.

[wr]: ../android/app/src/main/java/com/hermesvox/ErSoulTurn.kt
