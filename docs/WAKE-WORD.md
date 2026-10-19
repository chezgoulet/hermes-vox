# Wake word — design and evidence (#45)

Status: **foundation only** (2026-10-19). The app has a wake-word engine seam with an
inert implementation and an enrolment screen that records the owner's phrase takes,
~15 s of free speech and ~15 s of room sound — everything a personal verifier is
trained from. Nothing is trained and nothing listens in the background. §9 records a
bounded detector spike (sherpa KWS vs openWakeWord) and what it could and could not
settle. This document is the plan the next dispatches build from,
and it records what is known, what is only claimed upstream, and what we must measure.

Decision context: `docs/DECISIONS.md` D6 (ship "Hey Hermes", personalise, then
arbitrary phrases; enrolment audio is biometric data). §8 lists where the evidence
below argues for amending D6.

Notation: **[src]** = taken from a cited source; **[ours]** = checked in this repo or
its pinned artefacts; **[est]** = our estimate, not measured — treat as a hypothesis.

---

## 1. What we established

| Fact | Evidence |
|---|---|
| openWakeWord pipeline: melspectrogram → frozen Google speech-embedding model → small per-phrase classifier; 16 kHz PCM16 in **80 ms frames**, one 0–1 score per frame. | [src] openWakeWord README |
| Code is Apache-2.0; **openWakeWord's own pretrained models are CC BY-NC-SA 4.0** "because some training datasets have unknown or restrictive licenses". | [src] README |
| Upstream accuracy targets are **<5 % false rejects and <0.5 false accepts/hour "with appropriate threshold tuning"** — described as subjective targets, not measurements. The `hey_jarvis` model page says "there is not a test set available to evaluate this model". | [src] README, `docs/models/hey_jarvis.md` |
| An independent (vendor) benchmark measured openWakeWord at **8.50 FA/h at 68.6 % recall** on a 25 h "hey livekit" set; no threshold met the 0.1 FA/h target. LiveKit's own conv-attention model: 0.08 FA/h at 86.1 %. | [src] livekit/livekit-wakeword README (Apr 2026). A competitor's benchmark of a model it trained — indicative, not decisive. |
| One Raspberry Pi 3 core runs 15–20 openWakeWord models in real time. No per-model phone CPU or battery figure is published. | [src] README |
| Shared front-end files are small: `melspectrogram.onnx` 1.09 MB, `embedding_model.onnx` 1.33 MB; a full phrase model (`hey_jarvis_v0.1.onnx`, two stacked networks, ~103 k params) is 1.27 MB. | [ours] GitHub release v0.5.1 asset sizes |
| Training notebook defaults: Piper sample generator (`en_US-libritts_r-medium`) for synthetic positives, MIT RIRs + AudioSet + FMA for augmentation, **~2,000 h of precomputed ACAV100M negative features (17.3 GB file)**, ~11 h validation features (185 MB), 10,000 steps, ONNX + TFLite output. Clip generation ~10 min on a Colab T4. | [src] `notebooks/automatic_model_training.ipynb`; [ours] HF file sizes |
| The shipped `hey_jarvis` model used **~200,000 synthetic clips** and **~31,000 h of negatives** (ACAV100M, Common Voice 11, podcasts, FMA). | [src] `docs/models/hey_jarvis.md` |
| Custom verifier: a **logistic regression** on openWakeWord's features, trained from **≥3 positive takes** and **≥~10 s of the same speaker saying other things** (+ optional ~5 s background per environment). Runs only when the base score exceeds `custom_verifier_threshold`; its score then replaces the base score. Upstream says it is **"not intended to perform the task of speaker verification directly."** | [src] `docs/custom_verifier_models.md` |
| **Our pinned sherpa-onnx 1.13.6 AAR already contains `KeywordSpotter`** (open-vocabulary keyword spotting) **and `SpeakerEmbeddingExtractor`/`SpeakerEmbeddingManager`** (speaker verification). | [ours] `classes.jar` of the pinned AAR |
| The AAR bundles `libonnxruntime.so` **1.27.1** but **no ONNX Runtime Java API** (`ai.onnxruntime.*` absent). | [ours] `strings` + class listing |
| `KeywordSpotter` has JNI symbols in **all four ABIs** of the AAR; its keywords must be **pre-tokenised** (space-separated BPE pieces looked up in `tokens.txt`); the KWS path has no text-to-token step and the Kotlin API ships no tokenizer. | [ours] `jni/*/libsherpa-onnx-jni.so`; sherpa-onnx 1.13.6 source `csrc/utils.cc` `EncodeBase`, `csrc/keyword-spotter.h` |
| **ORT-Android 1.27.1 does not exist on Maven Central** (1.27.0 then 1.28.0; latest 1.31.0). 1.27.0's AAR is 44.5 MB and ships `jni/<abi>/libonnxruntime.so` (arm64: 27,985,944 B) + `libonnxruntime4j_jni.so` (111,648 B) — the **same path** as sherpa's 1.27.1 copy (arm64: 21,684,880 B). | [ours] `maven-metadata.xml`; AAR listing, sha256 `077dec5e…fbeb` |
| The app's LiteRT-LM AAR (`litertlm-android` 0.16.1) is an LLM engine only: no general `.tflite` interpreter class. | [ours] class listing of the cached AAR |
| sherpa KWS English model (`kws-zipformer-gigaspeech-3.3M`): encoder 4.6 MB int8 (12 MB fp32), keywords given as BPE tokens in a text file, **no retraining to add a phrase**. No published recall/false-alarm figures. A newer zh-en model offers ~160 ms (chunk-8) or ~320 ms (chunk-16) latency. | [src] k2-fsa KWS docs |
| Android 14+: a `microphone` FGS must be started while the app has a visible activity (or from a notification/widget interaction); it **cannot be started from `BOOT_COMPLETED`**. | [src] developer.android.com FGS restrictions; Android 15 behaviour changes |
| The low-power hotword DSP path (`CAPTURE_AUDIO_HOTWORD`) is `signature|privileged|role` — "Not for use by third-party applications" except via the assistant role. | [ours] AOSP `core/res/AndroidManifest.xml` (main) |

What we have **not** established: any false-accept rate for a "Hey Hermes" model, any
battery figure on our phones, or sherpa KWS accuracy. Those are §6's measurements.

---

## 2. "Hey Hermes" — the default phrase

### 2.1 Training (in our pipeline, off-device)

Inputs:

1. **Positives — synthetic only.** Piper sample generator over a multi-speaker model,
   with speaker-embedding interpolation for voice variety. The notebook's 1,000 clips
   is a demo; upstream's production model used ~200,000. **Plan: 20,000–50,000
   [est]**, plus spelling variants that TTS pronounces differently ("Hey Hermes",
   "Hey Hermès", "Hay Hermes").
2. **Adversarial negatives** — phrases that should *not* fire, synthesised the same
   way: "Hermes" alone, "hey her", "hermits", "Herman's", "hey Herbie", "her mess",
   "Hermès" (the brand), plus the app's own TTS voices saying "Hermes" in sentences
   (the being says its name; it must not wake itself).
3. **Background negatives** — the 2,000 h ACAV100M feature set (17.3 GB) or our own
   (see §8, decision 1), plus room impulse responses and noise/music for augmentation.
4. **Validation** — the 11 h false-positive feature set to pick the threshold, and,
   before shipping, our own eval set (§6).

Compute **[est]**: clip generation scales from the measured ~10 min per 1,000 clips on
a T4 → ~3–8 GPU-hours for 20–50 k clips; augmentation + feature extraction is the
same order; training 10–50 k steps on a small classifier is minutes to an hour.
Realistically **half a day to a day on the Thelio's GPU**, dominated by synthesis, and
the 17 GB negative-feature download. Not viable on the Odroid's CPU.

Output: `hey_hermes.onnx` (~1 MB), versioned and checksummed like our other models.

### 2.2 Shipping and loading on Android

- Ship three files: the two shared front-end models (2.4 MB, Apache-2.0 upstream) and
  `hey_hermes.onnx`. Bundle in `assets/` (small) **or** list them in `ModelCatalog`
  as an optional download; the catalog route keeps the APK size unchanged and reuses
  the existing download/verify/resume path. Recommend the catalog.
- **Runtime — needs a decision** (§8, decision 2; evidence in §9). Our AAR carries ORT
  1.27.1 natively but no Java API to load arbitrary graphs, and nothing already in the
  app can run openWakeWord: **every route is a new dependency**. Options:
  - `com.microsoft.onnxruntime:onnxruntime-android` (Maven Central; D8 already plans
    on it for F-Droid). It ships its own `libonnxruntime.so`, which **collides by name
    with sherpa's copy** in `jni/<abi>/`. Packaging needs `pickFirst`, and an exact
    version match is **impossible from Maven** (1.27.1 is not published; 1.27.0 is), so
    one side runs on an ORT a patch release away from the one it was built against.
    Both are API version 27, so it should load [est] — must be proven on device.
  - LiteRT (`.tflite` exports exist for all three files). A second ML runtime beside
    LiteRT-LM; no collision, but more APK weight.
  - A community Kotlin port (`Re-MENTIA/openwakeword-android-kt`, Apache-2.0, 18 stars,
    v0.1.3) runs the same three ONNX files on ORT. Useful as reference code, too young
    to depend on.
- The detector implements `com.hermesvox.wake.WakeWordEngine` (§5): feed 1,280-sample
  frames; mel → embedding (one vector per 80 ms) → phrase classifier over a window of
  recent embeddings (~1.3 s in upstream's models [est: from upstream code, verify]).

### 2.3 What accuracy to expect

Honestly: **unknown until measured.** Upstream's <0.5 FA/h is a target; the only
independent figure (8.5 FA/h) is a competitor's and on another phrase. Two things
pull our number down: the per-user verifier (§3), which upstream reports cuts false
accepts substantially, and Silero VAD gating (already in our app). One thing pulls it
up: the phone's own speaker — the being speaking "Hermes" — which the detector must
not hear (pause detection while TTS plays, as the voice loop already gates its own echo).

**Ship gate [proposal]:** ≤ 0.5 FA/h on our 24 h home-audio set with the verifier on,
≤ 5 % false rejects for the owner and ≤ 10 % for unenrolled speakers, measured at
arm's length and across a room.

---

## 3. Personalise to my voice

### 3.1 What the verifier needs

From upstream: ≥ 3 positive takes, ≥ ~10 s of the same person saying other things,
optionally ~5 s of their usual background, recorded in conditions close to where the
wake word will be used. The enrolment screen asks for **8 takes, 5 minimum**
(`EnrolmentPrompts`), each with a different prompt — normal, quiet, loud, fast, slow,
tired, bright, phone on the table — because the verifier has few parameters and one
reading eight times teaches it one point, not a region.

After the eight phrase takes the screen asks for two long takes (built):

| Take | Prompt | Kept if | Verifier role | Upstream floor |
|---|---|---|---|---|
| `speech.hvw` | talk naturally for 15 s about anything, not the phrase | 10–15 s, RMS ≥ −50 dBFS, ≤ 1 % clipped | same-speaker negatives: the owner's voice *not* calling it | ≥ ~10 s |
| `room.hvw` | stay quiet for 15 s where the wake word will be used | 5–15 s, RMS between −90 dBFS (dead mic) and −25 dBFS (someone talking) [est thresholds], ≤ 1 % clipped | background negatives | ~5 s per environment |

Both stop by themselves at 15 s, and each step on screen says why it is asked for.

**Verifier data requirements**, as the code now enforces them
(`EnrolmentStore.verifierMaterialComplete()`): ≥ 5 phrase takes, one free-speech take,
one room take. Still unmet and needed before a verifier can be trained:

- **A front-end to run them through.** The verifier is fitted on openWakeWord embeddings
  (or sherpa's, if §9 picks KWS) — no front-end runs in the app yet.
- **One environment only.** Upstream suggests background per environment; the screen
  records one room. A second room take is a cheap later addition if the first verifier
  overfits to it.
- **Upstream parity check.** Our on-phone logistic regression must be compared with
  upstream's `train_custom_verifier` on the same clips — and that comparison can only
  use clips the owner records *for that purpose*, off the phone, under separate consent
  (the enrolment consent covers on-device use only).

### 3.2 Where training happens

The verifier is a logistic regression over a fixed feature vector. Upstream trains
it with scikit-learn and pickles it — unusable on Android — but the model is a weight
vector and a bias. **Train it on the phone, in Kotlin**: run the takes through the same
mel + embedding front-end the detector uses, fit a regularised logistic regression on
~20 examples (milliseconds), store the weights encrypted next to the takes. Nothing
leaves the phone; no gateway consent is needed for this step. [est: straightforward;
verify parity against upstream's `train_custom_verifier` on the same clips.]

### 3.3 Where the audio lives (built)

- `noBackupFilesDir/wake/enrol/take-NN.hvw` — outside backup by construction;
  `allowBackup="false"` already covers the rest of the app.
- Each take: a small header (magic, format, index, sample rate) + PCM16, sealed with
  **AES-256-GCM under a dedicated Android Keystore key** (`hv_wake_enrol`, separate from
  the API-key alias). Written atomically (temp + rename).
- The two long takes are `speech.hvw` and `room.hvw` in the same directory, sealed the
  same way. Header format 2 carries the take kind as well as the index, so a file
  renamed into another slot does not decrypt into the wrong role. (Format-1 takes from
  the first preview build no longer load; delete-all and re-record.)
- **Consent** is a versioned file in the same directory. `save` refuses without it;
  consent given to older wording stops counting when `CONSENT_VERSION` is bumped. It is
  **version 2**: the wording now names the free-speech and room recordings.
- **Delete** removes every take, the consent, and **destroys the Keystore key**, so any
  ciphertext surviving a crash mid-delete is unreadable. Revoking consent is the same act.
- Only takes that pass `TakeMeasure` (0.5–4 s, RMS ≥ −50 dBFS, ≤ 1 % clipped samples)
  are kept, so the count on screen is a count of usable takes.
- **Sending to a gateway** is not implemented. When it is (§4b), it needs its own
  consent, per upload, naming the gateway — D6.

`PRIVACY.md` §1 describes the recordings as voice biometric data: encrypted on the
device in `noBackupFilesDir`, never uploaded or shared, used only for the wake word,
deletable from the same screen, and — in this version — not trained on anywhere. **When
on-device verifier training (§3.2) lands, that sentence must change in the same commit.**

### 3.4 Sharing with speaker-ID (#46, 1.1)

D6 says the verifier "is also the shared embedding front-end for speaker-ID". The
evidence says otherwise: openWakeWord's embedding is trained to recognise *what* was
said, and upstream explicitly says the verifier is not a speaker-verification model.
Meanwhile the pinned sherpa AAR already has `SpeakerEmbeddingExtractor` +
`SpeakerEmbeddingManager`, which run purpose-built speaker models.

What should be shared is **the enrolment, not the embedding**: the same consented,
encrypted takes (plus the ~15 s free-speech take, which speaker-ID needs anyway), the
same capture path, the same delete. Speaker-ID computes its own speaker embedding from
them with sherpa. One recording session, one consent, two models. (§8, decision 3.)

---

## 4. Arbitrary phrases ("Hey Torc")

Three ways, not two — the pinned AAR adds an option D6 did not list.

**(a) Streaming ASR + text match.** Run a streaming recogniser continuously and match
the phrase in its text. We have no streaming STT today (Whisper is offline; D5 is the
A/B that picks one). Cost: a full ASR model decoding every second of ambient audio —
an order of magnitude more compute than a keyword spotter [est], the worst battery
option, and it transcribes everything said near the phone, which is the wrong privacy
posture for an always-on feature. Matching is fuzzy, so false accepts depend on how
tolerant the matcher is.

**(a′) sherpa-onnx open-vocabulary KWS.** A 3.3 M-parameter streaming transducer that
decodes *only* the keywords in a text file. Any phrase is added by converting it to
tokens — no training, no audio leaves the phone, and the runtime is **already in the
APK**. The conversion is ours to do: the AAR takes BPE pieces, not text (§1), so
"Hey Hermes" can be tokenised once at build time, but a user's own phrase needs a
SentencePiece-BPE encoder in Kotlin over the model's `bpe.model` [est: ~100 lines, no
dependency] or tokenisation on the gateway. Cost: a ~5–17 MB model download, compute between openWakeWord and full ASR
[est], and accuracy that nobody has published — it must be measured. Names outside
the BPE vocabulary ("Torc") are spelled from sub-word pieces, which may weaken them.

**(b) Train an openWakeWord model on the user's gateway.** The base model is trained
from *synthetic TTS* of the phrase, not from the user's recordings — those can only
serve as verifier data and validation. So the gateway needs Python, PyTorch, Piper,
the 17 GB negative features and a GPU, and runs the §2.1 pipeline (hours [est]) per
phrase. Cost: heavy setup on every user's gateway, GPU hours, a model download back
to the phone, and — only if the user's takes are used for validation — an upload of
biometric audio under explicit consent. Best accuracy ceiling of the three, because
it is a dedicated model with the verifier on top.

**What to build first: (a′).** It costs no new dependency, no training, and no data
leaving the phone, and it can be evaluated on the same eval set as "Hey Hermes" in a
day. If its false-accept rate on a user phrase is acceptable *with the verifier on
top* (the verifier works the same way over any front-end's positives), (b) is not
needed for most users. Build (b) only if (a′) fails the ship gate, and then as an
opt-in gateway job. Do not build (a) as a wake path: it is (a′) with worse cost and
worse privacy.

It is worth measuring (a′) on "Hey Hermes" too: if it is close to openWakeWord, the
product needs one detector instead of two.

---

## 5. The seam (built)

```kotlin
interface WakeWordEngine {            // com.hermesvox.wake
    val id: String; val available: Boolean
    fun process(frame: ShortArray): WakeDetection?   // 1,280 samples, 16 kHz PCM16
    fun reset(); fun release()
}
object InertWakeWordEngine : WakeWordEngine        // never fires; the only engine today
```

The engine never opens the microphone: its caller owns capture, so "is anything
listening?" has one answer in one place. The future chain behind `process`:

```
frame (80 ms) ─► melspectrogram ─► embedding (shared, frozen) ─► phrase model ─┐
                                                                               ▼
                    score > custom_verifier_threshold ? per-user verifier : base score
                                                                               ▼
                 threshold + refractory (≥ 2 s) + VAD-gated ─► WakeDetection
```

An `OwwWakeWordEngine` (openWakeWord) and a `KwsWakeWordEngine` (sherpa) both fit it.

---

## 6. The always-on service

Nothing below is built. These are the constraints the listener must meet and the
measurements that gate shipping it.

**Service type and start.** A separate `WakeService` with
`foregroundServiceType="microphone"` (permissions already declared), not a mode of
`VoiceService`, so a stuck listener cannot keep the conversation pipeline alive. It can
only be started while an activity is visible or from its own notification action, and
**not at boot** (Android 14+). "Always on" therefore means *on since you last opened
the app or tapped the notification*; after a reboot the notification has to be tapped
once. Say so in the UI. A persistent notification is mandatory, and Android 12+ shows
the green microphone indicator the whole time — a design fact, not a bug to hide.

**No DSP offload.** The low-power hotword path needs `CAPTURE_AUDIO_HOTWORD`, held only
by system apps or the default-assistant role. Our listener runs on the application
processor, so it keeps a CPU core waking every 80 ms. (Becoming the default assistant
via `VoiceInteractionService` is the only route to offload; out of scope.)

**One owner of the mic.** When the wake fires, the listener releases its `AudioRecord`
and hands over to the voice loop, which re-acquires; it resumes when the conversation
ends. Pause detection while TTS plays (the being says "Hermes"). Watch
`AudioManager.registerAudioRecordingCallback` for `isClientSilenced` (Android 10+
concurrent-capture policy: calls and assistants win) and stop during phone calls.

**Thermal.** Read `PowerManager.getThermalHeadroom()`/thermal status; pause listening at
`THERMAL_STATUS_MODERATE` and above, with a notification line saying why.

**Battery — must be measured, no published number applies.** Per phone (Pixel 9 and
the low-end test phone), screen off, 4 h each:

| Measurement | How | Proposed budget |
|---|---|---|
| Battery drain per hour, listener on vs off | `dumpsys batterystats` + Perfetto power rails (ODPM on Pixel) | ≤ 1 %/h above baseline |
| CPU per frame, p50/p95 | trace around `process()` | p95 < 20 ms of the 80 ms frame |
| Wake-to-listening latency | log timestamp of detection → voice loop capturing | ≤ 500 ms |
| False accepts/hour | 24 h of real household audio (TV, podcasts, conversation, music), screen off | ≤ 0.5/h with verifier |
| False rejects | 50 takes owner + 50 from 3 other speakers, arm's length and across a room, quiet and with TV on | ≤ 5 % owner, ≤ 10 % others |
| Thermal over 2 h | thermal status trace | never reaches MODERATE from the listener alone |
| Survival | Doze, app swipe-away, low-memory kill, mic taken by a call | resumes or tells the user |

All seven go in a release note's evidence section before the toggle exists.

---

## 7. What was built and what was not

Built: `com.hermesvox.wake` — `WakeWordEngine` + `InertWakeWordEngine`,
`EnrolmentPrompts` (eight phrase prompts + the free-speech and room steps), `TakeKind`,
`TakeMeasure` (per-kind rules), `EnrolmentStore` (+ `TakeCipher`, `KeystoreTakeCipher`);
`WakeEnrolActivity` reached from Settings → *Wake word (preview)*; the `PRIVACY.md`
paragraph. 33 unit tests (`EnrolmentStoreTest`, `EnrolmentTest`) covering consent
refusal, per-kind measurement, replace-on-redo, encryption at rest for every kind, kind
labels, key destruction, versioned consent, verifier-material completeness, delete-all
and the inert engine.

Not built yet:

- any inference dependency or model file — no detector runs (§9 says what each costs);
- verifier training, and therefore any use of the recordings at all;
- a second room/environment take;
- any service, background capture or wake toggle (§6), and none of §6's measurements;
- an evaluation set for "Hey Hermes" (positives from several speakers, adversarial
  phrases, 24 h of household audio) — the prerequisite for every accuracy claim;
- gateway upload of any recording (would need its own per-upload consent, D6);
- any training of a "Hey Hermes" base model — blocked on §8 decision 1.

---

## 8. Decisions for the owner

1. **Provenance of the "Hey Hermes" weights — open, and nothing is trained until it
   is answered.** Train on upstream's precomputed negatives (ACAV100M/AudioSet/FMA:
   fastest, matches upstream, but those are the datasets that made upstream license its
   models CC BY-NC-SA), or build a negative set from clearly licensed corpora (e.g.
   Common Voice CC0, LibriSpeech, MUSAN) at the cost of days of pipeline work. This
   decides what licence the shipped model can carry in a GPL-3.0, F-Droid app.
   The question to answer: *which corpora may a model shipped in this app be trained
   on, and does the Piper voice used for synthetic positives (`libritts_r`) pass the
   same test?* A related one: the Hermes agent install already carries a
   `hey_hermes.tflite` (§9.3) whose README asserts redistribution is permitted but does
   not name its training data — can it be used, or must it be retrained?
2. **Detector order and runtime.** Measure sherpa KWS (no new dependency) against
   openWakeWord on "Hey Hermes" *before* committing to openWakeWord's runtime — and if
   openWakeWord wins, choose ORT-Java (resolve the `libonnxruntime.so` collision with
   sherpa; an exact 1.27.1 match is not on Maven, §9) or LiteRT. This amends
   D6.1/D6.3 from "openWakeWord then pick" to "measure both, ship the better". The
   measurement needs the KWS model downloaded and an eval set recorded (§9.4).
3. **Amend D6.2:** speaker-ID shares the *enrolment* (takes, consent, deletion), not the
   openWakeWord embedding; speaker-ID uses sherpa's `SpeakerEmbeddingExtractor`. And
   confirm the proposed ship gates (≤ 0.5 FA/h, ≤ 5 % FRR, ≤ 1 %/h battery).

---

## 9. Detector spike: sherpa KWS vs openWakeWord (2026-10-19)

The question: is sherpa's keyword spotter good enough on "Hey Hermes" that the app needs
no second runtime? Bounded to what this checkout and this machine can show **offline,
without adding a dependency**.

### 9.1 Side by side

| | sherpa-onnx KWS | openWakeWord |
|---|---|---|
| Runtime in the app today | **Yes** — `KeywordSpotter` + JNI in all four ABIs of the pinned 1.13.6 AAR [ours] | **No** — needs ORT-Java or LiteRT; LiteRT-LM has no general interpreter [ours] |
| New dependency to run it | none | `onnxruntime-android` 1.27.0 (44.5 MB AAR, collides on `libonnxruntime.so`; 1.27.1 not on Maven) [ours], or LiteRT for the `.tflite` exports |
| APK cost | 0 (model is a download) | with `pickFirst` keeping sherpa's ORT: ~0.1 MB JNI/ABI + 121 KB classes [ours]; without dedup it cannot package |
| Model download | `kws-zipformer-gigaspeech-3.3M`: encoder 4.6 MB int8 (12 MB fp32) + decoder/joiner/tokens [src] | 2.4 MB shared front-end + ~0.2–1.3 MB per phrase [ours] |
| New phrase | edit a keywords file; **needs BPE tokenisation** (build-time for "Hey Hermes", a Kotlin encoder for user phrases) [ours] | train a model per phrase (§2.1, hours on a GPU [est]) |
| Training-data licence question | pretrained ASR model, GigaSpeech — licence of the weights **not yet checked** | the open §8 decision 1 |
| Compute per 80 ms | **not measured** (no model on disk) | **2.1 ms p50 on one desktop core** (§9.2) |
| Accuracy on "Hey Hermes" | **unknown** — none published, none measured | **unknown** — no "Hey Hermes" model could be run, no eval set exists |
| Feeds the verifier | yes, if the verifier is fitted on its features instead — unbuilt | yes, upstream's design |

### 9.2 What was run

openWakeWord's chain on host ONNX Runtime 1.31.0, one thread pinned to one core of a
Ryzen 5 3600X, 2,000 timed runs each, noise input (compute, not accuracy). Inputs follow
upstream's streaming shapes: mel over 1,760 samples → (1,1,8,32); embedding over 76 mel
frames → 96-d; classifier over 16 embeddings.

| Stage | p50 | p95 |
|---|---|---|
| melspectrogram | 0.125 ms | 0.141 ms |
| embedding | 1.950 ms | 2.421 ms |
| phrase classifier (a 205 KB `dnn` model for another phrase, as a stand-in) | 0.025 ms | 0.027 ms |
| **chain** | **2.10 ms per 80 ms frame (2.6 % of one core)** | |

Files: `melspectrogram.onnx` (1,087,958 B, sha256 `ba2b0e0f…f176f`) and
`embedding_model.onnx` (1,328,103 B, `ba754db3…e51`), the same sizes as upstream's
v0.5.1 release assets; they came from a local scratch copy, so check the hashes against
the release before reusing the number. The embedding model is 93 % of the cost.
Projection to a phone little core (several times slower [est]) puts it at roughly
8–17 ms, inside §6's 20 ms p95 budget but not by much — measure on the Pixel and the
low-end phone.

**Not run, and why:**

- **sherpa KWS** — no KWS model exists on this machine, and the AAR's libraries are
  Android-only (bionic), so it can run only in the emulator (`vox16k` AVD exists) or on a
  phone, and only after downloading the model.
- **"Hey Hermes" on openWakeWord** — the only "Hey Hermes" model on the machine is a
  `.tflite` (§9.3); no TFLite runtime is installed here and none is in the app.
- **Accuracy of either** — there is no labelled "Hey Hermes" audio and no household
  negative set in the repo. The enrolment recordings are consented for on-device use
  by this feature only; they are not an eval set.

### 9.3 Found on the machine

`~/.hermes/hermes-agent/tools/wakewords/hey_hermes.tflite` (206,764 B), bundled with the
Hermes agent for its desktop wake word via rhasspy's `pyopen-wakeword`. Its README says
it was trained with the openWakeWord pipeline on synthetic TTS speech and that
"redistribution is permitted under the openWakeWord license" — but openWakeWord has two
licences (Apache-2.0 code, CC BY-NC-SA for its own models, §1), neither settles a model
someone else trained, and the README does not name the negative data. It was
not run and is not used. If its provenance is settled it would save a training run and
gives a ready baseline for §9.4; until then it is in the §8 decision 1 bucket.

### 9.4 What settling it would take, and cost

Neither detector's **accuracy** can be settled offline here. The minimum to settle it:

1. **An eval set** (owner's job to approve; §6 lists it): ~50 "Hey Hermes" takes from
   the owner and ~50 from three other people, the adversarial phrases from §2.1, and
   24 h of household audio. Recorded for evaluation, with consent from each speaker.
2. **sherpa KWS:** download the model (~5–17 MB, the existing `ModelCatalog` path),
   tokenise "Hey Hermes" once, run it through `KeywordSpotter` in the emulator or on a
   phone over the eval set at a few `keywordsThreshold` values. **No new dependency;
   ~1 day.**
3. **openWakeWord:** either clear `hey_hermes.tflite`'s provenance or train one
   (§2.1, blocked on §8 decision 1), and run it — off-device with host ORT/TFLite for
   accuracy (no app dependency), but **shipping** it costs the ORT-Java (or LiteRT)
   dependency and an on-device proof that sherpa and ORT-Java share one
   `libonnxruntime.so`. **~1 day to measure once a model exists; the dependency
   decision on top.**

**Recommendation:** do step 2 first. It is the only path that needs no new dependency
and no licence answer. If sherpa KWS meets the §2.3 ship gate with the verifier on top,
openWakeWord — and its runtime decision — is not needed at all.

---

## Sources

- openWakeWord README — https://github.com/dscripka/openWakeWord
- Custom verifier models — https://github.com/dscripka/openWakeWord/blob/main/docs/custom_verifier_models.md
- `hey_jarvis` model card — https://github.com/dscripka/openWakeWord/blob/main/docs/models/hey_jarvis.md
- Training notebook — https://github.com/dscripka/openWakeWord/blob/main/notebooks/automatic_model_training.ipynb
- Release v0.5.1 assets — https://github.com/dscripka/openWakeWord/releases/tag/v0.5.1
- Precomputed features — https://huggingface.co/datasets/davidscripka/openwakeword_features
- LiveKit wake-word comparison — https://github.com/livekit/livekit-wakeword , https://livekit.com/blog/livekit-wakeword
- openwakeword-android-kt — https://github.com/Re-MENTIA/openwakeword-android-kt
- sherpa-onnx KWS — https://k2-fsa.github.io/sherpa/onnx/kws/index.html , pretrained models: https://k2-fsa.github.io/sherpa/onnx/kws/pretrained_models/index.html
- sherpa-onnx 1.13.6 source (keyword encoding: `sherpa-onnx/csrc/utils.cc`, `csrc/keyword-spotter.h`) — https://github.com/k2-fsa/sherpa-onnx/tree/v1.13.6
- ONNX Runtime Android on Maven Central — https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/maven-metadata.xml
- FGS background-start restrictions — https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- Android 15 behaviour changes (BOOT_COMPLETED + FGS types) — https://developer.android.com/about/versions/15/behavior-changes-15
- `CAPTURE_AUDIO_HOTWORD` — AOSP `frameworks/base/core/res/AndroidManifest.xml`
