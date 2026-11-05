#!/usr/bin/env python3
"""Tests for presence-reports.py and the daily cadence. No dependencies, no device, no gh.

  python3 scripts/test_presence_reports.py

The fixture is testdata/presence-reports.json: four synthetic issues, three parsable (two
Companion on one Pixel 7, one Voice max on a Pixel 8) and one malformed. That is enough to
prove the parse, both grouping levels, the count behind each row, the medians, the CSV, the
missing-metric accounting, the unacknowledged list, and that a bad block is counted and
listed rather than dropped.
"""
import csv
import importlib.util
import io
import json
import os
import subprocess
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
FIXTURE = ROOT / "testdata" / "presence-reports.json"

_spec = importlib.util.spec_from_file_location("presence_reports", HERE / "presence-reports.py")
assert _spec and _spec.loader, "cannot load presence-reports.py"
pr = importlib.util.module_from_spec(_spec)
# dataclasses resolves annotations through sys.modules while the module is executing.
sys.modules[_spec.name] = pr
_spec.loader.exec_module(pr)


def issues():
    return pr.fetch_issues(fixture=str(FIXTURE))


class Parsing(unittest.TestCase):
    def test_a_valid_block_is_read_including_the_counters_line(self):
        issue = next(i for i in issues() if i.number == 101)
        report, bad = pr.parse_issue(issue)
        self.assertIsNone(bad)
        assert report is not None
        self.assertEqual("0.9.1-nightly-abc1234", report.fields["build"])
        self.assertEqual("companion", report.fields["preset"])
        self.assertEqual("Google Pixel 7, Android 14", report.fields["device"])
        self.assertEqual("lan", report.fields["mind"])
        self.assertEqual(3200, report.number("e2_p50_ms"))
        self.assertEqual(20, report.number("turns"))
        self.assertEqual(2, report.number("answered_by_voice"))
        self.assertEqual(5, report.number("escalated"))
        self.assertEqual("gemma-e2b", report.fields["model"])

    def test_the_counters_line_also_parses_with_single_spaces(self):
        fields, notes = pr.parse_block([
            "turns: 4 lost: 2 answered_by_voice: 1 escalated: 3",
        ])
        self.assertEqual([], notes)
        self.assertEqual({"turns": "4", "lost": "2",
                          "answered_by_voice": "1", "escalated": "3"}, fields)

    def test_preset_detail_and_device_keep_their_spaces(self):
        fields, _ = pr.parse_block([
            "preset_detail: voice=spoken density=chatty",
            "device: Google Pixel 7, Android 14",
        ])
        self.assertEqual("voice=spoken density=chatty", fields["preset_detail"])
        self.assertEqual("Google Pixel 7, Android 14", fields["device"])

    def test_a_missing_block_is_missing_not_empty(self):
        issue = pr.Issue(number=9, title="no block", body="Just prose, no diagnostic.")
        block, reason = pr.extract_block(issue.body)
        self.assertIsNone(block)
        self.assertEqual("no vox-diag block", reason)
        report, bad = pr.parse_issue(issue)
        self.assertIsNone(report)
        assert bad is not None
        self.assertEqual("no vox-diag block", bad.reason)

    def test_a_block_missing_a_required_field_is_malformed(self):
        issue = next(i for i in issues() if i.number == 104)
        report, bad = pr.parse_issue(issue)
        self.assertIsNone(report)
        assert bad is not None
        self.assertIn("missing mind", bad.reason)


class Aggregation(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.out = os.path.join(self.tmp.name, "aggregate.csv")
        self.mal = os.path.join(self.tmp.name, "malformed.csv")
        self.raw = issues()
        (self.reports, self.malformed, self.groups,
         self.missing, self.unack) = pr.build(self.raw, self.out, self.mal)

    def tearDown(self):
        self.tmp.cleanup()

    def test_counts_and_grouping(self):
        self.assertEqual(3, len(self.reports))
        self.assertEqual(1, len(self.malformed))
        self.assertEqual([("companion", "Google Pixel 7, Android 14"),
                          ("voice-max", "Google Pixel 8, Android 15")],
                         [(g.preset, g.device) for g in self.groups])
        companion = self.groups[0]
        self.assertEqual(2, len(companion.reports))
        self.assertEqual({101, 102}, {r.issue.number for r in companion.reports})
        voice = self.groups[1]
        self.assertEqual(1, len(voice.reports))

    def test_medians_and_sums(self):
        companion = self.groups[0]
        # Nearest-rank p50 (lower middle for an even count), the app's convention.
        self.assertEqual(2800, companion.e2_p50)
        self.assertEqual(32, sum(companion.values("turns")))
        self.assertEqual(3, sum(companion.values("answered_by_voice")))
        self.assertEqual(8, sum(companion.values("escalated")))
        voice = self.groups[1]
        self.assertEqual(900, voice.e1_p50)
        self.assertEqual(4100, voice.e2_p50)
        self.assertEqual([1], voice.values("lost"))

    def test_malformed_is_counted_and_listed(self):
        self.assertEqual(104, self.malformed[0].issue.number)
        self.assertIn("missing mind", self.malformed[0].reason)

    def test_missing_metrics_are_accounted_not_zeroed(self):
        # E1 and lost exist on exactly one report (the Voice max block); they are omitted by
        # the other two, and the aggregate says so rather than reading the gap as 0.
        self.assertEqual(2, self.missing["e1_p50_ms"])
        self.assertEqual(2, self.missing["lost"])
        self.assertNotIn("turns", self.missing)

    def test_unacknowledged_ignores_bots_and_the_author(self):
        self.assertEqual({102, 104}, {i.number for i in self.unack})
        # 103 has a bot comment and a human reply — the human reply is the acknowledgement.
        self.assertNotIn(103, {i.number for i in self.unack})
        # 101's only reply is from someone other than the reporter.
        self.assertNotIn(101, {i.number for i in self.unack})

    def test_csv_has_one_row_per_group_and_the_report_count(self):
        with open(self.out, newline="", encoding="utf-8") as fh:
            rows = list(csv.DictReader(fh))
        self.assertEqual(2, len(rows))
        self.assertEqual("2", rows[0]["reports"])
        self.assertEqual("2800", rows[0]["e2_p50_ms"])
        self.assertEqual("2", rows[0]["e2_n"])
        self.assertEqual("32", rows[0]["turns_sum"])
        self.assertEqual("2", rows[0]["turns_n"])
        self.assertEqual("0", rows[0]["lost_n"])
        self.assertEqual("gemma-e2b", rows[0]["models"])
        self.assertEqual("#101 #102", rows[0]["issues"])
        self.assertEqual("1", rows[1]["reports"])
        self.assertEqual("900", rows[1]["e1_p50_ms"])
        self.assertEqual("1", rows[1]["lost_sum"])

    def test_malformed_csv_never_drops_the_issue(self):
        with open(self.mal, newline="", encoding="utf-8") as fh:
            rows = list(csv.DictReader(fh))
        self.assertEqual(1, len(rows))
        self.assertEqual("104", rows[0]["number"])
        self.assertIn("missing mind", rows[0]["reason"])


class Cli(unittest.TestCase):
    def test_main_prints_a_summary_and_writes_the_csv(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = os.path.join(tmp, "aggregate.csv")
            buf = io.StringIO()
            with redirect_stdout(buf):
                rc = pr.main(["--fixture", str(FIXTURE), "--out", out])
            self.assertEqual(0, rc)
            text = buf.getvalue()
            self.assertIn("4 issue(s), 3 parsed, 1 malformed/missing", text)
            self.assertIn("companion — 2 report(s) across 1 device(s)", text)
            self.assertIn("Google Pixel 7, Android 14 — 2 report(s)", text)
            self.assertIn("Malformed or missing blocks (1)", text)
            self.assertIn("#104", text)
            self.assertIn("e1_p50_ms: 2/3 parsed report(s) omit it", text)
            self.assertIn("Unacknowledged reports (2)", text)
            self.assertTrue(os.path.exists(out))
            self.assertTrue(os.path.exists(out + ".malformed.csv"))

    def test_json_shape(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = os.path.join(tmp, "aggregate.csv")
            buf = io.StringIO()
            with redirect_stdout(buf):
                rc = pr.main(["--fixture", str(FIXTURE), "--out", out, "--json"])
            self.assertEqual(0, rc)
            data = json.loads(buf.getvalue())
            self.assertEqual(3, data["parsed"])
            self.assertEqual(1, len(data["malformed"]))
            self.assertEqual(2, len(data["groups"]))
            self.assertEqual(2, data["groups"][0]["reports"])
            self.assertEqual(2800, data["groups"][0]["e2_p50_ms"])
            self.assertEqual(2, data["missing"]["e1_p50_ms"])
            self.assertEqual([102, 104],
                             [i["number"] for i in data["unacknowledged"]])


class DailyCadence(unittest.TestCase):
    """The digest is allowed to be quiet: same numbers, no post. The rehearsal writes its own
    snapshot, rolling history and hash, so a dry run cannot consume the real change."""

    def run_daily(self, state):
        env = dict(os.environ)
        env["PRESENCE_REPORTS_STATE"] = state
        return subprocess.run(
            ["bash", str(HERE / "presence-reports-daily.sh"),
             "--fixture", str(FIXTURE), "--dry-run"],
            cwd=ROOT, env=env, capture_output=True, text=True, check=False,
        )

    def test_first_run_would_post_and_the_second_is_silent(self):
        with tempfile.TemporaryDirectory() as tmp:
            first = self.run_daily(tmp)
            self.assertEqual(0, first.returncode, first.stderr)
            self.assertIn("numbers changed", first.stdout)
            self.assertIn("--dry-run", first.stdout)
            self.assertIn("Unacknowledged reports (2)", first.stdout)

            second = self.run_daily(tmp)
            self.assertEqual(0, second.returncode, second.stderr)
            self.assertIn("numbers unchanged", second.stdout)
            self.assertNotIn("digest worth posting", second.stdout)

            rolling = Path(tmp) / "rolling.dry-run.csv"
            self.assertTrue(rolling.exists())
            self.assertFalse((Path(tmp) / "rolling.csv").exists(),
                             "a rehearsal must not write the real history")
            lines = rolling.read_text(encoding="utf-8").splitlines()
            # header + two groups on each of the two runs
            self.assertEqual(5, len(lines))
            self.assertTrue(lines[0].startswith("snapshot_utc,preset,device,reports"))

    def test_an_empty_label_never_posts(self):
        with tempfile.TemporaryDirectory() as tmp:
            empty = Path(tmp) / "empty.json"
            empty.write_text("[]", encoding="utf-8")
            env = dict(os.environ)
            env["PRESENCE_REPORTS_STATE"] = tmp
            out = subprocess.run(
                ["bash", str(HERE / "presence-reports-daily.sh"),
                 "--fixture", str(empty), "--dry-run"],
                cwd=ROOT, env=env, capture_output=True, text=True, check=False,
            )
            self.assertEqual(0, out.returncode, out.stderr)
            self.assertIn("no reports — no digest posted", out.stdout)
            self.assertFalse((Path(tmp) / "last.sha256.dry-run").exists())
            self.assertFalse((Path(tmp) / "rolling.dry-run.csv").exists())


if __name__ == "__main__":
    unittest.main()
