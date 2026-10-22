#!/usr/bin/env python3
"""truncation-triage.py — read a log against the lines that decide a truncation report (#202).

"Speech stops before the sentence ends" has two candidate causes the issue keeps apart:
playback retirement (the track closed before its last chunk played) and synthesis (the voice
produced less audio than the text). This reads every cut in a log and says which the log
points at, using only the app's own lines:

  event=reveal-freeze reason=R chars=HEARD of=TOTAL        where playback actually stopped
  event=tts-retire gen=N chunks=C ms=M                     the worker retired after C chunks
  event=tts-stop gen=N worker-break chunkIdx=I             the worker broke out at chunk I
  event=tts-stop reason=R msSinceBarge=…                   a stop someone asked for
  <model> chunk S smp @SR Hz (L ch)                        one chunk synthesized
  event=tts-drop reason=…                                  a chunk refused after a cut (#240)

  python3 scripts/truncation-triage.py log.txt

Verdicts, per reply:
  user-cut        a stop was asked for (barge / hush / hang-up): not a truncation
  retirement      a worker-break with no stop asked for, or audio frozen short while every
                  chunk was synthesized — the audio was cut
  synthesis       the reply ended naturally but the chunks were short for the text
  heard-whole     frozen at (or near) the end: nothing was lost
  not-enough-log  a line the verdict needs is missing; says which
"""
import re
import sys
from pathlib import Path

CHUNK = re.compile(r" chunk (\d+) smp @(\d+)Hz \((\d+) ch\)")
KV = re.compile(r"(\w+)=([^\s]+)")
# Natural speech runs ~12-16 characters per second; a chunk carrying far more text than its
# audio can hold at the slowest register is a synthesizer that smeared or skipped words.
# A hypothesis, not a measurement: retire it when a bench says otherwise.
MIN_SEC_PER_CHAR = 1 / 25.0


def triage(lines):
    replies, cur = [], None

    def new():
        return {"chunks": [], "stop_asked": None, "worker_break": None, "retired": None,
                "freeze": None, "drops": 0, "ended": False}
    for line in lines:
        m = CHUNK.search(line)
        if m:
            # A chunk after a cut or a freeze belongs to the next reply.
            if cur is not None and (cur["freeze"] or cur["stop_asked"] or cur["ended"]):
                replies.append(cur); cur = None
            if cur is None:
                cur = new()
            smp, sr, ch = (int(x) for x in m.groups())
            cur["chunks"].append((smp / sr, ch))
            continue
        if "event=" not in line:
            continue
        kv = dict(KV.findall(line))
        ev = kv.get("event")
        if cur is None and ev in ("tts-stop", "reveal-freeze", "tts-retire"):
            cur = new()
        if ev == "tts-stop" and "worker-break" in line:
            cur["worker_break"] = int(kv.get("chunkIdx", "-1"))
        elif ev == "tts-stop" and "reason" in kv:
            cur["stop_asked"] = kv["reason"]
        elif ev == "tts-drop" and cur is not None:
            cur["drops"] += 1
        elif ev == "reveal-freeze":
            cur["freeze"] = (int(kv.get("chars", "0")), int(kv.get("of", "0")), kv.get("reason", ""))
        elif ev == "tts-retire":
            cur["retired"] = int(kv.get("chunks", "0"))
            replies.append(cur); cur = None
        elif ev == "reply-end" and cur is not None:
            # #240's one end line; a cancel names who asked. The group stays open for the
            # tts-stop and reveal-freeze lines that the same cut writes next.
            cur["ended"] = True
            if kv.get("reason") == "cancelled" and cur["stop_asked"] is None:
                cur["stop_asked"] = kv.get("cause")
    if cur is not None:
        replies.append(cur)
    return [verdict(r) for r in replies]


def verdict(r):
    if r["stop_asked"] in ("barge-in", "hush", "stop", "focus-loss") or (r["stop_asked"] or "").startswith("er-"):
        return "user-cut", f"stop asked: {r['stop_asked']}"
    if r["worker_break"] is not None and r["stop_asked"] is None:
        return "retirement", f"worker broke at chunk {r['worker_break']} with no stop asked for"
    if r["freeze"]:
        heard, total, _ = r["freeze"]
        if total and heard >= total * 0.95:
            return "heard-whole", f"froze at {heard} of {total} characters"
        if r["retired"] is not None and r["chunks"] and r["retired"] >= len(r["chunks"]):
            return "retirement", f"froze at {heard} of {total} characters with all {len(r['chunks'])} chunks synthesized"
    if r["chunks"]:
        short = [(s, c) for s, c in r["chunks"] if c and s < c * MIN_SEC_PER_CHAR]
        if short:
            s, c = short[0]
            return "synthesis", f"{len(short)} chunk(s) short for their text (e.g. {s:.2f} s for {c} characters)"
        if r["retired"] is not None and not r["freeze"]:
            return "heard-whole", f"retired after {r['retired']} chunks, nothing cut"
    missing = [n for n, k in (("reveal-freeze", "freeze"), ("tts-retire", "retired")) if r[k] is None]
    return "not-enough-log", "missing " + (", ".join(missing) or "chunk lines")


def main(argv):
    if not argv:
        print(__doc__.strip().splitlines()[0]); return 2
    lines = []
    for p in argv:
        lines += Path(p).read_text(errors="replace").splitlines()
    out = triage(lines)
    for i, (v, why) in enumerate(out, 1):
        print(f"reply {i}: {v} — {why}")
    counts = {}
    for v, _ in out:
        counts[v] = counts.get(v, 0) + 1
    print("summary: " + (", ".join(f"{k}={n}" for k, n in sorted(counts.items())) or "no replies in log"))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
