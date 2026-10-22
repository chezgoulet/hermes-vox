# State audit — which surface carries which state (#280)

Set 2026-10-21 for 0.9.1. Feeds #281 (operations) and #282 (approvals); #283 is closed (D26).

The being was drawn to show that work is happening and what state the agent is in. Its vocabulary
grew by accretion (`MotionState`, the cues, the status pill, the console). This audit lists every
state a call can be in, what carries it **today**, and what must carry it in 0.9.1. The test for each
row is the issue's: it must hold **with the being off, with sound off, and in light mode**.

## What exists today, read from the code

| Surface | Where | What it can say |
|---|---|---|
| The being | `AvatarView` driven by `MotionState` (`LISTENING`, `THINKING`, `SPEAKING`, `STALL`, `RECOIL`, `TOOL`, `TOOL_RESULT`, `SETTLE`, `IDLE`, `DRIFT`) | shape, motion, colour — never *what* |
| Cues | `CueSynth.Cue` (`LISTENING`, `THINKING`, `REPLY_READY`, `ERROR`) via `CuePolicy` | an edge, as sound or haptic |
| Status pill | `R.id.status`, `MainActivity.setStatus` | words — but shown only while **warming** and on an **error**; for listening / thinking / speaking it is set and then hidden |
| Heard line | `R.id.heard_line` | the exact words sent, fading |
| Reply crawl / conversation | `R.id.reply_crawl`, `R.id.conversation` | the reply itself |
| Stream console | `R.id.stream` (dev console only) | `◆ tool: <name> <args>` and `◆ tool · <output>` — developer text, off by default |
| Notification | `VoiceService` | a fixed "Listening for the entity…" whatever the state |

So today **a tool running, a failed step and a long job are carried by the being alone** — a motif
and a shimmer that do not say which tool, on what, or that it broke. With the being in the black box
of light mode (#275), or with the phone in a pocket, nothing carries them.

## The table

"Words" means the **state line**: the status pill, kept visible for the length of a call, carrying
one line of plain language. It is the explicit UI surface; the being and the cues are the ambient
ones. A row that says *both* means each must be enough alone.

| State | Being | Sound | Words (state line) | Notification | Why |
|---|---|---|---|---|---|
| Connecting / warming | — | — | `Waking the voice…` (exists) | — | before a call; the being is not live yet |
| Listening | `LISTENING` breath | `LISTENING` cue on the edge | `Listening` | `On a call · listening` | the most frequent state: the being carries it, the word is there for being-off |
| The user speaking | `LISTENING` + level | — | the heard line, after | — | the user knows they are speaking; the heard line confirms what was heard |
| Thinking | `THINKING` | `THINKING` cue on the edge | `Working…` | `Working…` | ambient is enough while short; the word covers being-off |
| A tool running | `TOOL` motif | — | **`Checking your calendar · today`** — which tool, in plain language, and on what | same line | **the being cannot say *what*.** This is #281 |
| A long job | `STALL` drift | — | `… · still working, 40 s` — the elapsed time, after 8 s | same line | a spinner is a hope; a counter is proof it is moving |
| A step failed | `TOOL_RESULT`, then rest | `ERROR` cue | **`Couldn't send the email — <reason>`**, held until the next turn | same line | a failure must be readable after the fact, not a shimmer |
| A step done | `TOOL_RESULT` shimmer | — | `Checked your calendar` (past tense), until the next step or the reply | — | the closure of the step, without asserting the world changed (D26) |
| Speaking | `SPEAKING` waveform | the voice | `Speaking` | `On a call · speaking` | the voice is the signal; the word is for sound-off |
| Interrupted | `RECOIL` | — | `You interrupted` (exists) | — | |
| Waiting on the user | `LISTENING` | `LISTENING` cue | `Listening` | as listening | in a voice call "the agent asked you something" is the reply plus an open mic; no extra state is honest |
| Approval needed | **cannot** — no motion means "say yes or no to *this*" | a distinct cue | **a card: what, why, and what yes and no do** | the same, as an action | #282; **blocked upstream** — `/v1/responses` emits no `approval.request` (`docs/NOTES-api-events.md`) |
| Done (the reply landed) | `SETTLE` | `REPLY_READY` cue only if no speech follows (#156) | `Listening` | as listening | the reply is the result; the state returns to listening |
| Background / away | not visible | the cues still play | — | **the state line** | the notification is the only surface; today it never changes |

## What the audit concludes

1. **The being cannot carry *what*.** Tool identity, the target of an action, a failure's reason
   and an approval's request are content, and content needs words. The being keeps the ambient
   states and gains nothing new.
2. **One line, not a panel.** All the word-carried states fit one line of plain language with a
   state (`running` / `done` / `failed`). That is the status pill, made visible for the whole call
   instead of hidden after warming. It is not a log viewer and not a dashboard (#281).
3. **The notification mirrors the line**, so a pocketed call still says what it is doing.
4. **Approval is the one state that needs more than a line** — what, why, and what yes and no
   do — and it is the one this release cannot build, because the transport does not carry it.
5. **Light mode and being-off are covered by the words.** The line uses the theme's text colours,
   so it is legible on both backgrounds whatever the being does (#275 fixes the being itself).

## Sketches

Tool running, being on:

```
         HERMES
  [ Checking your calendar · today ]     <- the state line (the pill), live region
          ·  ·  ·⋰⋱·  ·  ·               <- the being, TOOL motif
                                         <- crawl
```

A long step, then a failure:

```
  [ Sending the email · still working, 12 s ]
  [ ✕ Couldn't send the email — permission denied ]   held until the next turn
```

Being off (conversation layout), sound off:

```
         HERMES
  [ Searching the web · trail conditions ]
  You: look up trail conditions for …
  Agent: …
```

Background: `Hermes Vox — Searching the web · trail conditions`.

## Out of scope here

- The words for a state are app-owned; the facts behind them (tool name, arguments, output) are
  upstream-owned and arrive on the stream (D25). The app translates names, it does not invent
  progress it was not told.
- A step's *effect on the world* is not asserted (D26): "Sent" is said only when the agent says so
  in the reply; the line says the step **ran**, and whether it reported an error.
