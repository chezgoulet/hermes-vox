# Kyllene review — what a second client knows that we don't

Reviewed 2026-11-13 at `Giton22/kyllene@1fb94f8a` (MIT), read-only: 870 Kotlin files, ~157k
lines, Android + desktop + Wear + Pebble. Method: four parallel lenses — connection/auth/profile
discovery; sessions and navigation; voice; engineering practice — each reading source and
returning file:line evidence. **Nothing was run.** Every claim below is read from source, and the
lenses' own "could not verify" lists are preserved in the transcript.

Why this review exists: Kyllene loads profiles automatically, and we don't. That turned out to be
the least interesting finding.

---

## 1. The decisive difference: they use a different server

Kyllene connects to the **`hermes dashboard`** process, not the OpenAI-compatible `api_server`
Vox uses. That choice is written down before any code, with a comparison table
(`docs/hermes-protocol-research.md:10-25`), and everything else follows from it:

| | dashboard surface (Kyllene) | api_server (Vox) |
|---|---|---|
| turns | WS JSON-RPC at `/api/ws` | SSE `/v1/chat/completions`, `/v1/runs` |
| profile roster | `GET /api/profiles`, `/api/profiles/active` | **none** |
| profile addressing | `profile=` parameter per call | URL prefix `/p/<name>/…` |
| credential | native bearer from `/auth/native/token` (PKCE) or password cookies | static `API_SERVER_KEY`, per profile |
| approvals | pushed over the socket | polled/streamed inside a turn |
| sessions | `/api/sessions*` | `/api/sessions*` (same) |

**This reframes two open questions.** Our conversational profile ask is not a workaround for a
missing endpoint — it is a workaround for a missing endpoint *on our surface*. And the `#579` 401
is the same fact seen from the other side: the api_server splits the auth realm per profile, while
the dashboard gives one bearer that a `profile=` parameter scopes.

**Recommendation.** Write Vox's own two-surface map (an hour): every Vox feature against the route
that serves it, marked *available* / *dashboard-only*. That document is what stops us building a
workaround for something unobtainable, or waiting for an upstream route that exists on the other
port.

---

## 2. Voice — the highest-value borrows, and they are all local

Kyllene's live voice is server-side WebRTC with the model doing STT+TTS. We should not copy the
architecture; we should copy **four devices** from their *chained* path, which is the same shape as
ours (record → transcribe → reply → speak).

**2a. Barge-in, three parts** (`BargeIn.kt:92-260`, `VoiceController.kt:629-680`). This is the
single best find in the review, because our barge-in has the exact defect their design exists to
fix — our own Piper output reads as speech.

- **An 800 ms pre-roll ring buffer**, flushed into the recording at the moment of a trip, so the
  first syllable is never clipped (`PRE_ROLL_MS=800`).
- **A bar that adapts to measured TTS bleed** — learned from the voice's own louder syllables (80th
  percentile) while it plays, not a fixed threshold — plus **sustained-majority** confirmation
  (~80 % of the last 300 ms; 500 ms over a loud echo).
- **After ducking, wait ~900 ms**: if nobody keeps talking, it was the phone hearing itself, and
  the voice resumes from where it was held (`NOBODY_AFTER_MS=900`, `isTtsEcho` with a difflib
  port, `MAX_ECHO_HOLDS=2`).

And **pause, don't stop**: playback is held (`AudioTrack.pause()`), so a false trip costs nothing
and a rejected one resumes mid-word. We currently stop.

**2b. Endpointing as a policy layer** (`Speech.kt:45-93`). Silero gives a probability per frame,
not a decision. They wrap it: 300 ms room calibration, speech bar `max(0.025, noise×2.5)`, quiet bar
`max(0.02, noise×1.6)`, 1.25 s pause to end, 12 s give-up, 60 s cap, floor tracking asymmetric
(fast down 0.3, slow up 0.02), and **two confirmation windows** — 60 ms at speech level, 200 ms at
murmur level — so a fan or a cough doesn't restart the silence timer. Plus a manual **"send now"**
once speech has been heard, for wind and cars.

**2c. Speech chunk shaping** (`Speech.kt:261-308`, `VoiceController.kt:1069-1102`). First piece
short (~160 chars), then each piece grows to 3× the *actual* previous length (cap 1200), with
**exactly one clip pre-synthesised ahead** (channel capacity 1). This is the cheapest latency win
available to us and it is model-agnostic — and it is the *opposite* of the "one small chunk" instinct
that made Supertonic drop words.

**2d. Resume, never restart** (`VoiceController.kt:936-998`). A per-reply watermark of what has been
voiced; a failure mid-reply degrades to "read the remainder", keyed by sentence boundary. With
on-device synthesis this matters more than it does for them: a Piper hiccup should cost one
sentence, not the whole answer.

**2e. Spoken approvals must fail closed** (`VoiceAnswers.kt:13-217`). Deterministic word logic, not
an LLM: fillers stripped; any negation → deny; a question mark or hedge → ask again; a grant counts
only if **every** word is in an allow-list (so "okay, let me think" is not a yes); "always"+"chat"
together → ask again; a reach the gateway never offered → ask again; and **no approval at all while
the device is locked**. With the false-positive rate of phone STT, an LLM judge is neither
deterministic nor free.

**Deliberately not copied:** server-side synthesis, WebRTC, their live-call reconnect story (they
have none — a dropped peer connection tears the call down).

---

## 3. Connection and trust — three things we report badly

**3a. Staged diagnosis** (`ConnectionCheck.kt:18-244`). "Can't connect" becomes three ordered
stages — Server → Sign in → Live — each returning a `(kind, args)` code, with earlier stages marked
*skipped* rather than *failed*, and a specific fix per failure (wrong guard, proxy in front, no chat
endpoint, client certificate wanted, unreachable by address class). **Error codes live in `core`,
wording lives in resources.** This is directly portable to api_server's own endpoints: `GET /health`
(unauthenticated), `GET /v1/capabilities` or `/v1/models` (key accepted), then a trivial streamed
turn.

**3b. Auth failure ≠ connection failure** (`NativeBearer.kt:38-113`). A rejected credential is
terminal with its own sentence; a transient failure keeps the credential and retries with backoff;
a refresh burst cannot replay a rotated token (per-gateway mutex). We currently collapse all of it
into "unreachable" — the same mistake that sent the owner looking at his network twice for a 401.

**3c. Don't send the key somewhere unsafe** (`GatewayUrl.kt:94-105`). They normalise the URL and
flag `isExposed` when the scheme is `http` and the host is not loopback / RFC1918 / Tailscale
(`100.64/10`, `.ts.net`), *before any credential is entered*. Our key is a full-authority secret and
nothing in Vox says a word about it.

**Also worth knowing:** Cloudflare Access service tokens as a first-class per-host credential with
two detectors (`AccessTokens.kt:14-86`, `ApiResult.kt:48-62`), and mTLS-from-KeyChain with
"the server wanted a client certificate" separated from "host unreachable"
(`ClientCerts.android.kt:25-127`, `ConnectionCheck.kt:52-54`). Both are cheap to *detect* even
without supporting them.

---

## 4. Session experience — adopt the posture, not the furniture

Kyllene's list is a rich desktop-class sidebar: six status tones, attention chips with counts,
projects by folder, threads, drag-reorder, search, swipe actions, desktop affordance layer. Almost
all of it should be refused by a voice client. Four things should not:

- **Ordered status tones as the single source of session state** (`SessionsSidebar.kt:2590-2608`):
  approval > question > running > new reply > draft > idle, rendered as one short phrase. In voice
  this becomes the thing the being says first.
- **One "Needs you (n)" entry** above the recent list (`SessionsViewModel.kt:124-134`) — the only
  list query a voice client should optimise, and it is cheap.
- **Offline posture** (`SessionsViewModel.kt:773-830`): keep the last known list and transcript, say
  how old it is, never show an empty state while stale data exists. A mobile voice client is out of
  network constantly; empty-or-error makes it feel dead exactly when it is being checked.
- **Uncertain delivery is a state, not a retry** (`ChatState.kt:44-54`, `ChatSession.kt:390-460`):
  a prompt that went out without an answer is marked unchecked, with check / resend / edit. Never
  auto-resend — voice makes that worse, because the user cannot see what they said.

**Explicitly not copied:** projects, threads, search screen, swipe gestures, type-ahead, the desktop
affordance layer, and the blank-list-on-empty-search state (their weakest screen).

---

## 5. Engineering practice — one big idea, several small ones

- **Split the gate into tiers** (`ci.yml`, `linux.yml:1-2`, `ci-selection.py`): a fast gate on every
  push, an explicitly *advisory* workflow whose name says so, path-selected expensive jobs, and a
  selector script with its own tests that fails closed on an unknown event. Our gate is more
  thorough than theirs and monolithic; tiering it is the structural win.
- **A `dev` build type** — `initWith release`, R8 on, not debuggable, `.dev` application id, debug
  signing (`androidApp/build.gradle.kts:100-111`). For a voice app this is not a nicety: latency
  measured in a debuggable build is fiction.
- **Assertions on the built artifact** (`ci.yml:50-77`): grep the minified dex for the class names
  ProGuard exists to protect, and `apkanalyzer` for reflection signatures. Green build, crash only
  in the shipped APK, is a failure mode we share.
- **Lint as a ratcheting gate** with a committed baseline that may only shrink
  (`androidApp/build.gradle.kts:74-92`). They have *no* ktlint/detekt — an area where we can be
  stricter than they are.
- **Fit tests at the narrowest screen and largest text** (`ReplyActionsFitTest.kt:36-56`): Robolectric
  at `w320dp` with font scale 2.6, each test named after the bug it prevents. We have a dense row
  (record / waveform / transcript) that will break exactly this way.
- **Cross-artifact identity tests**: where one fact lives in two files, a test reads both
  (`PebbleAppTest.kt:11-40`). We have DOC-TRUTH, which generalises this — the gap is *non-Kotlin*
  artifacts (package id, signing fingerprint, wire constants copied into JSON).

---

## 6. What we do better (so this stays a review, not a fan letter)

- **Doc truth is centralised and machine-checked** (90 invariants); theirs is a handful of ad-hoc
  pairwise comparisons and nothing checks README/ROADMAP prose.
- **Stream-parity fixtures** against a committed golden corpus — no equivalent there.
- **Shell scripts that test themselves**; ~200 lines of their release/packaging bash are unlinted
  and untested.
- **Reproducibility check**: they have none (`SOURCE_DATE_EPOCH`, rebuild-and-compare, dependency
  verification metadata all absent) while publishing signed APKs and a GPG-signed repo.
- **Nightlies on every push**; they build Android artifacts on PR/push and at tag, so an APK-only
  break surfaces at release.
- **No KMP tax**: five modules, a `jvmShared` source set, a desktop app, JCEF/jpackage, a Pebble C
  app and a Flatpak repo — thousands of lines of non-product code we do not need.
- **No advisory tier**: nothing in our gate can sit red on master without blocking.

---

## 7. The shortlist

Ranked by value per hour, with the honest effort:

1. **Barge-in: pre-roll (800 ms), adaptive bar, 900 ms continued-speech check, transcript-similarity
   echo rejection, pause-instead-of-stop.** Half a day to a day, entirely on-device, and it fixes a
   defect we can hear today.
2. **Speech chunk shaping** (short first piece, geometric growth, one clip ahead) and **resume from
   a voiced watermark**. Half a day. Improves time-to-first-audio and makes a failed synthesis cost
   one sentence.
3. **Auth vs transport failures separated, and the URL-exposure refusal.** A few hours; ends the
   class of "it said unreachable and I went looking at my network".
4. **Staged connection diagnosis** on `/health` → `/v1/capabilities` → trivial turn. A day.
5. **Endpointing policy layer** with hysteresis, asymmetric floor tracking, idle/max caps and a
   manual "send now". A day.
6. **The two-surface map** (documentation, an hour) — decides whether any future feature is
   buildable on our transport at all.
7. **Engineering tiering**: `dev` build type, artifact assertions, lint baseline, fit tests. Two to
   three days, in that order.

Everything else in Kyllene's 157k lines is either server-side architecture we have deliberately
rejected, desktop furniture a voice client cannot use, or a gap we already close better.

---

## Appendix — where the evidence is

- Protocol and surface decision: `docs/hermes-protocol-research.md:10-25, 59-70`.
- Auth, refresh policy, cookie encoding: `core/auth/{AuthApi,NativeBearer,BrowserSignIn,PersistentCookiesStorage}.kt`.
- Proxy/mTLS: `core/gateway/{AccessTokens,ClientCerts}.kt`, `ClientCerts.android.kt`.
- Staged diagnosis and error codes: `core/gateway/ConnectionCheck.kt`, `core/network/ApiResult.kt`.
- Profiles: `core/profiles/Profiles.kt`, `shared/ui/.../AppViewModel.kt:554-566`.
- Voice: `core/voice/{BargeIn,Speech,VoiceAnswers,VoiceController,SpeechStream}.kt`, `androidApp/.../{AndroidVoice,AndroidLiveCall}.kt`.
- Sessions and navigation: `shared/ui/.../sessions/*`, `chat/*`, `ui/App.kt`.
- Engineering: `.github/workflows/*`, `packaging/flatpak/*`, `shared/*/build.gradle.kts`, `docs/i18n.md`.
