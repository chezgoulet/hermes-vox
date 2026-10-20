# Presence lane — why a line is dropped `late`

Diagnosis for the 2026-10-20 field sessions (17 `er-soul-drop reason=late`, 0 guard
refusals, one session with 4 working slots). Line numbers are the tree as it stands after
the fix in §5; the changed step is named in §2.

## 1. There are two `late` sites, and the field's 17 are not the working lane

| Lane | Log line | `late` decided by | Counted by the report as |
|---|---|---|---|
| turn / beat render | `event=er-soul-drop reason=late` | `VoiceController.kt:1226` (`soulTurn.submitted`) | `late_drops` (`presence-session-report.py:114`) |
| mid-run working slot | `event=er-presence skip line=N reason=late` | `VoiceController.kt:292` (`turnGen`/first text/`cadenceLive`) | `skips.late` |

`event=er-soul-drop` is emitted only at `VoiceController.kt:207` (greeting), `:1226`
(turn/beat) and `:1233` (post-latch). The working slot never emits it. So the brief's
17 `er-soul-drop reason=late` are **turn renders**, not working slots — and the
arithmetic agrees: 4 working slots exist in the whole field set, so they cannot account
for 17 drops.

The two faults are real but separate. §5 fixes the working lane, as scoped. The turn lane
cannot be fixed within the constraints (§4).

## 2. Call path of a working slot

1. `ErPresence.kt:234` — the presence tick runs every 1000 ms.
2. `ErPresence.kt:207` → `PresenceCadence.shouldSpeak` (`PresenceCadence.kt:72`). Nothing
   before `ErFillers.LAG_AFTER_MS` = 4000 ms (`PresenceCadence.kt:75`); line 1 is the lag
   line; line N > 1 is one `Density.intervalMs` later (18 s, `PresenceCadence.kt:28`).
3. `ErPresence.kt:209` — the slot is minted; `ErPresence.kt:213` calls `onWorkingSlot`
   for line > 1 only.
4. `VoiceController.kt:177` — `onWorkingSlot = { slot -> requestWorkingLine(slot) }`.
5. `VoiceController.kt:236-237` — `turnGen` and the run's already-said lines are read.
6. **The render is requested.** Before the fix this was the *only* request site, reached
   when the slot opened (`VoiceController.kt:250`). Now the run also renders early at
   `VoiceController.kt:265` (`prewarmWorkingLine`, scheduled at `:1260`), and the slot
   consumes that line at `:244-247`; `:250` is the on-demand fallback.
7. `MainActivity.kt:987-993` — `soulDecide` maps `KIND_WORKING` to the `soul-narrate`
   intent and calls `orch.expressAsync`.
8. `VoxExpress.kt:100-108` — one render in flight; `soul-narrate` is **not** urgent, so it
   is dropped while any other render runs. `GemmaExpress.kt:238-246` — a second floor:
   `ErGemmaGuard.MIN_RENDER_SPACING_MS` = 1200 ms skips a non-turn render that arrives too
   soon.
9. `VoiceController.kt:285` — the render becomes a line (`ErSoulTurn.workingLine`).
10. `VoiceController.kt:286-297` — **`late` is decided here**: `gen != turnGen ||
    WorkingRender.late(firstTextAtMs, now) || !erPresence.cadenceLive(slot.runId)`.
11. `VoiceController.kt:321` — `speakGlue(line, SOURCE_WORKING)` delivers it.

The first text is timestamped and the cadence ended at `VoiceController.kt:1505-1508`.

## 3. Timeline of a working slot, before the fix

| t | Event | Where |
|---|---|---|
| 0 | window opens, cadence run starts, tick armed | `ErPresence.kt:139-150` |
| ~0 | turn/beat render requested (urgent) | `VoiceController.kt:1205` |
| ~0.7–0.9 s | `submitted`, gateway request | `:1292-1295`, `:1303` |
| ~1.8 s | mind's first text → latch, `endCadence("reply")` | `:1505-1508` |
| 4 s | tick: cadence line 1 = lag line (no render) | `ErPresence.kt:214` |
| 4 s + 18 s = 22 s | tick: line 2 → `requestWorkingLine` → **render requested** | `:250` |
| 22 s + ~2.5 s | render returns → delivery check | `:286-297` |

The run only survives to 22 s if the first text has **not** landed (a text delta ends the
cadence). So a working `late` fires only when the mind's first text lands inside the
2.5 s window between the slot opening and the render returning. After the fix the render
is already done at 22 s, so delivery happens at slot open and that window is gone.

## 4. Conclusion

- **Working lane: requested too late.** The code ordered the render after the cadence had
  decided the line was due, so a ~2.5 s render sat between "due" and "delivered". Moving
  the request to the run removes that window. Evidence: the code ordering in §2, not the
  logs — the field logged no working `late` at all.
- **Turn lane: too slow, not too late.** The turn render is already requested before the
  gateway submit (`:1205` < `:1303`) and runs in parallel, so there is nothing earlier to
  move it to. It is dropped because 2.5 s exceeds `SoulGate.MAX_WAIT_MS` = 900 ms
  (`SoulGate.kt:19`). Fixing that means waiting longer on the mind or changing constants —
  both out of scope. `late` is the correct rejection there.
- **Not established from the logs:** whether any turn `late` arrived *before* the first
  text (it is possible: with a measured p50 > 1200 ms, `SoulGate.waitMs` returns 0 and
  `submitted` is true immediately, so `late` fires on submit rather than on first text).
  Distinguishing that needs a timestamp at both edges; the new `deltaMs=` witness gives
  one for the working lane only.

## 5. The change that moved the request earlier

One warm render per run, requested at the first moment a working line could ever be
spoken — `ErFillers.LAG_AFTER_MS` (4 s) after the turn starts (`VoiceController.kt:1260`) —
and consumed by the first working slot instead of rendered on demand. No capture-loop,
barge, cadence-register or guard-bound change; `late` keeps its rule.

The literal "at turn start" point is not admissible, and was rejected: the turn's own
render is in flight there (dropped by `VoxExpress.kt:100-108`), the 1200 ms spacing rail
would blank it, and it would discard the `mindTool` context that makes the line topical.
