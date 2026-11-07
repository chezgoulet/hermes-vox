# The wake word: saying "Hey Hermes"

The wake word is meant to open the app hands-free: you say **"Hey Hermes"** and the call starts
without touching the phone. The app today records and stores the material a personal detector
would learn from — your takes of the phrase, a sample of you talking normally, and a sample of your
room — and **no detector is installed, so nothing listens and no phrase wakes the app.** The
enrolment screen is the whole feature in this build.

## What you need

- An Android phone and the microphone permission. The permission is requested when you first press
  Record, not at install.
- **Alpha features** on in Settings, and the **Wake word** set selected. Alpha features is one
  master switch immediately above the alpha block at the end of Settings, off by default; a set
  behind it runs only when the master is on and that set is selected. The wake-word row is dimmed
  and its screen refuses to open while the set is off.
- Nothing else: no model download, no account, no gateway setting. The recordings stay on the
  phone.

## Turning it on

Settings lists one **alpha block** under the master switch. The **Wake word** row belongs to it.
Selecting the set makes the row open the enrolment screen; turning the master off, or deselecting
the set, stops the wake word **listening**, not merely its row. The capture path asks the same
question the row is dimmed by (`WakeListening.listening`, which reads
`AlphaGate.allows(prefs, FeatureSet.WAKE_WORD)`), so a stale screen or a queued permission result
cannot open the microphone after the set has been switched off.

With the set off the screen records nothing and says why:

> Alpha features are off for the wake word — select Wake word under Alpha features above.

The Settings row reports the state without a promise: `N of 8 takes · not listening`.

## What enrolment records

The screen walks through ten steps in order. Eight are takes of the phrase, each with a different
prompt, because a verifier given one reading eight times learns one point rather than the range of
how you actually say it:

1. Your normal voice, phone at arm's length
2. Quietly, as if someone nearby is asleep
3. Loudly, as if from across the room
4. Quickly, running the words together
5. Slowly and clearly
6. Low and tired, end-of-day voice
7. Bright, with a smile
8. Phone on the table, you a step away

Then two long takes: **free speech** — talk naturally for 15 seconds about anything except the
phrase — and **room** — stay quiet for 15 seconds where the wake word will usually be used. Both
stop by themselves at 15 seconds. The screen says what each is for: free speech teaches the
detector what your voice sounds like when you are *not* calling it, and the room teaches it what
to ignore.

Every take is measured before it is kept, so the count on screen is a count of usable takes:

| take | length kept | level kept | rejected when |
|---|---|---|---|
| phrase | 0.5–4 s | RMS ≥ −50 dBFS | too short, too long, too quiet, more than 1% clipped samples |
| free speech | 10–15 s | RMS ≥ −50 dBFS | too short, too long, too quiet, more than 1% clipped |
| room | 5–15 s | RMS between −90 and −25 dBFS | dead silent (muted or covered mic), too loud for a quiet room, more than 1% clipped |

Five phrase takes is the minimum for the material to count as complete; eight are offered. A take
is recorded as 16 kHz mono PCM16 from the `VOICE_RECOGNITION` source, the capture an idle wake
listener would use with no playback to cancel. Pausing the screen during a take discards it. A
phrase take can be recorded again, replacing the slot; each long take has one slot and recording it
again replaces it.

## Where the recordings live

- Directory: `noBackupFilesDir/wake/enrol`, outside Android's backup by construction. The app is
  also `allowBackup="false"` everywhere.
- Names: `take-00.hvw` … `take-07.hvw`, `speech.hvw`, `room.hvw`, plus a `consent` file.
- Each file is sealed with **AES-256-GCM under a dedicated Android Keystore key** (`hv_wake_enrol`,
  separate from the API-key alias) before it touches disk, and written atomically (temporary file,
  then rename). The sealed header carries the format version, the take kind, the slot index and the
  sample rate, so a file renamed into another slot does not decrypt into the wrong role. Format-1
  phrase-only takes do not load; delete all and record again.
- **Consent** is versioned. The current version is **2**, whose wording names the free-speech and
  room recordings as well as the phrase takes. `save` refuses without a current consent, and
  consent given to older wording stops counting when the version is bumped.
- **Delete all** removes every take and the consent file and destroys the Keystore key, so any
  ciphertext that survives a crash mid-delete is unreadable. Revoking consent is the same act. The
  confirmation says exactly that, including that you can record again later.

## How it is trained and installed

**It is not, yet.** Nothing is trained and nothing listens. There is no training step, no wake-word
model and no code that feeds a detector. The only engine is `InertWakeWordEngine`: it reports
`available = false` and returns no detection for every frame. The wake-word entry is absent from the
Models screen, and the APK ships no detector graph.

What exists is the seam and the material:

- `WakeWordEngine` takes 16 kHz mono PCM16 in 80 ms frames (1,280 samples) and returns at most one
  detection per frame, with a score in 0..1. The caller owns the microphone; the engine never opens
  audio itself.
- `EnrolmentStore.verifierMaterialComplete()` reports true when five phrase takes, the free-speech
  take and the room take are all present. That is a statement about the recordings, not about a
  model: nothing reads them back for training.
- The pieces a detector would need — a wake-word front end that turns audio into features, a model
  that scores the phrase, a threshold and a refractory rule, and the verifier fitted on the owner's
  takes — are not in this build. The screen's "enough to personalise" means the recordings are
  complete, not that personalisation has happened.

Nothing in the app starts a service, opens the microphone in the background, or offers a wake
toggle. The section below lists what that leaves undone.

## What it costs

- **Time:** about a minute and a half of recording across the ten steps, plus reviewing each take.
- **Storage:** about 2 MB at most — eight phrase takes of at most 4 s plus two 15 s takes at
  32 KB/s — in the app's private, non-backed-up storage.
- **Memory and battery:** nothing. No engine is resident, no service runs, and nothing in the
  wake-word feature opens the microphone outside the enrolment screen.
- **Network:** nothing. No recording, model or consent leaves the phone.

## What it writes, sends or changes

- Writes the ten sealed take files and the versioned consent file described above.
- Changes the wake-word Settings row's state and the enrolment screen's count.
- Sends nothing. There is no upload path in this build; a gateway upload of the recordings would
  need its own per-upload consent naming the gateway, and no such path exists.

## What it cannot do

- It cannot wake the app. No detector runs, so saying "Hey Hermes" does nothing.
- It cannot listen in the background. There is no wake service, no always-on capture and no
  boot-time start.
- It cannot use the recordings. Nothing trains from them; a take's only effect is to fill a slot.
- It cannot recognise the owner. There is no per-user verifier and no speaker-identification model;
  the free-speech take is stored for a detector that does not exist.
- It cannot accept a phrase of your choosing. The prompt is fixed to "Hey Hermes".
- It cannot record without consent and the alpha set. Both are checked before the microphone opens,
  and the screen says which one is missing.
- It cannot keep a take that fails its measurement, and cannot keep a take recorded against older
  consent wording or an older file format.
- It cannot be backed up, exported, shared or uploaded. The files are excluded from backup and no
  code path reads them for anything except the enrolment screen.
- It cannot report accuracy: there is no evaluation set — no labelled household audio, no
  multi-speaker takes — and no false-accept or false-reject figure has been measured.
- It cannot be mistaken for the call's hearing. Presence's microphone is part of a live call and is
  a different capture path; the wake word has no microphone of its own.

The detector research and the evidence a ship decision would need are kept as a record in
`docs/WAKE-WORD-RESEARCH.md`; the wake-word decision they inform is D6 in `docs/DECISIONS.md`.
None of it is installed.
