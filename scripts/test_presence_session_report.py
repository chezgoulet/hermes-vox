#!/usr/bin/env python3
"""Tests for presence-session-report.py. No dependencies, no device.

  python3 scripts/test_presence_session_report.py
"""
import importlib.util
import unittest
from pathlib import Path

_spec = importlib.util.spec_from_file_location(
    "presence_report", Path(__file__).with_name("presence-session-report.py")
)
assert _spec and _spec.loader, "cannot load presence-session-report.py"
pr = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(pr)

HEAD = "10-20 14:20:51.480 15273 15273 D HermesVox: "


def log(*lines):
    return "\n".join(HEAD + l for l in lines)


class Parsing(unittest.TestCase):
    def test_a_slot_counts_as_offered_and_a_line_as_spoken(self):
        s = pr.parse(log("event=er-presence line=1 interval=4028 density=conversational kind=lag"))
        d = pr.summarise(s)
        self.assertEqual(d["slots"], 1)
        self.assertEqual(d["spoken"], 1)
        self.assertEqual(d["kinds"], {"lag": 1})

    def test_a_refusal_is_offered_but_not_spoken_and_keeps_its_reason(self):
        s = pr.parse(log(
            "event=er-presence line=1 interval=4028 density=conversational kind=lag",
            "event=er-presence skip line=2 reason=silent density=conversational",
        ))
        d = pr.summarise(s)
        self.assertEqual(d["slots"], 2)
        self.assertEqual(d["spoken"], 1)
        self.assertEqual(d["skips"], {"silent": 1})
        self.assertAlmostEqual(d["rate"], 0.5)

    def test_slot_indices_are_a_max_not_a_count(self):
        # line=4 alone means four slots were offered, whatever the log lost.
        s = pr.parse(log("event=er-presence line=4 interval=18157 density=conversational kind=soul"))
        self.assertEqual(pr.summarise(s)["slots"], 4)

    def test_runs_do_not_bleed_into_each_other(self):
        s = pr.parse(log(
            "event=er-presence line=1 interval=4000 density=conversational kind=lag",
            "event=er-presence stop reason=reply lines=1",
            "event=er-presence line=1 interval=9000 density=quiet kind=soul",
            "event=er-presence stop reason=cap lines=1",
        ))
        d = pr.summarise(s)
        self.assertEqual(d["runs"], 2)
        self.assertEqual(d["slots"], 2)
        self.assertEqual(d["spoken"], 2)
        self.assertEqual(d["stops"], {"reply": 1, "cap": 1})

    def test_a_run_that_said_nothing_still_closes(self):
        s = pr.parse(log("event=er-presence stop reason=reply lines=0"))
        self.assertEqual(pr.summarise(s)["runs"], 0)


class Soul(unittest.TestCase):
    def test_a_late_drop_is_the_after_the_reply_signal(self):
        s = pr.parse(log("event=er-soul-drop reason=late beat=false gen=1"))
        d = pr.summarise(s)
        self.assertEqual(d["late_drops"], 1)
        self.assertFalse(d["checks"]["late"])

    def test_a_render_without_ms_is_counted_as_missing_not_as_zero(self):
        s = pr.parse(log("event=er-soul kind=turn decision=answer chars=6 heard=true mood=calm"))
        d = pr.summarise(s)
        self.assertEqual(d["render_samples"], 0)
        self.assertEqual(d["render_samples_missing_ms"], 1)
        self.assertIsNone(d["render_ms_p95"])
        self.assertIsNone(d["checks"]["p95"])   # unknown, not a pass and not a fail

    def test_render_latency_is_summarised_when_the_build_logs_it(self):
        s = pr.parse(log(
            "event=er-soul kind=working decision=answer chars=30 heard=false mood=- ms=1200",
            "event=er-soul kind=working decision=answer chars=30 heard=false mood=- ms=1400",
            "event=er-soul kind=working decision=answer chars=30 heard=false mood=- ms=9000",
        ))
        d = pr.summarise(s)
        self.assertEqual(d["render_samples"], 3)
        self.assertEqual(d["render_ms_p50"], 1400)
        self.assertEqual(d["render_ms_p95"], 9000)
        self.assertFalse(d["checks"]["p95"])   # 9 s is over the 3 s bar

    def test_decisions_are_counted(self):
        s = pr.parse(log(
            "event=er-soul kind=turn decision=answer chars=6 heard=true mood=calm",
            "event=er-soul kind=turn decision=beat chars=0 heard=true mood=warm",
        ))
        self.assertEqual(pr.summarise(s)["decisions"], {"answer": 1, "beat": 1})


class Bar(unittest.TestCase):
    def test_a_third_of_slots_speaking_fails_the_rate(self):
        s = pr.parse(log(
            "event=er-presence line=1 interval=4000 density=conversational kind=lag",
            "event=er-presence skip line=2 reason=silent density=conversational",
            "event=er-presence skip line=3 reason=silent density=conversational",
        ))
        d = pr.summarise(s)
        self.assertAlmostEqual(d["rate"], 1 / 3)
        self.assertFalse(d["checks"]["rate"])
        self.assertFalse(d["passes_bar"])

    def test_quiet_runs_do_not_dilute_the_conversational_rate(self):
        s = pr.parse(log(
            "event=er-presence line=1 interval=4000 density=conversational kind=lag",
            "event=er-presence stop reason=reply lines=1",
            "event=er-presence skip line=1 reason=silent density=quiet",
            "event=er-presence skip line=2 reason=silent density=quiet",
            "event=er-presence stop reason=cap lines=0",
        ))
        d = pr.summarise(s)
        self.assertEqual(d["conversational"], {"slots": 1, "spoken": 1})
        self.assertAlmostEqual(d["rate"], 1.0)


class Percentiles(unittest.TestCase):
    def test_one_sample_is_that_sample(self):
        self.assertEqual(pr.pct([5], 0.95), 5)

    def test_p95_is_nearest_rank_not_interpolated(self):
        # Three samples: the tail must be the slowest, not a number between them.
        self.assertEqual(pr.pct([1200, 1400, 9000], 0.95), 9000)

    def test_median_of_an_even_run_interpolates(self):
        self.assertEqual(pr.pct([1, 3], 0.5), 2)

    def test_empty_is_none(self):
        self.assertIsNone(pr.pct([], 0.5))


if __name__ == "__main__":
    unittest.main(verbosity=2)
