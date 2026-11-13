# Presence reports (#417)

A report is worth reading only if the configuration travels with it. `Settings → Presence →
Advanced → Copy diagnostic summary` and the `/diagnostics` command put one fenced block on the
clipboard; the issue template asks for it, and `scripts/presence-reports.py` parses it. Prose
around the block is welcome; the block is what gets counted.

## Two entry points, one block

The block is built in one place (`PresenceDiagnostic.block`, filled in by
`PresenceDiagnosticCollect.collect`) and put on the clipboard by one function
(`PresenceDiagnosticCollect.copy`). Both entry points call that function with the same prefs, so
the text is byte-identical — the aggregator parses one shape, not two.

| Entry point | Where | What it says |
|---|---|---|
| Settings action | `Settings → Presence → Advanced → Copy diagnostic summary` | a toast: *"Diagnostic summary copied — safe to paste in a public report"* |
| Command | `/diagnostics`, under **The agent** in the commands sheet | one line: *"Diagnostic summary copied"* — no dialog |

`/diagnostics` is listed only while the switch below is on. A command that cannot run is not
listed, the same principle as the permission spine's schema following the grants; the `/help`
card is written from the same gates, so it never teaches a command the sheet would not show.

## The switch

`Settings → About & Diagnostics → Diagnostic summary` is the gate, beside *Log spoken transcript
(privacy)* and *Verbose file log (debug)*, stored as `debug_diag_summary`, **off by default**.

Its description is the promise: *"Adds a command that copies a report block for a bug report;
contains no conversations"*. The person turning it on is about to paste the result in public, so
the row says what it does and what it does not carry. Everything below — *What is deliberately
excluded* — is that promise kept.

Nothing turns it on for you: not a first run, not another preference. The tap is the consent.
Restoring *About & Diagnostics* to its defaults writes the shipped value (off) with everything
else in that section, so the command leaves the sheet with the rest of the debug switches.

## The block

```
vox-diag
build: 0.9.1-nightly-abc1234
preset: companion
preset_detail: voice=spoken density=chatty hears=off
device: Google Pixel 7, Android 14
mind: lan
e2_p50_ms: 3120
turns: 24  answered_by_voice: 0  escalated: 6
model: gemma-e2b
```

| Field | What it is |
|---|---|
| `build` | the app's own `versionName` from the installed package, nightly stamp included |
| `preset` | `companion` when the layer is running and every Presence setting is the shipped default, otherwise `custom` |
| `preset_detail` | only the settings that moved off that default bundle, from closed vocabularies |
| `device` | `Build.MANUFACTURER` + `Build.MODEL`, and `Build.VERSION.RELEASE` |
| `mind` | `local` (loopback), `lan` (private/single-label name), `remote` (including a tailnet) |
| `e2_p50_ms` | caller stops → the first audible word of the mind's answer |
| `turns`, `answered_by_voice`, `escalated` | the session's turn count and the soul's decisions |
| `model` | the on-device Presence model id, or `none` |

The app has no preset selector yet. `companion` is the shipped default bundle; every other
combination is `custom` and names the changed settings. `beat-only`, `voice-smalltalk` and
`voice-max` are the report template's vocabulary: the parser accepts them, and a build that
grows a selector can emit them without changing the format. Nothing is inferred into a name
the app cannot verify.

In 0.9.1 the soul is a companion, not a gatekeeper (D30), so `answered_by_voice` is expected
to be `0`: the counter is still measured, and a non-zero value would be the finding. The
counters are the call's, reset where the app starts a new conversation or call.

### Metrics the block deliberately leaves out

A missing line is a fact; an empty one is a lie, and a made-up zero is worse.

- **`e1_p50_ms` — caller stops → the first audible sound of any kind** (a cue, the Presence
  beat, or the reply). Not emitted today. The app times the *reply's* first audio write
  (`LatencyStats.pushSpeechToAudio`), not a shared seam where cues and soul lines also write,
  so the number would undercount exactly the turns where something other than the reply spoke
  first.
- **`lost`** — turns where the caller spoke and heard neither an answer nor a repair. Not
  emitted: `TurnRepair` knows how to say the line, but nothing counts a turn that ended
  without one. `turns`, `answered_by_voice` and `escalated` are still reported.
- **`cloud`** for `mind`. The app cannot distinguish a self-hosted gateway on a public address
  from a provider without a configuration it does not have, so a public host reads `remote`.
  It is never guessed into `cloud`.

`e2_p50_ms` is real: it is the session's own p50 of the speech-end → first audible reply-word
samples (up to 512, cleared where `LatencyStats.resetSessionTurns()` starts a new call). The
rolling per-turn window in the log is not used — it clears every eighth turn, which is the
wrong population for a report.

## What is deliberately excluded

The block is safe to paste without reading it first. It contains **no**:

- transcript text, prompts, model output or log lines;
- API key, token, relay secret or any `key`/`authorization` value;
- URL, hostname, IP, port, path or query string (only `local`/`lan`/`remote`);
- device serial, Android ID, account, locale, phone number or app-private path;
- free text from the user. `preset_detail` values are mapped through closed sets; an
  unrecognised stored value is reported as `other`, never echoed.

If a field would need any of those to be useful, it is omitted instead.

## Running the sweep

```bash
# the aggregate for the labelled issues; CSV is required
python3 scripts/presence-reports.py --out /tmp/presence.csv

# the same against the checked-in fixture (four synthetic issues, one malformed)
python3 scripts/presence-reports.py --fixture testdata/presence-reports.json --out /tmp/presence.csv

# machines
python3 scripts/presence-reports.py --fixture testdata/presence-reports.json --out /tmp/p.csv --json
```

The summary is grouped by preset and then device, and every row says how many reports stand
behind it. The CSV has one row per group with `reports`, medians (`e2_p50_ms`, `e1_p50_ms`)
and sums with their sample counts; a metric with no samples is blank, never `0`. Malformed or
missing blocks are counted, listed on stdout and written to `<out>.malformed.csv` — a bad
block never removes a report from the count silently.

### Daily

```bash
bash scripts/presence-reports-daily.sh            # sweep, append, digest only on change
bash scripts/presence-reports-daily.sh --dry-run  # decide and print, do not post
```

The script:

1. runs the aggregator and tees its summary;
2. appends the latest aggregate to `rolling.csv`;
3. prints every open report that has no reply from someone other than its author (bots are
   ignored), so the daily acknowledgement is one line to read, not a hunt;
4. posts to #217 **only when the aggregate hash changed** since the last run. A "nothing new"
   message is never posted. `--dry-run` keeps its own hash, so a rehearsal cannot swallow the
   real change.

State lives outside the repo at
`${XDG_STATE_HOME:-$HOME/.local/state}/hermes-vox/presence-reports/`; set
`PRESENCE_REPORTS_STATE` to move it. `PRESENCE_REPORTS_ISSUE` (default `217`) and
`PRESENCE_REPORTS_REPO`/`GH_REPO` choose the digest target.

The parser is tested in `scripts/test_presence_reports.py`, which the gate runs. The fixture
has two Companion reports on one device (so the count and median are real), a Voice max report
on a second device, and one malformed block.

## Reading the numbers

**Reports are self-selected.** The people who write them have a strong opinion, usually a bad
one, and the people who are content rarely write at all. That makes them excellent for finding
defects — a preset that never lands a turn, a device where the first word is late, a block that
always loses a line — and useless for setting defaults. A default comes from our own
measurements; a report tells us what we failed to measure. Do not turn the loudest preset in
this table into the shipped one.
