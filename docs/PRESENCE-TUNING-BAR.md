# The Presence tuning bar (#217)

An instrument and a bar for presence quality. The instrument is
`scripts/presence-session-report.py`; it reads any logcat and needs no device or build.

```
python3 scripts/presence-session-report.py logcat.txt          # the report
python3 scripts/presence-session-report.py --json logcat.txt   # the same, for machines
python3 scripts/presence-session-report.py --check logcat.txt  # exit 1 if a measured row fails
```

## The bar

Each row says **what it measures**, **which log events it counts**, the **threshold**, the
**value** and the **sample size** (`n`). A row with nothing to measure is `unknown` — never a
pass — and `--check` passes only when every measured row passes and at least one row measured.

| Row | Measures | Events | Threshold |
|---|---|---|---|
| `speak-rate` | spoken ÷ offered working slots, Conversational runs only | `er-presence line` / `er-presence skip` | ≥ 2/3 |
| `late` | turn renders that arrived after the reply and were dropped | `er-soul-drop reason=late` | = 0 |
| `repeats` | a spoken line heard twice in one run, compared by line hash | `er-presence line … h=` | = 0 |
| `render-p95` | render latency, nearest-rank p95 (the slowest of three is the p95 of three) | `er-soul … ms=` | < 3000 ms |
| `boundary` | working renders that **started** less than 700 ms after the caller stopped speaking — the D18 window, where an aside spends the reply's latency budget | `er-soul kind=working … boundaryMs=` | = 0 |

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
Added: `repeats`, `working_boundary_ms` (the samples), and `bar` — a list of rows, each
`{name, measures, source, threshold, value, n, verdict}` with `verdict` `true`/`false`/`null`.

## Tuning against it

The order of work in #217 stands: make the lane land lines, then judge the guard's bounds
(`docs/PRESENCE-GUARD-BOUNDS.md`), then the directive's wording, the cadence registers and the
mood mapping — each on one long Conversational run. The bar is the release's measure of
presence; it gates nothing by itself.
