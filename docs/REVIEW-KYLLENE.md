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

---

## 8. Against our own research, not against their code

The shortlist in §7 is ranked by effort and effect. This section tests the same findings against
what we already believe an excellent voice agent *is* — `docs/RESEARCH-2026-10-voice-agents.md`,
D18's cadence target, and the four consequences that research drew. The question is not "do they
have it" but "does their reasoning agree with ours, and if not, who is right".

### Where they converge with findings we had already made, and implement them better than we do

**Recovery after an interruption (research §4).** Our checkpoint says we have "no notion of any of
the three" recovery criteria: resume the workflow, address the interjection, do not re-deliver what
was heard. Kyllene has the third one *mechanically*: a per-reply voiced watermark, playback held
rather than stopped, and a rejected trip resuming mid-word. That is the implementation our finding
implies and we never built. They are silent on the first two — they resume the **reply**, not the
**task** — which is exactly where our agentic angle is ahead, and worth keeping in view: nobody in
this pair has built the recovery that matters for a long tool run.

**TTS must begin before the reply is complete (D18).** Our decision says the endpointer must commit
early and TTS must start before the reply finishes. Their chunk shaping — short first piece, then
each piece growing to 3× the actual previous length, one clip pre-synthesised ahead — is that
sentence as an algorithm. We have the principle and not the mechanism; they have the mechanism
without having written the principle down.

**Overlap is a *class*, not a level (research §3).** Our checkpoint states the field's
polarisation: "trigger-happy VAD" versus "unacceptable response delays", and concludes the missing
dimension is the class of the sound. Kyllene's barge gate is **still a level gate** — adaptive,
hysteretic, sustained — so on our own reading of the literature they are at the trigger-happy pole
with better engineering. But two devices of theirs converge on §3 from a different direction and
deserve to be read as evidence:

- **transcript similarity** rejects a trip when the captured audio transcribes to what is being
  spoken. That is classification, but of *self-echo vs user*, not of *backchannel vs interruption*.
  It answers a question our §3 framing never separated out, and it is the precondition for any
  barge policy in a room where the phone can hear itself.
- **the 900 ms continued-speech test** is a duration proxy for class: a short syllable resumes the
  reply, sustained speech cancels it. That happens to produce the §3 outcome (a backchannel does
  not kill the reply) without ever predicting the class. It is a cheap heuristic that gets the
  behaviour right — not the same thing as the finding, and not to be mistaken for it.

**AGC defeats endpointing (research §5 and D18).** Their recorder deliberately disables automatic
gain control, with the reason stated: AGC lifts the room hum until every pause reads as talking and
end-of-speech is never found. That is our §5 conclusion arrived at from a bug, and it is worth
recording as confirmation.

### Where they diverge, and our research is right

**Their endpoint pause cannot meet D18.** They wait 1.25 s of silence before declaring the turn
over, with 12 s and 60 s caps. D18 forbids exactly that shape: at a 700 ms p50 speech-end-to-audio
target you cannot wait out a silence threshold and still be natural — the endpointer commits early
and is occasionally wrong, which is what the provisional-endpoint frame exists for. They can afford
1.25 s because their reply comes from a cloud model with its own round-trip; we cannot, and copying
their number would quietly repeal D18.

**Their endpointing is a policy, not a prediction.** §5 says endpointing is a prediction problem
and that backchannel timing is continuous and language-specific. Their state machine is a good
policy over levels and durations; it is not the architecture our research points at. Adopt the
hysteresis, the two confirmation windows, the idle/max caps and the manual commit — as an interim,
not as the answer.

**Their barge-in has no notion of addressing the interjection.** §4 asks whether the agent addresses
what the user said when they cut in. Theirs cancels the reply and takes the new turn; the
interjection is not treated as content to be answered specially. Ours is not better — we have no
recovery at all — but the finding stays open, and this is the clearest place where "port their
device" would leave the actual goal untouched.

### Where our corpus had a blind spot, and they fill it

None of our four findings mention any of these, and each decides whether the policies work in a
real room:

- **the first syllable.** An 800 ms pre-roll ring buffer flushed into the recording at the trip.
  Our research discusses *when* to interrupt and *what to do after*, never *whether the beginning
  survived*.
- **a manual commit** ("send now") for wind, cars and any room where the pause never comes. D18's
  early-commit design has a failure mode — being wrong — and this is the human override for it.
- **a spoken approval grammar that fails closed**: allow-list per choice, any hedge or question
  means ask again, no approval while the device is locked. Approval is the one place a misheard yes
  is unrecoverable, and our corpus talks about latency and interruption but not about consent.
- **the self-echo class.** Separating "the phone heard itself" from "the user spoke" is a
  precondition for measuring anything else about barge-in, and it is absent from our findings.

### The comparison in one line

They have solved **the physics** — echo, pre-roll, chunk shaping, resume — and not **the
linguistics** — what kind of sound an overlap is, what the user meant by it, and what the agent owes
them afterwards. Our corpus names the linguistics and has not built the physics. The shortlist in §7
is ordered by physics first for a reason: the linguistics cannot be measured while the microphone is
still arguing with the speaker.

### What that changes about the shortlist

Nothing is removed, and two items move:

- **Echo rejection rises above the adaptive bar.** A barge policy whose input is contaminated by
  its own output is measuring the wrong signal; fix the signal before tuning the threshold.
- **Recovery splits in two.** "Do not re-deliver what was heard" (theirs, mechanical, half a day)
  and "resume the task and address the interjection" (ours, unbuilt by anyone here — needs an issue
  of its own, and it is the honest answer to §4 rather than a port).

## Appendix — where the evidence is

- Protocol and surface decision: `docs/hermes-protocol-research.md:10-25, 59-70`.
- Auth, refresh policy, cookie encoding: `core/auth/{AuthApi,NativeBearer,BrowserSignIn,PersistentCookiesStorage}.kt`.
- Proxy/mTLS: `core/gateway/{AccessTokens,ClientCerts}.kt`, `ClientCerts.android.kt`.
- Staged diagnosis and error codes: `core/gateway/ConnectionCheck.kt`, `core/network/ApiResult.kt`.
- Profiles: `core/profiles/Profiles.kt`, `shared/ui/.../AppViewModel.kt:554-566`.
- Voice: `core/voice/{BargeIn,Speech,VoiceAnswers,VoiceController,SpeechStream}.kt`, `androidApp/.../{AndroidVoice,AndroidLiveCall}.kt`.
- Sessions and navigation: `shared/ui/.../sessions/*`, `chat/*`, `ui/App.kt`.
- Engineering: `.github/workflows/*`, `packaging/flatpak/*`, `shared/*/build.gradle.kts`, `docs/i18n.md`.
