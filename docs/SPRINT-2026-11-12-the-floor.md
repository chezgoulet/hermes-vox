# Sprint 1 — the floor, and a comparator that can fail (2026-11-12)

The record of sprint 1 of the 0.9.x series: what was asked (`SPRINT-PLAN.md` §Sprint 1), what was
built, and what the two commands actually printed. A bar written after the work is a description;
the bar below is the one from the plan, quoted, with the evidence against each line.

**Goal.** "No less stable than the last release, with the alpha gate off" becomes a command that
exits non-zero, not a sentence.

## The bar, and how each line stands

| Bar | State | Evidence |
|---|---|---|
| 1. `baseline-diff.py` exits non-zero when a stated tolerance is crossed; a self-test feeds it a synthetic regression and requires a failure | **met** | `scripts/test_baseline_diff.py`, in the gate: a 3× slower first audio at n=8 fails on exit 1, a 2-turn run leaves those rows unjudged, a lost row exits 3, and two identical rows exit 0 |
| 2. The gate-off row is taken on a fresh install and fills every row of #209 or says "not in log" | **met, except the fresh-install half** | Two rows on the bench Pixel 7, and neither is gate-off: **the alpha master was ON with Phone Tools selected** (`event=alpha-gate state=on presence=false continuous_listening=false turn_taking=false phone_tools=true`). The label was inferred from the absence of Presence lines and the default voice, and the inference was wrong. The gate-off rows were then taken properly (below): the alpha master switched off, `v0.9.1.4` and `162075d` swapped with `install -r` (same versionCode, data kept), and the label **read** rather than inferred — off Settings on 0.9.1.4, and from `event=alpha-gate state=off` on the candidate. The **fresh-install** half stays unproven: the handset's saved key is not available to an agent, so the install was reused |
| 3. Path 2 and 5 walked | **path 5 pending, path 2 partial** | Path 5 (`full=true` on a long reply) needs a spoken turn — a reply of the length that gets cut into chunks cannot be produced headlessly. Path 2's cold row is recorded on the candidate (`ms=108`) and has no baseline: `ColdFrame` postdates `v0.9.1.4` |
| 4. #579 has its `event=profiles-ask` line, and paths 8 and 9 pass or the unreachable profile is named | **app half met, device half pending** | The probe line, `unreachable=` on the ask line, and the named switcher line are in, with 52 unit tests green on the build host. Paths 8 and 9 need the owner's handset — a card is queued |
| 5. `docs/templates/RELEASE-CUT.md` gains #209 run+diff, the #208 template, F5, F8 | **met** | The cut checklist now carries all four, and §2a is the single-device behaviour matrix |

## The comparator

`scripts/baseline-diff.py`: two `baseline-report.py --json` rows in, one verdict out.

- **0 `BASELINE-DIFF PASS`**, **1 `BASELINE-DIFF REGRESSED`**, **2 `BASELINE-DIFF USAGE`**,
  **3 `BASELINE-DIFF UNKNOWN`**. A 3 is not a green: the baseline measured a row and the
  candidate's log does not report it (D44).
- Tolerances are `max(relative × baseline, absolute floor)`, one row each, each with its reason
  in the script's header — so changing a number is a visible diff, not a memory.
- `baseline-report.py` gained the **ColdFrame cold row** (the first row #209 asks for) and
  `--label`, so a row says which build and configuration produced it.
- The gate now runs both self-tests. A comparator whose failures are never exercised is a table.

Three design decisions were forced by the first real rows, and they are worth stating because
each one refuses to let a number say more than it can:

1. **`stt` is not compared.** It is `now - segStart`, anchored where the microphone opened
   (`docs/BASELINE.md:82`). In a quiet room it is the length of the call. See below — the rows
   measured it.
2. **The conversational latencies are context under five turns a side.** Two replies are one
   model's answer, not a distribution. The latency verdict for a cut is **F8**'s, ten fixed
   utterances in a fixed order, which is why F8 is now a line in the cut checklist.
3. **A dropped turn warns; a failed turn fails.** A drop can be the caller hanging up; a
   `reply-error`, `stream-lost` or `empty-reply` is the build.

## The two rows

Same handset (Pixel 7, serial `2A091FDH200AJ1`, Android as the device reports it), same
procedure: force-stop, cold launch, one call left open for ten minutes with **no speech in the
room**, logcat captured, then hung up.

**The configuration is not gate-off, and this paragraph used to say it was.** Both rows were taken
with the alpha master **on** and Phone Tools the only set selected — Presence, continuous
listening, turn-taking, wake word and the digital assistant all off, and the voice on the
default path (`whisper-base`, Piper, silero VAD). That was inferred, not read: no `er-soul` line
and the default voice names. The app now writes the state itself, and when it was asked it said
`state=on … phone_tools=true`. The rows are honest measurements of that configuration; the label
was the error, and it is corrected here rather than in a quiet edit.

| | 0.9.1.4 (versionCode 151) | testing `983f68b` nightly |
|---|---|---|
| `am start -W` | TotalTime 219 ms, COLD | TotalTime 187 ms, COLD |
| app's own cold row | not in log (`ColdFrame` postdates this tag) | `ms=108 createMs=63` |
| warm: TTS / STT | SherpaTts 360 ms / Whisper-base 440 ms | 194 ms / 334 ms |
| peak RSS | 765 MB | 730 MB |
| lowest MemAvailable | 1342 MB | 1515 MB |
| thermal | none → none, 66 samples | none → none, 66 samples |
| turns | 3 (none spoken) | 2 (none spoken) |

Both `event=res-summary` blocks cover 643 s with 66 samples.

**What the label check cost, and why it was worth it.** Writing `event=alpha-gate` into the app
was meant to make the *next* row's label provable. It made this one's checkable, and the check
failed: the inference "no Presence line means the gate is off" was wrong, because the gate has six
sets and Phone Tools was on. Two lessons, both cheap ones bought here rather than at a cut —
an absence is not a state, and a claim about configuration has to be read out of the build, not
reasoned about from the log.

## The diff, as it printed

```
baseline-diff: base «0.9.1.4 (versionCode 151), alpha master ON (phone tools only), voice defaults, bench Pixel 7» vs candidate «testing 983f68b nightly, alpha master ON (phone tools only), voice defaults, bench Pixel 7»
  turns: base 3 · candidate 2; calls: base 1 · candidate 1

| Row | Base | Candidate | Allowed drift | Verdict | Note |
|---|---|---|---|---|---|
| cold start → first frame (p50) n=0/1 | — | 108 ms | — | no-baseline | first measurement |
| speech → text (p50) n=3/2 | 17664 ms | 66859 ms | — | context | n=3/2 — a reply, not a distribution; the latency verdict is F8's |
| first byte (p50) n=3/2 | 8 ms | 9 ms | — | context | n=3/2 — a reply, not a distribution; the latency verdict is F8's |
| first text (p50) n=3/2 | 2238 ms | 2562 ms | — | context | n=3/2 — a reply, not a distribution; the latency verdict is F8's |
| first audio (p50) n=3/2 | 2409 ms | 2659 ms | — | context | n=3/2 — a reply, not a distribution; the latency verdict is F8's |
| presence render (p50) n=0/0 | — | — | — | n/a | neither run measured it |
| peak RSS | 765 MB | 730 MB | +250 MB (250 MB) | pass |  |
| lowest MemAvailable | 1342 MB | 1515 MB | −200 MB (200 MB) | pass |  |
| thermal at start | none | none | rank | pass | worse rank is worse |
| thermal at end | none | none | rank | pass | worse rank is worse |
| failed turns | 0 | 0 | 0 | pass |  |
| dropped turns | 0 | 0 | 0 | pass |  |
| warm SherpaTts warmMs | 360 ms | 194 ms | +300 ms (50% or 300 ms) | pass |  |
| warm Whisper-whisper-base warmMs | 440 ms | 334 ms | +300 ms (50% or 300 ms) | pass |  |

not compared: fullReply — gateway time (`uptimeMillis() - t0` at the gateway's `done`), not audio; it grows with reply length, so two runs differ on reply size, not on the build
not compared: stt — `now - segStart` — anchored at the microphone opening, not at the end of speech (`docs/BASELINE.md:82`, `docs/BARGE-REGRESSION-ANALYSIS.md:99`). In a quiet room the mic stays open and the value is the whole call: two runs of the same build differ by minutes. `sttDecodeMs` is the recogniser's own time; this is not it
BASELINE-DIFF PASS (8 rows compared, 4 rows printed as context and not judged)
```

**What this run does and does not say.** The eight rows the build owns pass: memory is better on
`testing`, thermal is flat on both, nothing failed, no turn was dropped. The latency rows are
context, because the run had two and three turns and no speech. So the honest sentence is: *on
everything a silent ten-minute call can measure, `testing` is no worse than 0.9.1.4* — and the
spoken half of the floor is unmeasured until a human speaks into the phone.

## The gate-off rows

Taken after the correction above, same handset, same procedure, alpha master **off** on both sides.

| | 0.9.1.4 | testing `162075d` |
|---|---|---|
| the label, as read | Settings: *"Alpha features — Off · unfinished features stay out of the way"* | `event=alpha-gate state=off presence=false …` |
| `am start -W` | cold | cold |
| app cold row | not in log | `ms=128` |
| warm TTS / STT | 307 / 431 ms | 165 / 301 ms |
| peak RSS | 705 MB | 702 MB |
| lowest MemAvailable | 1482 MB | 1542 MB |
| thermal | none → none, 66 samples / 643 s | none → none, 66 samples / 644 s |
| turns | 0 (a quiet room, nothing triggered) | 0 |

```
BASELINE-DIFF PASS (8 rows compared)
```

The eight rows the build owns pass again, with the gate off: memory is level to slightly better
(702 against 705 MB, 1542 against 1482 MB available), the two warm legs are faster (165 against 307,
301 against 431 ms), thermal is flat, and nothing failed or dropped. The conversational rows are
`n/a` on both sides — no speech, so no turns — which is the honest state of a row a human has not
yet spoken into.

The cold row has a first measurement on the candidate (`128 ms`) and no baseline, because
`ColdFrame` postdates `v0.9.1.4`; the cut after this one is the first that can judge it.

## What the rows exposed

**`stt=` is not a recogniser time, and the two rows proved it in one evening.** The candidate's
first turn reported `stt=363212` for a 3.3-second clip; its `sttDecodeMs` was 763 ms. The base
row read 17.7 s, the candidate 66.9 s — for clips of three to five seconds, on the same handset,
an hour apart. The field is `now - segStart` with `segStart` at the microphone opening, which both
`docs/BASELINE.md:82` and `docs/BARGE-REGRESSION-ANALYSIS.md:99` already recorded as a known
telemetry defect.

It matters beyond this instrument: #569 is the slow-recogniser issue, and any reading of a field
log that treats `stt=` as the recogniser's time is measuring the microphone being open. The
recogniser's own number is `sttDecodeMs`, and the live-path number is `event=stt-rtf`. That
distinction belongs in #569's body before anyone re-measures it.

**A quiet call still costs three turns.** Both builds took noise-triggered turns that ran to the
clip limit and answered. That is what makes the `stt` rows look like a catastrophe; it is also a
real behaviour worth knowing about, and it is not a defect this instrument is entitled to call.

## #579 — the profile refresh

The owner's walkthrough failure: `0 profiles added · 1 not reachable`, and the switcher unusable.
The app probed `<root>/p/<name>/v1/models` for every named profile; a gateway that serves only
`GET /v1/models` answers a generic 404, and the app called a listed profile unreachable.

What landed:

- A 404 that is **not** the host's own `Unknown or unconfigured profile` answer is a fact about
  the route, not the profile: the root `/v1/models` is asked before the profile is written off.
  The invention guard survives — on a host that does serve `/p/<name>` routes, a name the agent
  made up still answers the missing-profile 404 and is never rescued by the root.
- Every probe writes `event=profiles-probe name= host= status= reason= reachable= ms=`, with the
  host only — no key, no path. The refresh that produced the failure logged nothing at all.
- `event=profiles-ask` gained `unreachable=` and `reason=` (`whole` / `partial` / `no-block` /
  `empty-block` / `unusable` / `timeout`).
- The switcher line names the profiles: `2 profiles added · lab not reachable`.

Proven: 52 unit tests on the build host (`ProfilesAskTest`), including the owner's failure
reproduced in the pure layer. **Not proven:** paths 8 and 9 on the owner's handset. That is a
card, and until it runs the release note says the refresh is unverified on a device.

## Also in this pass

- **`#569` locale fix.** `"%.2f".format(x)` used the device locale, so a French handset wrote
  `rtf=0,68` and every reader of the log stopped at the comma. `VoxLog.num()` pins `Locale.ROOT`
  for the eight log lines that carry a decimal. Display text stays locale-aware. The guard is a
  test that moves the JVM's default locale to France first, so a regression fails on the build
  host with no phone attached.
- **The gate state is now logged** at the start of every call (`event=alpha-gate state=off
  presence=false continuous_listening=false …`), which is what makes a gate-off row's label
  checkable out of the log that produced it.

## Pending, and who holds it

**Only the owner can do these.** They are cards, not blockers (D47):

1. **Speak into the phone**: the row that judges first byte / first text / first audio needs a
   human voice, and so do paths 5 (a long reply) and 4 (a short reply). A ten-utterance F8 run on
   the candidate is the same sitting.
2. **Paths 8 and 9** on his handset: switch profile mid-use, refresh the list. Expected now:
   `event=profiles-ask … unreachable=0` and, if one still fails, a `profiles-probe` line naming
   the host and the status.
3. **The fresh-install half of the row**: uninstall, reinstall, re-enter the connection, take the
   row again — five minutes with the key in hand, and it closes the one hole in bar 2.

**An agent can carry these alone:**

- `#208`'s first matrix row (endpoint states: cleartext refused, wrong key, unreachable) — a
  driven UI pass, no voice needed.
- The remaining rows of `README`/site copy that still describe a floor nobody measured.
- Sprint 3's three benches, inside their effort budgets.

## What the next session starts from

`testing` plus this branch. The comparator is live and in the gate; the cut checklist carries it.
The next cut's row is taken with `event=alpha-gate` in it, so the row's own label is provable, and
the next diff will have a cold row on both sides — the first one it can actually judge.
