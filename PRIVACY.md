# What leaves your phone, and what does not

Hermes Vox is the Android voice client for Hermes, an agent that runs on a gateway
you operate. This page lists every destination the app can send data to, what it
keeps on the phone, how long it keeps it, and what the agent can read through the
phone tool surface. It describes what the code in this repository does. Where the
app cannot guarantee something, the limit is named rather than softened.

You need an Android phone (Android 7.0 or newer), a Hermes gateway you can reach,
and the gateway's API key. Speech models are optional downloads; without one, the
phone's own recogniser is used, which is the one exception described below.

## The parts

- **The gateway** is the Hermes server you configure. It holds the agent.
- **The agent** is the mind on the gateway: it answers, remembers and runs tools.
- **A profile** is a saved gateway connection — address, key and model — that you can
  switch between.
- **A call** is one open session between the app and the agent: the microphone is
  live, the turn is sent, the reply is spoken.
- **On-device speech** is voice-activity detection (Silero VAD, which decides when
  you start and stop talking), speech-to-text (Whisper, Parakeet or Nemotron) and
  text-to-speech (Supertonic, Piper or the phone's system engine).
- **Presence** is the optional on-device layer that phrases short conversational
  asides with a local Gemma model. It sits behind the Alpha gate (the switch that
  enables preview features), which is off on a fresh install.
- **Phone tools** are the actions the agent can take on this phone through a small
  server the app runs on the tailnet (a private network such as Tailscale) while a
  call is open.
- **An approval** is the card that asks you to allow one phone action. Silence is
  never a yes.

## What is sent, and where

| Destination | What is sent | When |
|---|---|---|
| **Your gateway** | the turn's text — your transcribed speech or what you typed — with the gateway key in an `Authorization: Bearer` header; a session-key header when you set one; the answer to an approval; requests for the model catalogue, health, and session list; a fetch of the profile's `VOX.md` | every turn and every screen that talks to the gateway |
| **A remote speech server**, if you configure one | the recorded audio as a WAV file, the model name, and the key you saved for it if any | only when the speech-to-text backend is set to Remote **and** a URL is saved |
| **A model host**, when you install a model | a download request for one model file | only when you start that download |
| **Your phone's own recogniser**, when the app falls back to it | the audio of the utterance | every turn until an on-device model is installed, or whenever the backend is set to Platform |
| **The agent, over the tailnet** | the result of a phone-tool call, and the bytes of a file it fetches from an offer | while a call is open and the action's group is not Off |

The app sends **text**, not audio, to the gateway. Speech recognition and speech
synthesis run on the phone when a model is installed. The gateway is the only place
the conversation is kept: the app does not save a transcript of its own.

### The recognition exception

The speech-to-text setting has three backends: **on-device** (the default),
**Platform (Google)**, and **Remote (server)**. The default is on-device, but the
app cannot use a model that is not installed. With the on-device backend selected
and no model on the phone, the app falls back to the platform recogniser
(`resolveSttLeg` in `VoiceController.kt`; `Settings` labels it "Platform (Google)").
That recogniser belongs to the phone's maker, and its own policy decides whether the
audio leaves the device. The app neither sees nor controls that decision.

Two consequences, stated plainly:

- Install an on-device speech model to keep recognition on the phone.
- A blank remote URL never reaches the network. The remote leg is used only when the
  backend is Remote and the URL is non-blank; otherwise the app falls back to the
  on-device model, or to the platform recogniser.

Speech synthesis has the same shape: the chosen on-device voice speaks when it is
installed, and the phone's system TTS engine speaks when it is not.

## What stays on the phone

- **Microphone audio**, except when the platform recogniser or a remote speech
  server is in use, as above.
- **Recognition and synthesis**, when the models are installed: Silero VAD, Whisper,
  Parakeet and Nemotron for hearing; Supertonic and Piper for speaking.
- **Presence**, including the optional Gemma model. It runs on the phone, has no
  tool access, and is downloaded only if you choose to install it.
- **The avatar** and the call's visual state.

## What is stored, and for how long

| What | Where | How long |
|---|---|---|
| Connection settings: gateway address, model and voice choices, profile list, speech backend, Alpha-gate choices | the app's private `hv` preferences | until you change them, clear the app's data, or uninstall |
| The gateway key, the phone-MCP token (the credential the gateway presents to the phone's tool server), and the optional remote speech-server key | the `hv` preferences, as AES/GCM ciphertext (authenticated encryption) from an Android Keystore key (the phone's key store) — `SecureStore` uses `hv_api_key` for the gateway key and `hv_phone_mcp` for the phone token | until replaced, cleared or uninstalled |
| A secret the Keystore refused to encrypt | the same preferences, as plaintext; the failure is logged and the encryption is retried on the next start | until a later start encrypts it, or the value is cleared |
| Downloaded models | `filesDir/models/<id>/` | until you delete the model, clear the app's data, or uninstall |
| The profile's `VOX.md` mirror | `filesDir/vox/<profile>/VOX.md` | until refetched, cleared or uninstalled |
| The debug log | `filesDir/logs/hermes-vox.log`, capped at 5 MB with one rotated copy | the most recent 5 MB in each of two files, unless you turn on the verbose log, which never prunes |
| The crash log | `filesDir/vox_crash.log` | the last 9,000 characters |
| The phone tool audit ("Recent actions") | the `hv` preferences | the last 50 calls, including refusals |
| Wake-word enrolment takes, if you record any | `noBackupFilesDir/wake/enrol/`, encrypted with an AES-GCM key held in the Android Keystore, plus a consent record | until you use "Delete all my recordings", clear the app's data, or uninstall |
| A file being offered to the agent | `filesDir/file-offer/` | deleted after the single transfer, or when the five-minute offer expires |

**The logs.** The app writes diagnostics to the local log and to logcat: event
names, counts, durations and error classes. Transcript text, tool arguments, tool
outputs and keys are not written by default. The one exception is the
**Log spoken transcript** setting, which is off by default; while it is on, the words the app
heard and the reply text are written to the local log. The phone tool audit is
separate and summarised: a note's text is stored as a character count, and a
notification, contact or app listing stores a count rather than the contents.

**The wake word.** Enrolment records short takes of your voice only after you agree
to the consent text on the screen. The recordings are encrypted, kept outside
Android backup, and are never uploaded. Nothing in this build listens for the wake
word: the only detector implementation is inert, and the enrolment screen says
"not listening". Deleting the recordings removes them and destroys their key, so any
copy left behind cannot be read.

**Android backup is off.** The manifest sets `allowBackup="false"`, so the app's
private storage is not copied to Android's cloud backup. The manifest declares no
device-transfer extraction rules; a transfer performed by the phone's own migration
tool is outside what the app can control.

## What the phone tool surface can read, and when

The phone tool surface is a server the app runs while a call is open. It binds only
to the phone's tailnet address, answers only the `/mcp` path, and requires a bearer
credential on every request. It is off when no call is open and when the phone has no
tailnet address.

Every capability group starts **Off**. Each group has exactly three states:

- **Off** — the group's actions are absent from the schema the agent sees, and a
  direct call is refused `not_granted`.
- **Ask** — the action is offered; a call raises a card naming the action and its
  arguments. Nothing runs until you answer. The card is good for two minutes, at most
  three cards are open at once — one on screen and two behind it — and a yes from a
  locked phone is refused.
- **Allow** — the action runs without a card. The screen says what that means before
  you choose it.

| Group | What the agent can do or read | Default |
|---|---|---|
| Timers and alarms | set, cancel and check countdown timers and alarms | Off |
| Do not disturb and volume | turn Do Not Disturb on or off; change the media volume | Off |
| Reminders and notes | keep a short note and read it back | Off |
| Media | play, pause, skip, and ask what is playing | Off |
| Device state | read battery, charging, connectivity, screen and storage state | Off |
| Clipboard | write text; read the current clip | Off |
| Files | list, read and offer files inside the one folder you granted | Off |
| Calendar | read today's events and create an event, with an undo | Off |
| Notifications | read the notifications on the phone while a call is open; dismiss one or all of an app's | Off |
| Messages | write a text and open your messaging app; you press send | Off |
| Calls | open the dialer with a number ready; you press call | Off |
| Apps | list the apps a person can open; open an app or a link; remove an app, which Android then confirms | Off |
| Contacts | look up a contact's number by name | Off |

The read groups are narrower than the action names suggest:

- **Notifications** are read only while a call is open, and only if you granted
  notification access on Android's own Settings screen. What the agent receives is
  the notification's content; the audit keeps a count, not the content.
- **Clipboard** reads are allowed by Android 10 and newer only for the app in front
  (or the default keyboard). The app asks the platform and reports the answer; when
  nothing comes back and Vox is not in front, it says that focus is the likely reason
  rather than claiming there was nothing there. An Allow for this group means "read
  without asking while Vox is in front": it can include a password or a code.
- **Files** are limited to the folder you pick with Android's folder picker. The app
  declares no storage permission; it reads through that grant. One read returns at
  most 64 KiB. An offer copies the file into private staging and serves it once over
  the tailnet; nothing is uploaded.
- **Contacts** returns names, numbers and labels, never notes, addresses, photos or
  email.
- **Apps** lists what a person can open (launcher entries), not every installed
  package. Recent usage is visible only if you grant usage access on Android's
  special-access screen.
- **Messages** and **Calls** hand a draft to your messaging app or dialer. The app
  does not send the message or place the call; you do.
- **Notifications**, **contacts** and **apps** are the groups the bulk "allow instant
  actions" helper deliberately skips; each stays a decision of its own.

Every call the agent makes, including every refusal, is recorded in Recent actions.
The entry contains the time, the action, a summary of the arguments, the outcome and
any undo id. It never contains a token, a message body or a file's contents.

## What nothing ever sends

- **No analytics, telemetry or advertising SDK.** The shipped dependency list is
  AndroidX, Kotlin and its coroutines, Apache Commons Compress, the sherpa-onnx runtime
  and the LiteRT-LM runtime. There is no Firebase, Crashlytics, Sentry, analytics or ad
  library in the tree.
- **No crash upload.** A crash is written to the local file and nowhere else. You
  can export the log yourself; the app never sends it.
- **No account, name, email, phone number, contacts, location, device identifier or
  advertising identifier goes to the project.** The project operates no server to
  receive it.
- **No audio in a turn.** A turn carries text. Audio leaves the phone only through the
  platform recogniser or a remote speech server you configured.
- **No file upload.** An offered file is fetched by the agent from the phone over the
  tailnet; the app does not push it anywhere.
- **No model upload.** Model files are downloaded; nothing is sent back.

These statements are about the app and the project. A destination you choose — your
gateway, a remote speech server, a model host — sees the request you or the app sent
it, under that party's own policy.

## Contact

Questions about this document and the app's own data can go to the repository
(`github.com/chezgoulet/hermes-vox`) or to `chris@coveredbridgecookies.com`.
Questions about what your gateway stores, logs or forwards belong to the person who
operates that gateway — usually you.

## What it cannot do

- It cannot control what your gateway logs, retains, or forwards to a model provider.
  That data belongs to the gateway you operate.
- It cannot make the platform recogniser local. Only an installed on-device model
  does that.
- It cannot encrypt a secret if the Android Keystore refuses; it keeps the value in
  plaintext, logs the failure, and retries on the next start.
- It cannot protect data from a rooted or otherwise compromised operating system.
- It cannot hide a model download from the host serving it. The host sees the
  request, the time and the network address.
- On Android 10 and newer it cannot read the clipboard while it is not the app in
  front; older versions allow the read. It cannot make Android grant notification,
  usage or Do Not Disturb access.
- It cannot keep more than the last 50 phone-tool calls, and it keeps those as
  summaries rather than a full record.
- It cannot delete anything on your gateway. Removing the app removes the phone's
  copy; the gateway's copy is yours to manage.
- It cannot promise that a third-party keyboard, launcher or Android build does not
  observe what it draws or what you type.
