# The Presence tuning bar (#217)

An instrument and a bar for presence quality. The instrument is
`scripts/presence-session-report.py`; it reads any logcat and needs no device or build.

```
python3 scripts/presence-session-report.py logcat.txt          # the report
python3 scripts/presence-session-report.py --json logcat.txt   # the same, for machines
python3 scripts/presence-session-report.py --bar logcat.txt    # just the verdict
python3 scripts/presence-session-report.py --check logcat.txt  # exit 1 if a measured row fails
```

`--bar` prints the five criteria only — verdict, measured number and `n` on each line — and exits 0
when every measured row passes, 1 when a measured row fails, and 2 when nothing was measured.
`--check` is the same verdict without the rows.

## The bar

Each row says **what it measures**, **which log events it counts**, the **threshold**, the
**value** and the **sample size** (`n`). A row with nothing to measure says **no data** — never
a pass — and the verdict fails when nothing was measured.

| Row | Measures | Events | Threshold |
|---|---|---|---|
| `speak-rate` | spoken ÷ offered working slots, Conversational runs only | `er-presence line` / `er-presence skip` | ≥ 2/3 |
| `late` | lines that arrived after the reply's first text: turn renders dropped as late **and** working slots skipped as late | `er-soul-drop reason=late`, `er-presence skip reason=late` | = 0 |
| `repeats` | a spoken line heard twice in one **session**, compared by line hash | `er-presence line … h=` | = 0 |
| `render-p95` | render latency, nearest-rank p95 (the slowest of three is the p95 of three) | `er-soul … ms=` | < 3000 ms |
| `boundary` | working renders that **started** less than 700 ms after the caller stopped speaking — the D18 window, where an aside spends the reply's latency budget | `er-soul kind=working … boundaryMs=` | = 0 |

A **session** is one conversation. `er-presence stop reason=call-end` closes one; every other
stop (`reply`, `cap`, `barge`, `hush`) closes a turn inside it, and a second log file is a
second conversation. Repeats are counted against that boundary: the same line in two turns of
one call repeats, the same line in the next call does not.

```
bar — pass, fail or no data per criterion, with the measured number and n
  pass    speak-rate     71%  bar >= 2/3    n=7    spoken / offered working slots, Conversational runs  [er-presence line / skip]
  pass    late             0  bar == 0      n=10   lines that arrived after the reply's first text (turn drops + working skips)  [er-soul-drop reason=late / er-presence skip reason=late]
  fail    repeats          1  bar == 0      n=5    a spoken line heard twice in one session (by line hash)  [er-presence line … h=]
  pass    render-p95 1500 ms  bar < 3000 ms n=3    render latency, nearest-rank p95  [er-soul … ms=]
  pass    boundary         0  bar == 0      n=3    working renders started < 700 ms after the caller stopped (D18)  [er-soul kind=working … boundaryMs=]
verdict: fail — 5 of 5 criteria measured, 1 failed, 0 with no data
```

The per-session summary above it carries the same numbers plus what the bar does not judge:
slots offered, lines spoken, skips by reason (a skip with no reason is counted as `unknown`,
never dropped), repeats with the hashed-line count, the late split between turn drops and
working skips, render p50/p95 and the guard's refusal reasons.


## What the app logs for it

- `event=er-presence line=N … kind=K h=HASH` — `h` is eight hex digits of a CRC32 over the line,
  lowercased with punctuation folded (`lineHash` in `ErPresence.kt`). A repeat is measurable
  without the line's text, which stays behind *Settings → Log transcripts*.
- `event=er-soul kind=K … ms=N boundaryMs=N` — `ms` is the render's duration, timed at the
  `expressAsync` call site; `boundaryMs` is the time from the caller's last speech end to the
  render's start (`VoiceController.msSinceSpeechEnd`), `-1` before the first turn of a call.
  Builds before this change log neither; the rows say `unknown`.

## `--json` shape

The first version's keys are unchanged (`runs`, `slots`, `spoken`, `rate`, `skips`, `late_drops`,
`render_ms_p50`, `render_ms_p95`, `checks.rate`, `checks.late`, `checks.p95`, `passes_bar`, …).
Added: `sessions`, `repeats` (per session), `hashed_lines`, `late_working`, `late_total`,
`working_boundary_ms` (the samples), and `bar` — a list of rows, each
`{name, measures, source, threshold, value, n, verdict}` with `verdict` `true`/`false`/`null`.
`--bar --json` prints only `{bar, passes_bar}`.

`testdata/presence-session.txt` is the rehearsal fixture: a synthetic two-call log with known
counts, used by `scripts/test_presence_session_report.py`. It is not a field reading.


## Tuning against it

The order of work in #217 stands: make the lane land lines, then judge the guard's bounds
(`docs/PRESENCE-GUARD-BOUNDS.md`), then the directive's wording, the cadence registers and the
mood mapping — each on one long Conversational run. The bar is the release's measure of
presence; it gates nothing by itself.
