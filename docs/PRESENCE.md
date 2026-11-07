# Presence: the phone's voice while the agent works

Presence is the layer that lets the phone say a short line while the Hermes agent works. It is not
a mode the app is in and not a second assistant: the conversation, the memory and the answers stay
with the agent, and Presence only owns what the phone says in the gaps — a greeting when the call
connects, a few words while a tool runs, a listening sound while you talk, and the thread picked up
after you interrupt.

Three words carry the rest of this document. **The mind** (also *the agent*) is the Hermes instance
on your gateway: it owns facts, tools, memory and the substantive answer, and it is the same
instance your other front ends talk to. **The soul** is the small on-device model — Gemma 4 E2B —
that renders Presence's own lines; it has no tools, no memory of its own and no authority over what
is true. **The being** is the voice you hear on the phone: the soul's output, delivered by Presence.

## What you need

- **An Android phone with 8 GB of RAM or more.** At first run the app records the phone's total and
  free memory. A phone below the bar is told so, and can still select Presence to try.
- **About 3 GB of free memory at first run.** Presence's measured resident cost is roughly 2.6 GB;
  the Alpha panel states that a live call holds **about 3 GB of memory**. A phone with less free
  memory than the floor is warned, not blocked.
- **The on-device model.** Gemma 4 E2B is a 2,588 MB download listed in **Models** as "Gemma 4 E2B
  (presence)" (`gemma-e2b`). It runs on the GPU when the phone offers one and falls back to the CPU.
- **A Hermes gateway and a saved profile.** A **profile** is one saved connection to one Hermes
  instance: its address, key, memory scope, and its own voice, model and look. The mind answers
  through that profile.
- **A VOX.md for that profile.** **VOX.md** is the voice document the agent authors about itself:
  a fixed Contract followed by a Soul block (its name, essence, register, how it addresses you,
  its humour). The phone holds a read-only mirror per profile. The app asks the agent to author it
  the first time a call needs one; a call with no valid VOX.md for the active profile runs without
  Presence rather than speaking a stale or generic soul.
- **The alpha gate open.** **Alpha features** is one master switch immediately above the alpha
  block at the end of Settings, off by default. A **set** is one unfinished feature behind it; a set
  runs only when the master is on and that set is selected. Presence is the first set, and its own
  switch still has to be on. On a fresh install nothing alpha runs.

Continuous listening belongs to Presence: it is how the being hears the call, so its set requires
the Presence set. Turn-taking's acknowledgements also need continuous listening; its interruption
classification does not. Taking either set is optional: Presence without them speaks its greeting,
the beat and its working lines, and does not hear live partials or classify an interruption.

## How it works, in the order a call meets it

### The call opens with the being's greeting

When the call connects and the caller has not spoken, the soul renders one greeting against the
profile's VOX.md and the phone speaks it. The directive asks for one short warm sentence, under
about twenty words, and forbids asking what the caller needs or offering help. The greeting is the
one full line the soul speaks without a mind turn competing: there is nothing to answer yet. If the
phone cannot load Presence, or the profile has no VOX.md, the call opens as ordinary operation — no
label, no degraded pill; the Settings row says what is missing.

### It hears continuously

**Continuous listening** is the set that keeps the microphone and the streaming recogniser open
for the whole call (`ContinuousCapture`). Two things change from the ordinary turn loop:

1. The live recogniser stream is fed and its partial read every **100 ms**, starting at the
   utterance's first sample, instead of once per 900 ms.
2. The recorder is opened once per call and left recording across turns, so the first word after a
   reply is not lost to a stop/start seam and the microphone is not re-negotiated between turns.

It is not one endless recogniser stream: each utterance gets its own cheap reset, which keeps the
decoding state bounded and the turn's text its own. It only engages on a recogniser that keeps a
live stream; on a whole-clip recogniser the app logs `event=capture-continuous
skip reason=no-live-stream` and behaves as before. The measured cost stated in the panel is about
300 MB more memory during a call.

### It judges the end of your turn

**Semantic endpointing** decides that you have finished speaking from evidence rather than a fixed
silence. It has three inputs: how long you have been silent, whether the words so far read as a
finished English sentence, and how long the recogniser's hypothesis has held still. A partial that
ends on "and", "the", "because" or "um" needs a long pause; one that reads finished and has stopped
changing commits after a short one; every turn commits at the preset's 2.2 s ceiling.

The presets are PATIENT, BALANCED and EAGER, differing only in how long the hypothesis must hold
still (400, 300 and 200 ms); BALANCED ships. Only the `FINAL` verdict commits the turn;
`PROVISIONAL` is a latency hint and is not acted on. The thresholds are a first fit to a 22-clip
English corpus, replayed on every build — a hypothesis, not a field measurement. Semantic
endpointing needs the 100 ms partials, so it does not engage without continuous listening; the app
logs `event=endpoint-semantic skip reason=no-continuous-capture` once and falls back to the fixed
pause.

### The beat: what the being says while the mind starts

Every voice turn goes to the mind immediately. The soul is asked for one thing on that turn: the
**beat** — two to six words you hear as the mind starts to think, or silence. The beat is rendered
in the same call as the decision and is dropped if the mind's first text or first audio gets there
first. A beat counts as spoken only when it is audible (`event=er-soul-played`, recorded from the
TTS start callback, never from the render).

The beat is checked before it can play: at most seven words, at most 60 characters, no bracket
markup, and no claim that a result exists. A render that answered instead of opening is not spoken
at all (`event=er-soul-drop reason=not-a-beat`); the mind is answering.

### While the mind works

The mind's own progress narration (`phase=commentary`, the `message.interim` event on the runs
route) is spoken as the working line when it exists and the narration voice is not `silent` — the
mind describing its own work, rather than the soul guessing from a tool name. It goes through the
same guards as any other aside and is cut to the beat's budget at a clause end.

If the mind has said nothing after **4 s**, the phone says one short lag line. After that, the
**register** paces mid-run asides: **Quiet** (one line every 30 s, at most 3), **Conversational**
(every 18 s, at most 6 — the default) and **Chatty** (every 10 s, at most 12). A slot the soul
declines still counts as a line, so a wedged turn cannot talk forever. The reply preempts
everything: once its first text or audio is live, no further aside is delivered.

### While you are still talking

A **backchannel** is a listening sound — "mm-hm", "right", "go on" — that acknowledges without
asking anything. With continuous listening and the turn-taking set, the phone may play one recorded
nonverbal clip while you talk. It never synthesises words for this. Every condition must hold: you
have been talking for at least 6 s; the pause is between 250 ms and the smaller of 500 ms and 60%
of the turn-ending silence; the live hypothesis does not read as finished; the rate allows it; and
nothing else is sounding. The rate is **Sparse** by default (one every 20 s, at most 2 a minute),
**Normal** is 10 s / 4 a minute, and **Off** is a real choice. For 900 ms after a clip the VAD may
not reset the pause clock, so the acknowledgement cannot extend your turn.

The timing is fitted to English. Backchannel timing does not transfer between languages, and this
policy says nothing about any other.

### When you interrupt

Talking over the reply is a **barge-in**. The physical cut is level-based: sustained speech-level
audio stops the voice at once, because a missed stop is the thing a caller notices first. What
happens next depends on what you said, and that is **overlap** classification (`Overlap`):

| what the interrupting words are | class | what happens |
|---|---|---|
| a listening sound — "mm-hm", "right", "go on" | `BACKCHANNEL` | the mind is asked to carry on from the cut, not to answer the sound |
| one or two words — "wait", "hold on", "no" | `BARGE` | the being goes quiet and listens; no turn is sent; the cut reply stays marked |
| a full sentence | `NEW_TURN` | a new turn is answered, carrying the interruption mark so the mind may resume |

An empty or unreadable carry classifies as `BARGE`, the cheaper error: stop and listen, never a
turn built from nothing. This classification runs only with the **turn-taking** set. Without it,
every carried utterance is a new turn, as it was before the set existed. Separately, the **semantic
barge** switch decides whether an interruption cancels the mind's work or is held as a
backchannel; with it off, every interruption cancels the work.

Speech during the mind's work that is not a listening sound is not thrown away either. The app keeps
one pending slot — newest wins — and submits those words as the next turn when the current one
settles. The words are dropped only after 60 s or when you start a newer turn yourself;
`event=er-pending` is the witness that they were queued rather than lost.

### Picking the thread back up

When a reply is cut, Presence keeps a **resume mark**: what you actually heard (the playback
cursor's frozen position, not what had been written to the track) and what was written but never
heard. The next voice turn carries one plain directive built from it — you were interrupted, here
is where, answer them first, then continue without repeating what they heard, or drop it if they
changed course.

The mark is one at a time, used once, and expires after **two minutes**. A hush (your own stop) or
a hang-up is not an interruption and leaves no mark. For a listening sound, the directive asks the
mind to continue from exactly the cut; if there was nothing left to say, no turn is sent. The
directive quotes at most 160 characters of what you heard and 400 of what you did not, so it places
the cut without replaying the answer. The mark and the overlap classification both belong to the
turn-taking set; with it off, a barge leaves no mark.

### What it may say, and what it must never claim

Presence's own speech is limited to the greeting, the beat, the working line, the hold line ("okay
— take the time you need", only for a listening sound during the mind's work), and the recorded
acknowledgements. At the start of a turn the phone adds nothing of its own: "yes" is the turn, not a
backchannel. A failed or empty turn is ended by the app's own repair line, spoken through the
ordinary reply path rather than by the soul, so the caller is never left in silence. The soul is
never asked to answer a factual turn.

VOX.md's Contract is the same rule in the agent's own document: never invent facts or fake a
result; never commit a real-world action; escalate substantive questions to the mind; never claim
a capability you do not have; always be interruptible; do not fabricate shared history.

The code enforces a narrower, testable subset:

- **A claimed result is refused.** A completed-action claim — "I've booked it", "it's done",
  "you're all set" — is dropped on every Presence lane: the greeting, the beat, the working line and
  the mind's own commentary. It is refused, not repaired.
- **Format is repaired, content is refused.** An over-long line is cut at a clause end inside the
  seven-word budget, or dropped when there is none; markdown, fences, titles, bullets and a
  trailing courtesy are removed. A line is never refused for being badly formatted.
- **One line, one time.** A line already said this run is refused; nothing of the soul's is
  delivered after the mind's reply has begun.
- **Numbers are not policed.** A number the client did not put in the directive is counted in
  `numbers-unvouched=` and spoken. The guard refuses claims, not arithmetic.
- **It may look, it may not act.** Presence reads the phone's action registry, clock, battery,
  network and Do Not Disturb state so it can answer "how long is left?"; it has no call that sets,
  cancels or changes anything. The mind acts on the phone through the separate Phone tools set.

### Reading a call afterwards

`event=er-handoff` is one receipt per turn: `ok=true` means the gateway accepted the turn and
returned a stream id; `ok=false` names the path that dropped it. The caller's text never appears in
it — only `askChars`, its length — so the line is safe with transcript logging off. The other
events in the same vocabulary are `er-soul` (the render, its duration and how long after speech end
it started), `er-soul-drop` (why a line was not spoken), `er-soul-played` (it was audible),
`er-presence line` / `er-presence skip` (a working slot spoken or passed over) and `er-pending`
(the caller's words queued instead of dropped).

**The tuning instrument** is `scripts/presence-session-report.py`. It reads a logcat file and needs
no device or build:

```bash
python3 scripts/presence-session-report.py logcat.txt          # the report
python3 scripts/presence-session-report.py --bar logcat.txt    # only the five criteria
python3 scripts/presence-session-report.py --check logcat.txt  # non-zero unless the bar passes
```

**The bar** has five rows. Each prints its threshold, its measured value and `n`. A row with
nothing to measure says **no data** — never a pass — and a run where nothing was measured cannot
pass.

| row | what it measures | bar |
|---|---|---|
| `speak-rate` | spoken ÷ offered working slots, Conversational runs only | ≥ 2/3 |
| `late` | lines that arrived after the reply's first text: dropped turn beats plus working slots skipped late | = 0 |
| `repeats` | a spoken line hash heard twice in one session | = 0 |
| `render-p95` | render latency, nearest-rank p95 | < 3000 ms |
| `boundary` | working renders that started less than 700 ms after the caller stopped | = 0 |

A **session** is one conversation; `er-presence stop reason=call-end` closes one, and a second log
file is a second conversation. `--bar` exits 0 when every measured row passes, 1 when any measured
row fails, and 2 when nothing was measured, so an empty run cannot be mistaken for a pass. The bar
is the release's measure of Presence; it gates nothing by itself.

## What it writes, sends or changes

- **Settings.** The alpha master and the Presence set; the Presence switch; whether the soul narrates
  at all (Soul narration); its narration voice (`silent`, `sounds`, `spoken`); the register; whether
  it hears the caller's audio; the semantic barge scope; the reasoning effort for voice turns
  (default `low`).
- **The VOX.md mirror.** `filesDir/vox/<profile>/VOX.md`, written only from the agent's reply and
  read-only on the phone. The phone never pushes identity back. A pre-profile file is migrated
  into the active profile's slot once.
- **Every voice turn's `instructions`.** The voice register, the soul-sync (what the phone has
  already said, and the caller's measured vibe) and the presence context — at most 200 characters
  naming the last line, whether the phone is speaking, the running local actions, the clock,
  battery, connectivity and Do Not Disturb. The caller's words themselves are sent byte-exact as
  the request input. `instructions` is ephemeral: the gateway appends it to the system prompt for
  that request and does not store it.
- **A per-request reasoning effort** (`model_options.reasoning`, default `low`) on voice turns.
- **Local logs.** Counts, durations, class names and line hashes; transcript text only when
  transcript logging is on.
- **Nothing to the network from the soul.** Speech recognition, the soul's model and synthesis run
  on the phone. The caller's audio is never sent to the gateway; only text is.

## What it cannot do

- It cannot answer a turn. Every voice turn goes to the mind; the soul's own contribution on a
  turn is the beat, and a render that tries to answer is not spoken.
- It cannot claim a result, commit an action, invent a fact, or claim a capability the mind does
  not have. Those lines are refused.
- It cannot police numbers: a number the client did not supply is spoken and only logged.
- It cannot speak without a valid VOX.md for the active profile. That call runs without Presence.
- It cannot run without the 2.6 GB model. A phone below the 8 GB bar is warned rather than
  supported, and a first load that fails switches the Presence set off and says so.
- It cannot hear live partials without continuous listening, and cannot judge a semantic endpoint
  without those partials. Without them, the fixed pause ends the turn and no acknowledgement can
  fire; an acknowledgement also needs the turn-taking set.
- It cannot classify an interruption without the turn-taking set; without it, anything carried
  into the next segment becomes a turn.
- It cannot resume after two minutes, across a process death, or more than once: there is one
  mark, used once.
- It cannot act on the phone. It reads the action registry and device state; the mind is what
  sets, cancels or changes anything.
- It cannot listen in the background or wake on a phrase. The wake word is a separate set, and its
  detector is not installed.
- It cannot keep its own memory of the relationship. The soul has a rolling conversation with no
  facts in it; identity comes from VOX.md and facts come from the mind.
- It cannot repair a bad VOX.md on the phone. The gateway authors it; the phone only mirrors it.
