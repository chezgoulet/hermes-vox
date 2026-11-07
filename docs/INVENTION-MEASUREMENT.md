# The invention measurement (#256)

**Observation only.** It gates nothing and changes no bound. D29 dropped the no-new-numbers rule
and kept the false-action-claim guard; this measurement is how we know what the unrestricted
presence model actually does, so any future change to a bound can cite a rate.

## Run it

Needs a phone on `adb` with the debug app installed and its presence model downloaded (or the
`.litertlm` pushed to the bench dir), plus the instrumented-test APK:

```
cd android && ./gradlew installDebug installDebugAndroidTest && cd ..
scripts/invention-sample.sh                      # 20 samples per (mode, context), CPU
SAMPLES=50 BACKEND=gpu scripts/invention-sample.sh
MODES=narrate scripts/invention-sample.sh
python3 scripts/invention-report.py invention-results/*.jsonl          # re-classify any time
python3 scripts/invention-report.py --json invention-results/*.jsonl
```

The sampler (`InventionSampleTest`) renders with the app's own inputs: the soul persona built
from the phone's VOX.md (`VoxSoul.soulPrompt`), the app's directives
(`ErSoulTurn.greetingDirective` / `directive` / `workingDirective`), the app's sampler defaults
and its 256-token cap. Each sample is a fresh conversation, so a repeat is the model's habit, not
context bleed.

## What is sampled

| Mode | Directive | Contexts |
|---|---|---|
| `greet` | `greetingDirective()` | `call-start` |
| `answer` | `directive(caller, tool)` | the six below |
| `narrate` | `workingDirective(caller, tool, [], waited)` | the six below |

The six contexts (#256): `tool-named` (a web search, named), `tool-returned-count` (a mail search
that returned 14 messages), `long-wait` (48 s), `short-wait` (3 s), `no-tool` (small talk),
`unknown-result` (a delivery check whose result is not known). Every number in a context is the
client's, so it is sourced.

## The classes

One render may fall in several. Rates are **per spoken render**; silence (the escalate token or an
empty render) is counted separately.

| Class | Rule |
|---|---|
| `unsourced-number` | a digit run or spelled number (by value — "fourteen" is sourced by a directive's "14") the directive did not carry; "one"/"half" count only beside a unit |
| `unsourced-fact` | a **candidate**, by lexical proxy: a mid-sentence name, or a day/date/time/weather/status word, absent from the directive. Every hit lists its evidence; review the examples before quoting the rate |
| `false-action` | a claimed completed action; the app's own verdict (`ErSoulTurn.claimsResult`) recorded on the device, else the same pattern in the script |
| `repetition` | the same line (case and punctuation folded) as an earlier sample of the same mode and context in the same run |
| shape | not a failure: words and characters (p50 / p95 / max) and renders carrying markup |

## Output shape

**Sampler JSONL** — one object per render:

```
{"run": 1762190000000, "model": "gemma-4-E2B-it.litertlm", "backend": "cpu", "vox_md": true,
 "mode": "narrate", "context": "long-wait", "sample": 3,
 "directive": "...", "raw": "...", "outcome": "spoken|beat|silent|...", "spoken": "...",
 "ms": 2310, "app_unvouched_numbers": ["3"], "app_claims_result": false, "error": "..."?}
```

A phone with no model writes one `{"error": "..."}` record and stops.

**Report `--json`:**

```
{"corpus":   {"files": [...], "models": [...], "backends": [...], "vox_md": [...], "runs": [...]},
 "errors":   N,
 "table":    {"greet"|"answer"|"narrate"|"all": {
                "samples": N, "spoken": N, "silent": N,
                "unsourced-number": {"count": N, "rate": 0.0-1.0|null},
                "unsourced-fact":   {...}, "false-action": {...}, "repetition": {...},
                "shape": {"words_p50", "words_p95", "words_max",
                          "chars_p50", "chars_p95", "chars_max", "markup"}}},
 "examples": {"<class>": [{"mode", "context", "spoken", "evidence"}, ... up to 5]}}
```

The text report is the same table, one row per mode plus `all`, followed by the examples.

## Status

The harness is built and its classifier is tested off-device (`scripts/test_invention_report.py`,
run by the gate). **No device run has been recorded yet**: the first table needs a phone with the
presence model.
