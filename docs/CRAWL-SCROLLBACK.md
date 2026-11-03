# The crawl's scrollback (#368)

Read earlier agent speech on the presence surface. The owner's case is the barge-in:
the response was cut and its first lines are already above the top fade. This is a
rendering feature, not a scroll-view feature — `CrawlView` is canvas-drawn.

## The gesture

No control is added. Press anywhere on the reply and drag **down**: the transcript
follows the finger and older lines come down out of the fade. This is direct
manipulation, the same direction a chat list moves when you pull it back.

Exit is deliberately easier than entry:

- **Tap** the crawl (a press with no movement) — back to live at once;
- or drag **up** to the live edge and release — the same exit by movement.

The return is a 180 ms ease to the live edge, not a mode switch. Nothing else can
trap the reader: a call can be hung up, a new turn can start, and both leave the
trail exactly where it is.

## Where the history lives

`CrawlHistory` is a bounded ring, not the conversation:

- the last **24** finished lines, or **8 000** rendered characters, whichever binds
  first;
- only agent speech: a finished turn reply and a Presence aside (marked `· ` like the
  transcript). State narration and tool steps never enter;
- a soul answer is the turn's reply; if the mind's final text echoes it in the same
  turn, it is one line. The same words in a *new* turn are two lines — a real repeat.

The in-flight reply is not in the ring. It stays the live slot (`CrawlView.full`)
because it is still growing and still speech-locked. When a turn's speech finishes — or
is frozen by a hush/barge — it is **sealed** into the ring and the live slot is
cleared. `CrawlDocument` joins `history + "\n\n" + live`, so the on-screen shape does
not change at the seal.

`Transcript` (conversation layout) keeps its own 4 000-character record and is
untouched. A new conversation clears both.

## While you read

A live reply **queues behind** the read. It keeps streaming into the live slot below
the viewport; the reader's position is anchored as a character distance from the
document's end, so new text cannot pull the words under the eye. This holds for a
reply that finishes and seals mid-read and for a new turn arriving mid-read. Only the
caller's own gesture leaves the trail.

The being keeps its motion, the call keeps its turn gate, and the cues keep playing.
None of them is an input to the scroll state, and the scroll state is not an input to
any of them.

## Presence

Presence asides already reach the app through `onPresenceSpoke`, the one funnel every
voiced line passes through. That funnel now also records the line in the ring, once,
when it was actually voiced. A scroll never re-emits it and never reads it a second
time, so asides cannot be lost or duplicated. Back in live, the asides are not drawn
on the crawl — that is unchanged — but they are part of the trail you scroll back to.

## Accessibility

The crawl is drawn, so TalkBack sees it only through `contentDescription`. That
description is now the whole document — the finished trail plus the live reply — not
just the live line, so the scrolled-back text is reachable. It is still not a live
region: the voice speaks the reply; a scroll must not re-announce it.

## Both grounds

History is painted with the same paints and the same `CrawlInk` alphas as live text;
nothing new is dimmed. The top fade still applies, and the trail may be pulled down
until its first line reaches the bright band below the fade, so a line never has to
be read at zero alpha. `CrawlDrawContrastTest` remains the guard on both grounds.

## What a device still has to prove

- A press + drag enters, follows the finger, and a tap exits (touch plumbing is not
  unit-testable here).
- The anchored words stay still when a delta arrives mid-read, at real reflow widths.
- TalkBack focus reads the scrolled-back region, and does not re-announce on scroll.
- Both themes while scrolled: the first line in the fade band and the live tail at
  the bottom are readable.
- The barge case end to end: interrupt a long reply, scroll back, read the start,
  tap back to live.
