# Hermes Vox 0.9.1 — each change, explained

## Presence

**Presence is a layer, not a mode.** There used to be a switch between the normal app and "enhanced realtime", which were two products pretending to be one. Now there is one call, and Presence sits on top of it: on, and the phone helps; off, and nothing about the call changes.

**Alpha features gate, off by default.** Everything unfinished lives behind one switch at the top of Settings, and it starts off. Turn it on and you get a list of the alpha feature sets — Presence first — and you choose which to take.

**Small model holds the line; the agent answers.** The local model greets you and fills the pauses; every real question goes to the agent on your gateway. If the agent is slow you hear the wait instead of a guess.

**No more swallowed turns.** Before, the local model could answer something itself and tell the agent to stay quiet, and sometimes you got silence instead of an answer. Now the agent is asked every time, and a turn only counts as answered if you actually heard something.

**VOX.md per profile.** Your agent writes a short document about who it is and how it talks, and the phone hands that to the local voice. Switch profile and the voice changes with it, so two agents don't blur into one.

**Asides and backchannels.** While you talk at length, the being can acknowledge with a small sound — only in a natural pause, never over your words, at a rate you choose.

**Resume after an interruption.** If you cut it off mid-answer, it remembers what you heard and what it never reached, and picks up from there instead of starting again.

**Overlap classification.** Speaking over it is judged by what you said rather than how loud you were: a word or two is an interruption, a full sentence is a new turn.

**Presence tuning bar.** An instrument for judging how Presence is behaving on your phone, so a change can be measured instead of guessed at.

**Presence transcript.** A readable record of what the being said, so a bad turn can be looked at afterwards.

**Continuous capture and semantic endpointing.** The microphone stays open for the whole call instead of restarting each turn, and it decides you've finished by what you said as well as by silence. Both sit behind the gate.

**First-run hardware check.** On first run the app measures the phone and tells you honestly whether it can carry Presence. It advises; it never switches anything on.

## Commands and settings

**Commands reworked from the ground up.** The sheet is grouped by what a command does, the filler text is gone, and each row shows the real state of the command rather than a message that disappears.

**Settings regrouped by what you're changing.** Rows are ordered the way a person arrives at them — connection, hearing, voice, presence, look — and the two rows that answered one question are now one.

**Model switcher: provider → category → model.** Instead of one flat list of identifiers, you choose a provider, then a kind of model, then the model, with the one in use marked.

**Every dialog on the app's own ground.** Around twenty pickers and confirmations that were stock Android panels are now drawn in the app's own style, on its own background.

**Deep UI refinements for light mode.** Light mode used to be the dark app with the lights on; every part of the being now has a light version drawn for it, with contrast that is measured rather than guessed.

**Look controls with a live preview.** Density, speed, trail, hue and motion can be changed while you watch the being, instead of applying them and then judging.

## The call

**Approvals on the phone, by touch or voice.** When the agent needs permission for something, the request appears on your phone with what it wants to do and why. You answer by tapping, or by saying yes or no.

**Silence is never a yes.** If you don't answer in time it counts as no, and an answer that isn't clearly one or the other asks again rather than being read as consent.

**Turn budget pauses for approvals.** A turn used to have a fixed deadline; a sign-off waiting on you no longer runs out the clock and cancels the work.

**Voice instructions moved into the API.** What the app tells the agent about the call now travels in a field meant for it, instead of being pasted into your sentence. Your words stay your words.

**Voice turns keep their tools.** Voice turns used to tell the agent not to do real work, which is why a question with a factual answer came back as a guess. That clause is gone, so it looks things up.

**Reasoning effort setting for voice.** Voice turns can ask for less deliberation, so answers arrive sooner on models that think slowly — a setting, not a different model.

**Speech during a turn is kept, not dropped.** If you correct yourself while it is working, your correction becomes the next turn instead of vanishing.

**Spoken repair instead of silence.** If a reply fails or comes back empty, the phone says so rather than going quiet.

**The agent narrates its own work.** The line that says what is happening is written by the agent about what it is actually doing, not invented by the local model.

## Phone and agent

**Local actions: timer, alarm, do-not-disturb, volume.** Ask for one of these and the phone does it immediately, without a round trip to the agent, and says it is done only when it is.

**The agent can see what the phone is doing.** Every voice turn tells the agent what the local voice last said, which of those actions are running, and simple facts like the time and the battery.

**Profiles come from the host.** The list of agents fills itself from your gateway; an unavailable one is marked rather than deleted, and switching ends the current call cleanly.

**Files handed to the agent, not uploaded.** The phone stages a file, proves it is unchanged with a hash, and serves it once for the agent to fetch. Nothing goes through a third party.

**Vox is a share target.** Any app that can share can send something to Vox, and clipboard text arrives as a named file rather than a wall of pasted words.

**Scrollback through the agent's speech.** You can scroll back through what was said earlier in the call instead of losing it when it scrolls off.

## Distribution

**sherpa-onnx built from source.** The speech engine used to be a prebuilt binary downloaded from the internet. It is now compiled from source when the app is released.

**Reproducible builds.** Two builds of the same source produce identical binaries, so anyone can check that the app they install is the app we built.

**F-Droid metadata prepared.** The recipe for publishing to F-Droid is written and sits in the repository.

## Not yet

**The agent can't direct or veto phone actions.** The phone can act, and the agent can see what it is doing, but it cannot yet ask the phone to act or cancel something you set.

**Voice, model and look are device-wide.** Per-profile settings are not in yet, so one phone has one voice and one look.

**Upgrade path from 0.9.0.3 untested.** A real upgrade from the previous release has not been run on a device. The migrations are tested; the upgrade itself is called out as unproven.
