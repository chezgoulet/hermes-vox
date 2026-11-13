# What the presence layer costs, and what each way of not paying it costs instead

> **Historical — kept as a record.** It describes the state of the app at the time it was written and is not a claim about the current build. See `docs/INDEX.md` for what is current.

The presence layer is a Gemma 4 E2B bundle (2.5 GB on disk) that is built on every launch and, by
shipped default, **never released while the process lives**. `docs/BUDGETS-0.9.1.md` budgets 220 MB
of idle PSS and a 1.5 GB whole-process ceiling. This document measures what the layer actually costs
on the gated phone, and prices the three ways of not paying it:

1. **hears off** — build the engine without its audio encoder (`presence_hears=false`).
2. **deferred load** — do not build the engine at launch; build it when a call starts
   (`presence_lazy_load=true`).
3. **release while open** — release the engine after N minutes with no live call
   (`presence_idle_open_min=N`).

None of the three is on by default, and none changes behaviour for a phone that has not set the
pref. Each is settable from Settings → Presence → *Presence memory (dev)* and, on a bench build,
from the harness.

Device: Pixel 7 `2A091FDH200AJ1` (the 8 GB class the budgets are gated on). Raw runs:
`presence-cost-results/*.jsonl`.

## 1. What is resident, measured on the release build

Release-signed `0.9.1-nightly-ffaa5e6`, versionCode 147, n=10 per arm, same session. "Presence on"
is the shipped default.

| | presence **on** (shipped) | presence **off** |
|---|---|---|
| idle PSS, median | **2,709,607 KB (2,646 MB)** | **179,751 KB (176 MB)** |
| idle PSS, range | 2,661,410 – 2,784,463 KB | 179,380 – 184,163 KB |
| MemAvailable drop, median | **2,237 MB** | 40 MB |
| time to a warm presence, median | **27,505 ms** (27,353 – 28,830) | — |
| cold start, median | **222 ms** (204, 209, 220, 221, 222, 222, 230, 233, 234, 250) | **172.5 ms** (166, 168, 170, 171, 172, 173, 174, 176, 177, 211) |

What follows from those numbers alone:

- The resident layer costs **~2,470 MB of PSS** and **~2.2 GB of MemAvailable**.
- **Both memory budgets are broken at rest**: 2,646 MB against a 220 MB idle budget, and the process
  is already past its 1.5 GB whole-process ceiling before a call starts.
- **Cold start pays ~49.5 ms** for the layer (222 ms vs 172.5 ms, same build, same session). The
  161 ms figure in `BUDGETS-0.9.1.md` is the earlier `2ed5714` run, not today's.
- **A warm presence is ~27.5 s away**, in the background. The window between first frame and
  "ready to render" is real: the layer cannot speak during it.

## 2. The three options

`hears` is read at engine load, so the prefs have to be set before the load. The release build is
release-signed and refuses `adb shell run-as`, so its prefs are read-only from the host — the three
arms were therefore measured on a **bench build** (`com.hermesvox.presencecost`, debug-signed,
installed beside the owner's app, its own data dir, a hash-verified copy of the same model). The
build procedure is in `scripts/presence-cost.sh`.

The bench numbers below are a **debug** build's, and are compared only against each other. For
scale, its shipped-default PSS (2,700,448.5 KB) is within 9 MB of the release build's
(2,709,607 KB), which is why the `hears` delta is quoted as representative rather than as a release
figure.

| arm | prefs | engine at idle | idle PSS, median | load, median | cold start, median |
|---|---|---|---|---|---|
| default (today) | `hears=true`, load at launch | yes, with ears | **2,700,448.5 KB (2,637 MB)** | 5,634.5 ms | 434.5 ms |
| no-hearing | `hears=false` | yes, text-only | **2,625,918.5 KB (2,564 MB)** | 5,771.5 ms | 372.5 ms |
| deferred | `lazy_load=true` | **no** | **208,977 KB (204 MB)** | none — never loaded | 359 ms |
| idle-release | `idle_open_min=2` | yes, then no | 2,647,586 KB → **261,622 KB** after 120 s | 5,834 ms | 370 ms |

n=10 for default and no-hearing, n=3 for deferred, n=1 plus a 260 s trace for idle-release.

### 2a. hears off — the audio tower costs 73 MB, not 2.6 GB

The hypothesis was that a layer which only *expresses* (the caller's words arrive as text from the
gateway; the mic belongs to the call's own recogniser) might be paying for an audio tower it never
uses. Measured on the same install, ten runs each:

- **with ears: 2,700,448.5 KB median. Without ears: 2,625,918.5 KB median.**
- **The audio encoder and its adapter cost 74,530 KB — 72.8 MB, 2.76 % of the resident layer.**

So the ears are cheap and the ears are not the problem: the 2.6 GB is the language model and its
KV cache. What `hears=false` also does is change behaviour: `hears` is set false for the session and
`render()` stops passing audio at all (`GemmaExpress.kt`, `if (hears) audio else null`), so the
`soul-heard` path is dead even if a caller later sends audio. Paying a semantic change for 2.8 % of
the memory is the wrong trade.

Cold start did come out ~62 ms lower in this arm (372.5 vs 434.5 ms), outside the two ranges only at
the edges; it is reported, but one ten-run comparison on a debug build is not strong enough to
attribute it to the missing encoder.

### 2b. deferred load — everything back at idle, paid on the first call

Launched with `lazy_load=true`, the engine is never built: three runs, **PSS 204 MB** at idle
(197,560–209,101 KB at start, settling at 204,743–209,101 KB), **93 MB** of MemAvailable instead of
2,237 MB, and no `presence-load` line in the log at all — the arm is `none`, i.e. no engine.

The cost moves to the first call. A call start already runs `ensurePresenceLoading()` and waits on
`soulReady`, offering a skip after a bounded wait, so the user sees the existing "warming" state and
then either a voice or a text-only call. That wait is the measured load: **5.6 s on the bench build,
27.5 s on the release build** (§1). Every launch pays it on the first call, because nothing loads
before one.

### 2c. release while open — 2.4 GB back after N minutes, reload only if you come back

The existing hangup arm (`presence_unload_delay_min`, shipped 1 min) only arms **after a call
ends**; an app launched and left on a desk arms nothing, which is how the 2.6 GB stays resident
forever. This arm is the same release armed by "no call is live". Measured with `idle_open_min=2`:

```
06:27:59  event=presence-idle-open-arm minutes=2
06:30:00  event=presence-unload reason=idle-open released=true availMb=2572
```

The timer fired 120.0 s after being armed. PSS across the window: 2,645,278 → 2,631,825 KB while
armed, then **290,606 KB** at the first sample after the release, settling at **261,622 KB**.
MemAvailable went from ~1,468 MB to ~3,512 MB. The release returns **2,383,656 KB** in total,
though it settles ~57 MB above a launch that never loaded (allocator retention) — 256 MB vs 204 MB.

The cost is the same reload as deferred, but only charged to a call that arrives after the window;
a call started inside the window finds the engine warm, and a call start cancels the pending
release (`cancelIdleUnload` → `cancelIdleWhileOpen`). The hangup arm still applies after a call, so
the two compose rather than stack.

## 3. The trade-off, in the terms a user feels

| | resident at idle | in a call | cold start | time to warm presence | what the user experiences | risk |
|---|---|---|---|---|---|---|
| **today** (shipped) | 2,646 MB | engine already resident; no load | 222 ms median | 27.5 s once, after launch | first call after launch is warm only after ~27 s; other apps get killed on an 8 GB phone | the 220 MB and 1.5 GB budgets are broken; background apps are evicted |
| **hears off** | 2,564 MB (−73 MB) | text-only engine | 372.5 ms bench (−62 ms, single comparison) | unchanged | no visible change until something wants the audio path, which is then silently gone | a dead `soul-heard` path; 2.8 % is not worth a semantic change |
| **deferred load** | **204 MB** (−2,433 MB) | pays the full load before it can speak | unchanged (359 ms bench) | 5.6 s bench / 27.5 s release **on the first call, every launch** | the first call after opening the app is slow and may fall back to text | every first call is charged, even for a user who opens the app to talk |
| **release while open (2 min)** | 2,646 MB for 2 min, then **256 MB** | warm if the call starts inside the window; otherwise the same reload | unchanged (370 ms bench) | 27.5 s only after an idle window, then warm again | a call made right after opening is fast; a call after walking away waits | reload charged after idle; ~57 MB is not returned |

## 4. Recommendation

**Keep today's behaviour as the default and ship these as switches — then switch on release while
open, not the other two.**

- **Release while open is the one with the numbers behind it.** It is the only arm that keeps the
  common case (open the app, call) warm *and* returns the memory in the case the budgets actually
  describe (app sitting at rest): 2,646 MB → 262 MB measured, 2,383,656 KB returned, 1,468 → 3,512 MB
  of MemAvailable. The 27.5 s reload is charged only to a call that arrives after the idle window,
  and that is the same load the process pays after any death.
- **Deferred load is not better, only earlier.** It saves the same ~2.4 GB but charges the 27.5 s to
  the first call of *every* launch, i.e. exactly the moment the user wanted the entity to speak. Its
  one advantage — never holding the memory at all — matters only if the budget is measured at 30 s
  after launch rather than at rest.
- **hears off should not be shipped.** 72.8 MB of 2,637 MB does not justify killing the audio path,
  and the measurement says the audio tower is not where the memory is.

Before choosing the window: **the in-call PSS with the layer resident is not measured here** (§5),
so "2 min" is the smallest choice the Settings screen offers, not a number derived from a call
budget. If the in-call figure comes in near the 1.5 GB ceiling, 2 min is the right default; if there
is headroom, 5 or 10 min costs the user fewer reloads.

## 5. What could not be measured here

- **The three arm prefs on the release build.** A release-signed APK is not debuggable, so
  `adb shell run-as` is refused and `scripts/presence-cost.sh pref` dies with that reason. The bench
  build exists for exactly this; the procedure is in the script header:
  `./gradlew assembleDebug -Phermesvox.benchSuffix=presencecost`, install it beside the owner's app,
  `run-as … cat` the model into `files/models/gemma-e2b/`, then
  `PKG=com.hermesvox.presencecost scripts/presence-cost.sh arm no-hearing 10`.
- **In-call PSS with the presence engine resident.** The call path needs a live gateway; this
  measures launch and rest. The 640 MB in-call figure in `BUDGETS-0.9.1.md` is three local models
  *without* presence, so the two do not compose into a measured total.
- **The ~5.6 s vs ~27.5 s load difference was not isolated.** The bench build is debug and was run
  with the owner's app stopped (≈3.9 GB MemAvailable); the release runs had a resident engine and
  ≈3.3 GB. Page cache is the likely explanation, not proven, so load times are compared within an
  arm set, never across them.
- **Why the ears change cold start by ~62 ms** is not explained by this measurement.

### Two failed attempts, recorded so the next run does not repeat them

1. **An empty `presence-cost-results/arm-default.jsonl`.** The first bench attempt wrote no rows at
   all, because `am start -W -n com.hermesvox.<suffix>/.MainActivity` names a class that does not
   exist on a suffixed package, and the script exited before writing. The harness now asks the phone
   for the launcher component (`cmd package resolve-activity`) and refuses to write a row when
   `am start -W` printed no `TotalTime`.
2. **An arm that measured today's behaviour under another name.** The first `no-hearing` run
   reported `hears=true` in the engine's own load line. The pref writer had emitted
   `<presence_hears value="false" />`, but Android's SharedPreferences takes the **element name as
   the type** and the key from `name=`; the app ignored the entry, then wrote its own map back over
   the file. The writer now emits `<boolean name="presence_hears" value="false" />` and the arm
   read-back checks that same shape. The `no-hearing`, `deferred` and `idle-release` figures above
   are all from re-runs after that fix; the `default` rows predate it but asked for the defaults
   anyway, and the engine's own line confirms `hears=true` for every one of them.
