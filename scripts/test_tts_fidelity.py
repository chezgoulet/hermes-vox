#!/usr/bin/env python3
"""Self-test for tts-fidelity.py: the scoring and the splits, proven with no model and no host.

The bench's numbers are only worth what its word-comparison is worth. A comparison that
over-counts blames the voice for the recogniser; one that under-counts hides exactly the defect
#559 is about. So the pure parts are pinned here:

  * a perfect transcript loses nothing;
  * a dropped word is named, in order, and nothing else is;
  * a substituted word is one missing and one extra (the honest reading);
  * the `comma` split lands after the first comma and keeps it;
  * the `mid` split lands between words, never inside one, and the halves rejoin to the sentence;
  * a sentence with no comma has no `comma` rendering — invented punctuation would test nothing.
"""
import importlib.util
from pathlib import Path

spec = importlib.util.spec_from_file_location(
    "tts_fidelity", Path(__file__).with_name("tts-fidelity.py"))
tf = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tf)

S = "The timer is set, and it will ring in ten minutes."

# One word is one word, whatever the punctuation around it.
assert tf.words(S)[:4] == ["the", "timer", "is", "set"], tf.words(S)
# A clock time is two words, spelled the way the transcript spells them.
assert tf.words("Nine, at 9:30 — ok.") == ["nine", "at", "nine", "thirty", "ok"], tf.words("Nine, at 9:30 — ok.")
assert tf.words("Don’t do that.") == ["don't", "do", "that"], tf.words("Don’t do that.")

# A perfect transcript loses nothing.
perfect = tf.compare(S, S)
assert perfect == {"expected": 11, "heard": 11, "missing": [], "extra": [], "kept": 11}, perfect

# A dropped word is named, and only it.
gone = tf.compare(S, "The timer is set, and it will ring in ten.")
assert gone["missing"] == ["minutes"], gone
assert gone["extra"] == [], gone
assert gone["kept"] == 10, gone

# Two drops, in expected order — the order is what lets a reader see which chunk lost what.
assert tf.compare("a b c d e", "a c e")["missing"] == ["b", "d"]

# A substitution is one missing and one extra, not a silent pass.
sub = tf.compare("ring in ten minutes", "ring in ten minnows")
assert sub["missing"] == ["minutes"] and sub["extra"] == ["minnows"], sub

# A recogniser writes what it hears as a numeral; the transcript spells it. Same word.
assert tf.compare("ring in ten minutes", "ring in 10 minutes")["missing"] == []
assert tf.compare("starts at nine", "starts at 9")["missing"] == []

# Punctuation and case are never differences.
assert tf.compare(S, S.upper())["missing"] == []
assert tf.compare(S, S.replace(",", "").replace(".", ""))["missing"] == []

# The three renderings.
ways = tf.split_ways(S)
assert list(ways) == ["whole", "comma", "mid"], ways
assert ways["whole"] == [S]
assert ways["comma"] == ["The timer is set,", "and it will ring in ten minutes."], ways["comma"]
# `mid` splits between words: rejoining the halves must give the sentence back, less one space,
# and neither half may be empty (an empty chunk would test nothing but the concatenation).
left, right = ways["mid"]
assert (left + " " + right) == S, (left, right)
assert left and right, (left, right)
assert not left.endswith(" ") and not right.startswith(" ")
assert len(left) > 10 and len(right) > 10, (left, right)   # near the middle, not at an end

# No comma, no comma rendering — and `whole`/`mid` still exist.
nocomma = tf.split_ways("I can hear you clearly and the line sounds steady.")
assert set(nocomma) == {"whole", "mid"}, nocomma
# A one-word sentence cannot be split mid-phrase, and must not produce an empty chunk.
assert "mid" not in tf.split_ways("Yes"), tf.split_ways("Yes")

# The sweep holds the words constant and varies only how many pieces they arrive in.
for n in (1, 2, 3, 4):
    pieces = tf.equal_splits(S, n)
    assert len(pieces) == n, (n, pieces)
    assert " ".join(pieces) == S, (n, pieces)
    assert all(p.strip() for p in pieces), (n, pieces)
assert tf.equal_splits(S, 1) == [S]
assert tf.equal_splits("one two", 5) == ["one", "two"]   # more pieces than words cannot invent words

# The resampler changes length by the ratio and leaves a constant signal alone.
flat = [0.5] * 44_100
out = tf.resample(flat, 44_100, 16_000)
assert abs(len(out) - 16_000) <= 1, len(out)
assert all(abs(v - 0.5) < 1e-6 for v in out[:100]), out[:5]
assert tf.resample([0.1, 0.2], 16_000) == [0.1, 0.2]

print("tts-fidelity self-test: OK")
