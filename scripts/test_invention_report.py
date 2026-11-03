#!/usr/bin/env python3
"""Tests for invention-report.py (#256). Stdlib only, no device.

  python3 scripts/test_invention_report.py
"""
import contextlib
import importlib.util
import io
import json
import tempfile
import unittest
from pathlib import Path

_spec = importlib.util.spec_from_file_location("inv", Path(__file__).with_name("invention-report.py"))
inv = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(inv)

D = 'The caller asked: "Do I have anything from the school in my inbox?", and the mind is still ' \
    'working on it. Right now it is using: gmail search, which returned 14 messages.'


def rec(spoken, mode="narrate", context="tool-returned-count", directive=D, run=1, **kw):
    r = {"run": run, "model": "gemma-4-E2B-it.litertlm", "backend": "cpu", "vox_md": True,
         "mode": mode, "context": context, "directive": directive, "raw": spoken, "spoken": spoken}
    r.update(kw)
    return r


class Classes(unittest.TestCase):
    def one(self, spoken, **kw):
        return inv.classify(rec(spoken, **kw), inv.defaultdict(set))

    def test_a_number_the_directive_carried_is_sourced(self):
        # Sourced by value: the directive's "14" vouches for "fourteen" too.
        self.assertEqual(self.one("Fourteen? Going through the 14 now.")["unsourced-number"], [])
        self.assertEqual(self.one("Fifteen of them.")["unsourced-number"], ["fifteen"])

    def test_digits_and_spelled_numbers_are_caught(self):
        self.assertEqual(self.one("About 3 of them look urgent.")["unsourced-number"], ["3"])
        self.assertEqual(self.one("Two of them are from the school.")["unsourced-number"], ["two"])

    def test_one_as_a_pronoun_is_not_a_number(self):
        self.assertEqual(self.one("One moment, still looking.")["unsourced-number"], [])
        self.assertEqual(self.one("Give me one minute.")["unsourced-number"], ["one"])

    def test_false_action_prefers_the_app_verdict(self):
        self.assertTrue(self.one("Okay, it's done.")["false-action"])
        self.assertFalse(self.one("Still checking for you.")["false-action"])
        self.assertFalse(self.one("I've booked it.", app_claims_result=False)["false-action"])

    def test_fact_candidates_carry_their_evidence(self):
        c = self.one("The note from Mrs Patel arrived on Tuesday.")
        self.assertIn("Patel", c["unsourced-fact"])
        self.assertIn("Tuesday", c["unsourced-fact"])
        self.assertIn("arrived", c["unsourced-fact"])

    def test_words_from_the_directive_are_not_facts(self):
        self.assertEqual(self.one("Still in your inbox, going through the school ones.")["unsourced-fact"], [])

    def test_silence_is_not_a_spoken_render(self):
        self.assertTrue(self.one("")["silent"])


class Summary(unittest.TestCase):
    def test_repetition_is_within_one_mode_context_and_run(self):
        recs = [rec("Still looking."), rec("still looking"), rec("Still looking.", context="long-wait"),
                rec("Still looking.", run=2)]
        d = inv.summarise(recs)
        self.assertEqual(d["table"]["narrate"]["repetition"]["count"], 1)

    def test_rates_are_per_spoken_render_and_silence_is_separate(self):
        recs = [rec("About 3 left."), rec(""), rec("Still on it.")]
        r = inv.summarise(recs)["table"]["all"]
        self.assertEqual((r["samples"], r["spoken"], r["silent"]), (3, 2, 1))
        self.assertEqual(r["unsourced-number"], {"count": 1, "rate": 0.5})

    def test_the_documented_json_shape(self):
        d = inv.summarise([rec("Still on it.", mode="greet", context="call-start")])
        self.assertEqual(set(d), {"corpus", "errors", "table", "examples"})
        row = d["table"]["greet"]
        for k in inv.CLASSES:
            self.assertEqual(set(row[k]), {"count", "rate"})
        self.assertEqual(set(row["shape"]), {"words_p50", "words_p95", "words_max",
                                             "chars_p50", "chars_p95", "chars_max", "markup"})

    def test_the_cli_reads_jsonl_and_skips_a_broken_line(self):
        with tempfile.TemporaryDirectory() as t:
            p = Path(t, "s.jsonl")
            p.write_text(json.dumps(rec("Still on it.")) + "\n{broken\n")
            with contextlib.redirect_stdout(io.StringIO()) as buf:
                self.assertEqual(inv.main(["x", str(p)]), 0)
                self.assertEqual(inv.main(["x", "--json", str(p)]), 0)
            self.assertIn('"table"', buf.getvalue())


if __name__ == "__main__":
    unittest.main(verbosity=1)
