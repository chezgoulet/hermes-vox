#!/usr/bin/env python3
"""Tests for presence-session-report.py. No dependencies, no device.

  python3 scripts/test_presence_session_report.py
"""
import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
SCRIPT = HERE / "presence-session-report.py"
FIXTURE = HERE.parent / "testdata" / "presence-session.txt"

_spec = importlib.util.spec_from_file_location(
    "presence_report", HERE / "presence-session-report.py"
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


class Guard(unittest.TestCase):
    def test_a_refused_line_records_why_the_guard_refused_it(self):
        s = pr.parse(log(
            "event=er-presence skip line=3 reason=silent density=conversational",
            "event=er-presence detail line=3 why=too-many-words text=<hidden>",
        ))
        d = pr.summarise(s)
        self.assertEqual(d["guard_refusals"], {"too-many-words": 1})
        self.assertEqual(d["skips"], {"silent": 1})   # the skip still counts as a slot

    def test_each_guard_reason_is_counted_separately(self):
        s = pr.parse(log(
            "event=er-presence detail line=3 why=too-long text=<hidden>",
            "event=er-presence detail line=4 why=digits-or-markup text=<hidden>",
            "event=er-presence detail line=5 why=too-long text=<hidden>",
        ))
        self.assertEqual(pr.summarise(s)["guard_refusals"], {"too-long": 2, "digits-or-markup": 1})

    def test_a_line_the_guard_would_have_spoken_is_not_a_refusal(self):
        # `why=-` says the guard passed it and the slot was skipped for another reason.
        s = pr.parse(log(
            "event=er-presence detail line=2 why=- text=<hidden>",
            "event=er-presence skip line=2 reason=late density=conversational",
        ))
        d = pr.summarise(s)
        self.assertEqual(d["guard_refusals"], {})
        self.assertEqual(d["skips"], {"late": 1})

    def test_the_late_witness_rides_the_detail_line_without_hiding_why(self):
        # The working lane's witness appends deltaMs= after the text, and a working `late` is a
        # slot skip — it must never be counted as the turn lane's late_drops.
        s = pr.parse(log(
            "event=er-presence detail line=2 why=- text=<hidden> deltaMs=1840",
            "event=er-presence skip line=2 reason=late density=conversational",
            "event=er-soul-drop reason=late beat=false gen=1",
        ))
        d = pr.summarise(s)
        self.assertEqual(d["skips"], {"late": 1})
        self.assertEqual(d["late_drops"], 1)   # only the turn lane's line counts here


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

    def test_p95_over_an_odd_number_of_samples(self):
        self.assertEqual(pr.pct([100, 200, 300], 0.95), 300)

    def test_p95_over_an_even_number_of_samples(self):
        # Nearest rank: ceil(0.95 × 4) = 4, the slowest of the four — never an interpolation.
        self.assertEqual(pr.pct([900, 1200, 1400, 4000], 0.95), 4000)
        self.assertEqual(pr.pct([900, 4000], 0.95), 4000)

    def test_median_of_an_even_run_interpolates(self):
        self.assertEqual(pr.pct([1, 3], 0.5), 2)

    def test_empty_is_none(self):
        self.assertIsNone(pr.pct([], 0.5))



class NamedBar(unittest.TestCase):
    """#217: every row names what it measures, and a row with nothing to measure is unknown."""

    def row(self, d, name):
        return next(b for b in d["bar"] if b["name"] == name)

    def test_every_row_names_its_measure_source_threshold_and_n(self):
        d = pr.summarise(pr.parse(log("event=er-presence line=1 interval=1 density=conversational kind=lag")))
        names = [b["name"] for b in d["bar"]]
        self.assertEqual(names, ["speak-rate", "late", "repeats", "render-p95", "boundary"])
        for b in d["bar"]:
            for k in ("measures", "source", "threshold", "n"):
                self.assertTrue(str(b[k]), f"{b['name']} has no {k}")
        text = pr.report(pr.parse(log("event=er-presence line=1 interval=1 density=conversational kind=lag")))
        self.assertIn("[er-presence line / skip]", text)

    def test_repeats_are_counted_by_hash_across_a_session(self):
        # Two turns of one call (a `reply` stop ends a turn, not the call). The third line
        # repeats the earlier two across the turn boundary: a repeat is a call-level fact.
        d = pr.summarise(pr.parse(log(
            "event=er-presence line=2 interval=1 density=conversational kind=soul h=aaaa0001",
            "event=er-presence line=3 interval=1 density=conversational kind=soul h=aaaa0001",
            "event=er-presence stop reason=reply lines=3",
            "event=er-presence line=2 interval=1 density=conversational kind=soul h=aaaa0001",
        )))
        self.assertEqual(d["repeats"], 2)
        self.assertEqual(d["hashed_lines"], 3)
        self.assertIs(self.row(d, "repeats")["verdict"], False)

    def test_repeats_without_hashes_are_unknown_not_a_pass(self):
        d = pr.summarise(pr.parse(log("event=er-presence line=2 interval=1 density=conversational kind=soul")))
        self.assertIsNone(self.row(d, "repeats")["verdict"])

    def test_a_working_render_inside_the_boundary_window_fails(self):
        d = pr.summarise(pr.parse(log(
            "event=er-soul kind=working decision=spoken chars=20 heard=false mood=- ms=900 boundaryMs=12000",
            "event=er-soul kind=working decision=spoken chars=20 heard=false mood=- ms=900 boundaryMs=300",
            "event=er-soul kind=turn decision=beat chars=20 heard=false mood=- ms=900 boundaryMs=50",
        )))
        b = self.row(d, "boundary")
        self.assertEqual((b["value"], b["n"], b["verdict"]), (1, 2, False))

    def test_no_turn_yet_is_not_a_boundary_sample(self):
        d = pr.summarise(pr.parse(log(
            "event=er-soul kind=working decision=spoken chars=20 heard=false mood=- ms=900 boundaryMs=-1",
        )))
        self.assertIsNone(self.row(d, "boundary")["verdict"])

    def test_the_old_check_keys_survive_for_json_readers(self):
        d = pr.summarise(pr.parse(log("event=er-presence line=1 interval=1 density=conversational kind=lag")))
        for k in ("rate", "late", "p95"):
            self.assertIn(k, d["checks"])


class SessionSummary(unittest.TestCase):
    """The whole summary over testdata/presence-session.txt — a synthetic log with two
    conversations (one closed by `stop reason=call-end`), known counts, and one repeat."""

    def setUp(self):
        self.text = FIXTURE.read_text(encoding="utf-8")
        self.s = pr.parse(self.text)
        self.d = pr.summarise(self.s)

    def test_slots_spoken_and_skips_by_reason(self):
        self.assertEqual(self.d["sessions"], 2)
        self.assertEqual(self.d["runs"], 3)
        self.assertEqual(self.d["slots"], 7)
        self.assertEqual(self.d["spoken"], 5)
        self.assertAlmostEqual(self.d["rate"], 5 / 7)
        # A skip with no reason is `unknown`, counted — never dropped from the denominator.
        self.assertEqual(self.d["skips"], {"silent": 1, "unknown": 1})

    def test_repeats_are_session_scoped(self):
        # h=aaaa0002 is said in two different turns of the first call: one repeat.
        self.assertEqual(self.d["repeats"], 1)
        self.assertEqual(self.d["hashed_lines"], 5)

    def test_render_latency_p50_and_p95(self):
        self.assertEqual(self.d["render_ms_p50"], 1240)
        self.assertEqual(self.d["render_ms_p95"], 1500)
        self.assertEqual(self.d["render_samples"], 3)

    def test_nothing_landed_after_the_reply_in_this_log(self):
        self.assertEqual(self.d["late_drops"], 0)
        self.assertEqual(self.d["late_working"], 0)
        self.assertEqual(self.d["late_total"], 0)

    def test_the_bar_rows_carry_value_and_n(self):
        rows = {b["name"]: b for b in self.d["bar"]}
        self.assertAlmostEqual(rows["speak-rate"]["value"], 5 / 7)
        self.assertEqual(rows["speak-rate"]["n"], 7)
        self.assertEqual(rows["repeats"]["value"], 1)
        self.assertEqual(rows["repeats"]["n"], 5)
        self.assertEqual(rows["late"]["n"], 10)   # soul renders (3) + working slots (7)
        self.assertEqual(rows["render-p95"]["value"], 1500)
        self.assertIs(rows["render-p95"]["verdict"], True)
        self.assertEqual(rows["boundary"]["n"], 3)
        self.assertIs(rows["boundary"]["verdict"], True)

    def test_the_fixture_fails_only_on_the_repeat(self):
        failed = [b["name"] for b in self.d["bar"] if b["verdict"] is False]
        self.assertEqual(failed, ["repeats"])
        self.assertFalse(self.d["passes_bar"])

    def test_the_report_prints_the_number_beside_each_criterion(self):
        text = pr.report(self.s)
        self.assertIn("  sessions             2", text)
        self.assertIn("  slots offered        7", text)
        self.assertIn("  skips by reason      silent 1, unknown 1", text)
        self.assertIn("  repeats              1", text)
        self.assertIn("  render latency       p50 1240 ms   p95 1500 ms   n=3", text)
        self.assertIn("fail    repeats", text)
        self.assertIn("pass    speak-rate", text)
        self.assertIn("verdict: fail", text)


class SkipReasons(unittest.TestCase):
    def test_a_skip_with_no_reason_is_unknown_not_dropped(self):
        d = pr.summarise(pr.parse(log(
            "event=er-presence line=1 interval=4000 density=conversational kind=lag",
            "event=er-presence skip line=2 density=conversational",
            "event=er-presence skip line=3 reason=silent density=conversational",
        )))
        self.assertEqual(d["slots"], 3)
        self.assertEqual(d["spoken"], 1)
        self.assertEqual(d["skips"], {"unknown": 1, "silent": 1})
        self.assertEqual(sum(d["skips"].values()), 2)


class RepeatSessions(unittest.TestCase):
    def test_the_same_line_in_a_second_call_is_not_a_repeat(self):
        # `stop reason=call-end` closes the conversation; the next call starting with the same
        # line is the app being consistent, not repeating itself.
        d = pr.summarise(pr.parse(log(
            "event=er-presence line=1 interval=1 density=conversational kind=soul h=aaaa0001",
            "event=er-presence stop reason=call-end lines=1",
            "event=er-presence line=1 interval=1 density=conversational kind=soul h=aaaa0001",
            "event=er-presence stop reason=reply lines=1",
        )))
        self.assertEqual(d["sessions"], 2)
        self.assertEqual(d["hashed_lines"], 2)
        self.assertEqual(d["repeats"], 0)
        self.assertIs(next(b for b in d["bar"] if b["name"] == "repeats")["verdict"], True)

    def test_a_second_log_file_is_a_second_session(self):
        one = pr.parse(log("event=er-presence line=1 interval=1 density=conversational kind=soul h=aaaa0001"))
        two = pr.parse(log("event=er-presence line=1 interval=1 density=conversational kind=soul h=aaaa0001"))
        one.merge(two)
        d = pr.summarise(one)
        self.assertEqual(d["sessions"], 2)
        self.assertEqual(d["repeats"], 0)
        self.assertEqual(d["slots"], 2)


class BarCli(unittest.TestCase):
    def run_script(self, *args):
        return subprocess.run(
            [sys.executable, str(SCRIPT), *args],
            capture_output=True, text=True, check=False,
        )

    def test_bar_on_a_session_with_no_offers_says_no_data_everywhere(self):
        with tempfile.TemporaryDirectory() as tmp:
            empty = Path(tmp) / "empty.log"
            # A log that never offered a slot: the bar has nothing to measure, so it must not
            # pass. Five rows, five `no data`.
            empty.write_text(log("event=er-soul-drop reason=late gen=1"), encoding="utf-8")
            out = self.run_script("--bar", str(empty))
            # No data is not a failure and not a pass. Exit 2 keeps a caller from reading
            # an empty run as a run that met the bar — or as one that missed it.
            self.assertEqual(2, out.returncode)
            self.assertEqual(5, out.stdout.count("    no data"))
            self.assertIn("0 of 5 criteria measured", out.stdout)
            self.assertIn("verdict: no data", out.stdout)
            self.assertNotIn("verdict: fail", out.stdout)
            self.assertNotIn("pass    ", out.stdout)

    def test_bar_on_the_fixture_names_each_criterion_with_its_number(self):
        out = self.run_script("--bar", str(FIXTURE))
        self.assertEqual(1, out.returncode, "the fixture repeats a line, so the bar fails")
        self.assertIn("fail    repeats          1", out.stdout)
        self.assertIn("pass    speak-rate     71%", out.stdout)
        self.assertIn("pass    render-p95 1500 ms", out.stdout)
        self.assertIn("5 of 5 criteria measured, 1 failed", out.stdout)

    def test_json_carries_the_same_numbers_as_the_text_summary(self):
        out = self.run_script("--json", str(FIXTURE))
        self.assertEqual(0, out.returncode, out.stderr)
        d = json.loads(out.stdout)
        self.assertEqual((2, 3, 7, 5), (d["sessions"], d["runs"], d["slots"], d["spoken"]))
        self.assertEqual({"silent": 1, "unknown": 1}, d["skips"])
        self.assertEqual(1, d["repeats"])
        self.assertEqual(1500, d["render_ms_p95"])
        self.assertEqual(1240, d["render_ms_p50"])
        self.assertEqual(len(d["bar"]), 5)

    def test_a_clean_run_passes_the_bar(self):
        with tempfile.TemporaryDirectory() as tmp:
            clean = Path(tmp) / "clean.log"
            clean.write_text(log(
                "event=er-presence line=1 interval=4000 density=conversational kind=lag h=aaaa0001",
                "event=er-presence line=2 interval=8000 density=conversational kind=soul h=aaaa0002",
                "event=er-presence line=3 interval=8000 density=conversational kind=soul h=aaaa0003",
                "event=er-soul kind=soul decision=spoken chars=20 heard=false mood=- ms=900 boundaryMs=2000",
                "event=er-presence stop reason=reply lines=3",
            ), encoding="utf-8")
            out = self.run_script("--bar", str(clean))
            self.assertEqual(0, out.returncode, out.stdout + out.stderr)
            self.assertIn("verdict: pass", out.stdout)


if __name__ == "__main__":
    unittest.main(verbosity=2)
