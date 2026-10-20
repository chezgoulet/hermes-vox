#!/usr/bin/env python3
"""Drive the on-device streaming-STT A/B and score it (docs/STT-AB.md).

Pipeline: push the corpus + one model directory to the bench APK's external
files dir, launch com.hermesvox.bench/.SttBenchActivity once per (arm,
condition), wait for the `.done` marker, pull the JSON, then score it here:

  partial latency   first partial that contains a reference word (aligned by DP)
                    minus that word's ground-truth onset, pooled per arm/condition;
                    median / p90 / p95. Also the post-word delay (same, minus the
                    word's end) so a long word's duration is not read as model lag.
  finalisation      last speech sample -> the final text existing, given the fixed
                    trailing-silence rule both arms share (500 ms); plus the decode
                    tail spent inside that window.
  WER               final text vs reference, sttbench normalization.
  stability         Levenshtein edits between successive partials / reference words
                    ("revisions per word"), and how many words appeared then vanished.
  RTF / RSS         decode ms / audio ms, VmHWM after the run.
  battery           BATTERY_PROPERTY_ENERGY_COUNTER delta per audio-hour.
  cold start        model load ms in a fresh process.

    python3 tools/sttbench-stream/run_ab.py --arms zip20m,zip0626 --conditions quiet
    python3 tools/sttbench-stream/run_ab.py --score-only

Always run it under the phone lock; scripts/stt-ab.sh does that.
"""
import argparse
import json
import os
import re
import statistics
import subprocess
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
CORPUS = HERE / "corpus"
RESULTS = HERE / "results"
DEFAULT_MODELS = Path("/home/c/stt-ab-models")

PKG = "com.hermesvox.bench"
ACT = "com.hermesvox.bench/.SttBenchActivity"
# The measurement phone is the operator's, not the project's: no serial is baked in.
SERIAL = os.environ.get("SERIAL", "")
if not SERIAL:
    raise SystemExit("run_ab: set SERIAL to the device serial (see: adb devices)")

ARMS = {
    # arm id -> model dir name, as extracted from the upstream tarball
    "zip20m": "sherpa-onnx-streaming-zipformer-en-20M-2023-02-17",
    "zip0626": "sherpa-onnx-streaming-zipformer-en-2023-06-26",
    # Nemotron 0.6B: one checkpoint, one ONNX export per chunk size. The chunk
    # size is baked into the encoder, so each export is a separate directory.
    "nemotron160": "sherpa-onnx-nemotron-speech-streaming-en-0.6b-160ms-int8-2026-04-25",
    "nemotron560": "sherpa-onnx-nemotron-speech-streaming-en-0.6b-560ms-int8-2026-04-25",
}
ARM_LABEL = {
    "zip20m": "Zipformer streaming 20M (2023-02-17, int8)",
    "zip0626": "Zipformer streaming en (2023-06-26, int8)",
    "nemotron160": "Nemotron 0.6B streaming 160ms (2026-04-25, int8)",
    "nemotron560": "Nemotron 0.6B streaming 560ms (2026-04-25, int8)",
}

# arm id -> (tarball, sha256, bytes). Pins are of the tarballs actually measured;
# `--fetch` refuses anything that does not match both the hash and the size.
RELEASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/"
ARTIFACTS = {
    "zip20m": ("sherpa-onnx-streaming-zipformer-en-20M-2023-02-17.tar.bz2",
               "9c559283e8498d3fe95913c79ca1cb454bb26281ac2b102b41306c7d752765d9",
               127887156),
    # Verified 2026-10-19 against a fresh download: 310,414,022 bytes. The
    # "74685102…" once quoted in docs/STT-AB.md is not this artifact.
    "zip0626": ("sherpa-onnx-streaming-zipformer-en-2023-06-26.tar.bz2",
                "639e25b578e9e997131402199419c13a941f8e4e198e2da1ce57dbf5cf401282",
                310414022),
    "nemotron160": ("sherpa-onnx-nemotron-speech-streaming-en-0.6b-160ms-int8-2026-04-25.tar.bz2",
                    "0ae73a41cd51599dc7cac9ac083d9d35de53d762ca45923505fde47a3751814b",
                    463945198),
    "nemotron560": ("sherpa-onnx-nemotron-speech-streaming-en-0.6b-560ms-int8-2026-04-25.tar.bz2",
                    "78e2b79fcf7271553a74402a76b771b09ea40117a39566a79f52235b23db6358",
                    463945051),
}


def sha256_file(p: Path) -> str:
    import hashlib
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


def fetch(arms, models_dir: Path):
    """Download, verify and extract each arm's tarball into --models."""
    import tarfile
    import urllib.request
    models_dir.mkdir(parents=True, exist_ok=True)
    for arm in arms:
        tarball, pin, nbytes = ARTIFACTS[arm]
        dst = models_dir / tarball
        if dst.exists() and sha256_file(dst) != pin:
            print(f"[{arm}] existing {tarball} does not match the pin — replacing")
            dst.unlink()
        if not dst.exists():
            print(f"[{arm}] fetching {tarball} ({nbytes / 1e6:.1f} MB)…", flush=True)
            tmp = dst.with_suffix(dst.suffix + ".part")
            urllib.request.urlretrieve(RELEASE + tarball, tmp)
            tmp.rename(dst)
        got = sha256_file(dst)
        if got != pin:
            raise SystemExit(f"[{arm}] sha256 mismatch for {tarball}: {got}")
        if dst.stat().st_size != nbytes:
            raise SystemExit(f"[{arm}] size mismatch for {tarball}: "
                             f"{dst.stat().st_size} != {nbytes}")
        if not (models_dir / tarball.replace(".tar.bz2", "")).exists():
            with tarfile.open(dst) as t:
                t.extractall(models_dir, filter="data")
        print(f"[{arm}] ok (sha256 + size verified)")


# ------------------------------------------------------------------ adb helpers
def adb(args, check=True, capture=True):
    cmd = ["adb", "-s", SERIAL] + args
    r = subprocess.run(cmd, capture_output=capture, text=True)
    if check and r.returncode != 0:
        raise SystemExit(f"adb {' '.join(args)} failed: {r.stderr.strip()}")
    return r.stdout if capture else r


def shell(cmd, check=True):
    return adb(["shell", cmd], check=check)


def device_files():
    return f"/sdcard/Android/data/{PKG}/files"


def push_dir(local: Path, remote: str):
    # adb push of a directory contents; mkdir first so it never nests by basename
    shell(f"mkdir -p {remote}")
    r = subprocess.run(["adb", "-s", SERIAL, "push", str(local) + "/.", remote],
                       capture_output=True, text=True)
    if r.returncode != 0:
        raise SystemExit(f"adb push {local} -> {remote} failed: {r.stderr.strip()}")


def model_files(arm: str, models_dir: Path):
    d = models_dir / ARMS[arm]
    if not d.exists():
        raise SystemExit(f"{d} missing — extract the arm tarball first")
    def pick(prefix):
        # Zipformer exports name files "encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx";
        # NeMo/Nemotron exports name them "encoder.int8.onnx" (no dash, no epoch suffix).
        for pat in (f"{prefix}-*int8.onnx", f"{prefix}.*int8.onnx",
                    f"{prefix}-*.onnx", f"{prefix}.*.onnx"):
            cands = sorted(d.glob(pat))
            if cands:
                return cands[0]
        raise SystemExit(f"{d}: no {prefix}*.onnx")
    return {
        "encoder": pick("encoder"),
        "decoder": pick("decoder"),
        "joiner": pick("joiner"),
        "tokens": "tokens.txt",
    }


def prepare(arms, models_dir, conditions):
    """Push corpus once and each arm's model directory."""
    push_dir(CORPUS, f"{device_files()}/corpus")
    for arm in arms:
        names = model_files(arm, models_dir)
        remote = f"{device_files()}/models/{arm}"
        shell(f"rm -rf {remote}; mkdir -p {remote}")
        for f in names.values():
            r = subprocess.run(["adb", "-s", SERIAL, "push",
                                str(models_dir / ARMS[arm] / f), f"{remote}/{f}"],
                               capture_output=True, text=True)
            if r.returncode != 0:
                raise SystemExit(f"push model {f} failed: {r.stderr.strip()}")
    shell(f"mkdir -p {device_files()}/results {device_files()}/runs")


# ------------------------------------------------------------------ run
def write_config(run_id, arm, condition, clips, models_dir, threads, chunk_ms,
                 tail_ms, warmup):
    names = model_files(arm, models_dir)
    remote_models = f"{device_files()}/models/{arm}"
    cfg = {
        "run_id": run_id,
        "model": {"id": arm,
                  **{k: f"{remote_models}/{v}" for k, v in names.items()}},
        "threads": threads,
        "chunk_ms": chunk_ms,
        "trailing_silence_ms": tail_ms,
        "max_tail_ms": 3000,
        "realtime": True,
        "warmup": warmup,
        "reps": 1,
        "clips": [{
            "id": c["id"], "condition": c["condition"], "category": c["category"],
            "wav": f"{device_files()}/corpus/{c['wav']}",
            "reference": c["reference"],
        } for c in clips],
    }
    local = RESULTS / "runs" / f"{run_id}.json"
    local.parent.mkdir(parents=True, exist_ok=True)
    local.write_text(json.dumps(cfg, indent=1))
    remote = f"{device_files()}/runs/{run_id}.json"
    subprocess.run(["adb", "-s", SERIAL, "push", str(local), remote],
                   check=True, capture_output=True, text=True)
    return remote


def wait_idle(max_s=900, need=3):
    """Wait until the phone is quiet: our activity focused, or the app that tends to
    run here (Hermes Vox, another agent's measurements) below 8% CPU for a few
    checks. Politeness on a shared device — a run started into someone else's test
    is both unfair to them and noisy for us."""
    stable, t0 = 0, time.time()
    while time.time() - t0 < max_s:
        focus = shell("dumpsys window | grep -m1 mCurrentFocus", check=False)
        cpu = shell("dumpsys cpuinfo | grep -m1 com.hermesvox | awk '{print $1}'", check=False)
        try:
            pct = float(cpu.strip().rstrip('%'))
        except ValueError:
            pct = 0.0
        if PKG in focus or pct < 8.0:
            stable += 1
        else:
            stable = 0
        if stable >= need:
            return True
        time.sleep(10)
    return False


class RunInterrupted(Exception):
    pass


def run_one(run_id, cfg_remote, timeout_s=900, wait=True):
    if wait:
        wait_idle()
    shell(f"am force-stop {PKG}")
    shell(f"rm -f {device_files()}/results/{run_id}.done "
          f"{device_files()}/results/{run_id}.json "
          f"{device_files()}/results/{run_id}.error")
    shell(f"am start -n {ACT} -e config {cfg_remote}")
    time.sleep(3)
    focus = shell("dumpsys window | grep -m1 mCurrentFocus", check=False)
    if PKG not in focus:
        raise SystemExit(f"{run_id}: bench activity did not take the foreground "
                         f"({focus.strip()}) — another app is in use; aborting")
    t0 = time.time()
    while time.time() - t0 < timeout_s:
        if shell(f"ls {device_files()}/results/{run_id}.done 2>/dev/null", check=False).strip():
            break
        err = shell(f"ls {device_files()}/results/{run_id}.error 2>/dev/null", check=False).strip()
        if err:
            shell(f"cat {device_files()}/results/{run_id}.error", check=False)
            raise SystemExit(f"{run_id}: harness error (see above)")
        time.sleep(2)
    else:
        raise SystemExit(f"{run_id}: timed out after {timeout_s}s")
    local = RESULTS / "raw" / f"{run_id}.json"
    local.parent.mkdir(parents=True, exist_ok=True)
    r = subprocess.run(["adb", "-s", SERIAL, "pull",
                        f"{device_files()}/results/{run_id}.json", str(local)],
                       capture_output=True, text=True)
    if r.returncode != 0:
        raise SystemExit(f"pull {run_id} failed: {r.stderr.strip()}")
    res = json.loads(local.read_text())
    if res.get("interrupted"):
        local.unlink(missing_ok=True)
        raise RunInterrupted(f"{run_id}: activity was backgrounded mid-run")
    return res


# ------------------------------------------------------------------ scoring
ABBR = {"mr": "mister", "mrs": "missus", "dr": "doctor", "st": "saint", "ok": "okay"}


def norm_words(s):
    s = s.lower().replace("-", " ")
    s = re.sub(r"[^a-z' ]", " ", s)
    return [ABBR.get(w.strip("'"), w.strip("'")) for w in s.split() if w.strip("'")]


def edit_ops(a, b):
    """Levenshtein distance plus the backtrace of matched pairs."""
    n, m = len(a), len(b)
    d = [[0] * (m + 1) for _ in range(n + 1)]
    for i in range(n + 1):
        d[i][0] = i
    for j in range(m + 1):
        d[0][j] = j
    for i in range(1, n + 1):
        for j in range(1, m + 1):
            d[i][j] = min(d[i - 1][j] + 1, d[i][j - 1] + 1,
                          d[i - 1][j - 1] + (a[i - 1] != b[j - 1]))
    i, j, pairs = n, m, []
    while i > 0 and j > 0:
        if d[i][j] == d[i - 1][j - 1] + (a[i - 1] != b[j - 1]):
            if a[i - 1] == b[j - 1]:
                pairs.append((i - 1, j - 1))
            i -= 1
            j -= 1
        elif d[i][j] == d[i - 1][j] + 1:
            i -= 1
        else:
            j -= 1
    return d[n][m], list(reversed(pairs))


def pct(vals, p):
    if not vals:
        return None
    vals = sorted(vals)
    k = (len(vals) - 1) * p / 100.0
    lo, hi = int(k), min(int(k) + 1, len(vals) - 1)
    return vals[lo] + (vals[hi] - vals[lo]) * (k - lo)


def score_clip(rec, truth):
    """Latency, stability and WER for one clip result against its manifest row."""
    ref = norm_words(truth["reference"]) if truth["reference"] else []
    words = truth.get("words") or []
    partials = rec.get("partials", [])
    out = {"id": rec["id"], "condition": rec["condition"], "category": rec.get("category"),
           "seconds": rec["seconds"], "final": rec["final_text"]}

    if ref:
        hyp = norm_words(rec["final_text"])
        e, _ = edit_ops(ref, hyp)
        out["wer"] = e / len(ref)
        out["ref_words"] = len(ref)

        lat, post, appeared = [], [], set()
        lat_by_index = {}
        # walk partials in time order; a reference word "appears" at the first
        # partial whose tokens align it to an equal token
        prev_tokens = []
        revisions = 0
        for p in partials:
            toks = norm_words(p["t"])
            pairs = dict(edit_ops(ref, toks)[1])
            for i, tok in pairs.items():
                if i not in appeared and i < len(words):
                    appeared.add(i)
                    lat.append(p["w"] - words[i]["onset_ms"])
                    post.append(p["w"] - words[i]["end_ms"])
                    lat_by_index[i] = p["w"] - words[i]["onset_ms"]
            # stability: edits between consecutive snapshots
            if prev_tokens or toks:
                revisions += edit_ops(prev_tokens, toks)[0]
            prev_tokens = toks
        out["partial_latency_ms"] = lat
        out["post_word_ms"] = post
        out["lat_by_index"] = lat_by_index
        out["word_coverage"] = len(appeared) / len(ref)
        out["revisions_per_word"] = revisions / len(ref)
        out["partials"] = len(partials)
    else:
        out["wer"] = None
        out["hallucinated"] = bool(norm_words(rec["final_text"]))

    out["decode_ms"] = rec["decode_ms"]
    out["rtf"] = rec["rtf"]
    out["rss_kb"] = rec["rss_kb"]
    out["feed_lag_mean_ms"] = rec["feed_lag_mean_ms"]
    out["feed_lag_max_ms"] = rec["feed_lag_max_ms"]
    eps = rec.get("endpoints") or []
    if eps and rec.get("endpoint_wall_ms", -1) > 0:
        out["finalisation_ms"] = rec["endpoint_wall_ms"] - rec["clip_end_wall_ms"]
        out["tail_decode_ms"] = rec["tail_decode_ms"]
        out["endpoints"] = len(eps)
    return out


def paired(table_runs, arms, conds):
    """Latency on the words BOTH arms actually surfaced.

    The 20M arm drops words (its coverage is ~60%), and the words it drops are
    disproportionately the ones with the longest wait, so its unpaired median
    flatters it. This restricts the comparison to the same words in the same clips.
    """
    out = {}
    by_key = {}
    for arm in arms:
        for cond in conds:
            for r in table_runs.get((arm, cond), []):
                for i, v in (r.get("lat_by_index") or {}).items():
                    by_key.setdefault((cond, r["id"], int(i)), {})[arm] = v
    for cond in conds:
        common = {k: v for k, v in by_key.items() if k[0] == cond and all(a in v for a in arms)}
        row = {"words": len(common)}
        for arm in arms:
            vals = [v[arm] for v in common.values()]
            row[arm] = {"median": round(statistics.median(vals), 1) if vals else None,
                        "p95": round(pct(vals, 95), 1) if vals else None}
        diffs = [common[k][arms[1]] - common[k][arms[0]] for k in common] if len(arms) == 2 else []
        row["median_diff_ms"] = round(statistics.median(diffs), 1) if diffs else None
        out[cond] = row
    return out


def summarize(runs):
    """Aggregate one (arm, condition) pool of scored clips."""
    words = [w for r in runs for w in r.get("partial_latency_ms", [])]
    post = [w for r in runs for w in r.get("post_word_ms", [])]
    fin = [r["finalisation_ms"] for r in runs if "finalisation_ms" in r]
    wer_n = sum(r["ref_words"] for r in runs if r.get("ref_words"))
    wer_e = sum(r["wer"] * r["ref_words"] for r in runs if r.get("ref_words"))
    audio_s = sum(r["seconds"] for r in runs)
    dec_ms = sum(r["decode_ms"] for r in runs)
    return {
        "clips": len(runs),
        "audio_s": round(audio_s, 1),
        "wer": round(wer_e / wer_n, 4) if wer_n else None,
        "wer_words": wer_n,
        "latency_median": round(statistics.median(words), 1) if words else None,
        "latency_p90": round(pct(words, 90), 1) if words else None,
        "latency_p95": round(pct(words, 95), 1) if words else None,
        "latency_mean": round(statistics.fmean(words), 1) if words else None,
        "post_word_median": round(statistics.median(post), 1) if post else None,
        "post_word_p95": round(pct(post, 95), 1) if post else None,
        "finalisation_median": round(statistics.median(fin), 1) if fin else None,
        "finalisation_p95": round(pct(fin, 95), 1) if fin else None,
        "tail_decode_median": round(statistics.median(
            [r["tail_decode_ms"] for r in runs if "tail_decode_ms" in r]), 1) if fin else None,
        "revisions_per_word": round(statistics.fmean(
            [r["revisions_per_word"] for r in runs if "revisions_per_word" in r]), 2)
            if any("revisions_per_word" in r for r in runs) else None,
        "rtf": round(dec_ms / (audio_s * 1000), 3) if audio_s else None,
        "rss_peak_mb": round(max(r["rss_kb"] for r in runs) / 1024, 1) if runs else None,
        "words_observed": len(words),
        "word_coverage": round(statistics.fmean(
            [r["word_coverage"] for r in runs if "word_coverage" in r]), 3)
            if any("word_coverage" in r for r in runs) else None,
        "feed_lag_mean_ms": round(statistics.fmean([r["feed_lag_mean_ms"] for r in runs]), 2),
        "feed_lag_max_ms": round(max(r["feed_lag_max_ms"] for r in runs), 1),
        "hallucinations": sum(1 for r in runs if r.get("hallucinated")),
    }


def aggregate(results, manifest):
    truth = {(m["id"], m["condition"]): m for m in manifest}
    per = {}
    meta = {}
    for res in results:
        arm, cond = res["_arm"], res["_condition"]
        scored = [score_clip(c, truth[(c["id"], c["condition"])])
                  for c in res["clips"] if not c.get("warmup")]
        per.setdefault((arm, cond), []).extend(scored)
        m = meta.setdefault(arm, {"load_ms": [], "energy": [], "rss_peak": [], "runs": 0,
                                  "cpu_ticks": 0})
        m["load_ms"].append(res.get("load_ms"))
        m["rss_peak"].append(res.get("rss_peak_kb", 0))
        m["energy"].append(res.get("energy", {}))
        m["cpu_ticks"] += res.get("cpu_ticks_total", 0)
        m["runs"] += 1
    table = {}
    for (arm, cond), runs in per.items():
        table.setdefault(arm, {})[cond] = summarize(runs)
    arms = sorted({a for a, _ in per})
    conds = sorted({c for _, c in per})
    table["_paired"] = paired(per, arms, conds)
    table["_arms"] = arms
    table["_conds"] = conds
    for arm, m in meta.items():
        audio_s = sum(v["audio_s"] for c in table[arm].values() for v in [c])
        nwh = sum(e.get("energy_nwh_delta", 0) for e in m["energy"])
        # nWh -> mWh per audio-hour
        wh = nwh / 1e6
        table[arm]["_meta"] = {
            "runs": m["runs"],
            "cold_start_ms": round(statistics.median([x for x in m["load_ms"] if x]), 1),
            "rss_peak_mb": round(max(m["rss_peak"]) / 1024, 1),
            "energy_mwh_total": round(wh, 4),
            "audio_s": round(audio_s, 1),
            "battery_mwh_per_audio_hour": round(wh / (audio_s / 3600), 2) if audio_s else None,
            "energy_charge_uah": sum(e.get("charge_uah_delta", 0) for e in m["energy"]),
            # CPU seconds per audio-hour: the honest fallback when the energy counter
            # is unavailable (device on USB power). 100 ticks = 1 s on Android.
            "cpu_s_per_audio_hour": round((m["cpu_ticks"] / 100) / (audio_s / 3600), 1)
            if audio_s else None,
        }
    return table


def md_table(table, conditions, metric_rows):
    arms = [a for a in table if not a.startswith("_")]
    out = ["| metric | " + " | ".join(f"{ARM_LABEL[a].split('(')[0].strip()} · {c}" for a in arms for c in conditions) + " |",
           "|---|" + "---|" * (len(arms) * len(conditions))]
    for label, key in metric_rows:
        cells = []
        for a in arms:
            for c in conditions:
                s = table.get(a, {}).get(c, {})
                v = s.get(key)
                cells.append("—" if v is None else (f"{v:.2f}" if isinstance(v, float) else str(v)))
        out.append(f"| {label} | " + " | ".join(cells) + " |")
    return "\n".join(out)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--arms", default="zip20m,zip0626")
    ap.add_argument("--conditions", default="quiet,tv,kitchen")
    ap.add_argument("--models", type=Path, default=DEFAULT_MODELS)
    ap.add_argument("--threads", type=int, default=4)
    ap.add_argument("--chunk-ms", type=int, default=20)
    ap.add_argument("--tail-ms", type=int, default=500)
    ap.add_argument("--passes", type=int, default=1, help="repeat every arm/condition")
    ap.add_argument("--retries", type=int, default=2,
                    help="retries when the bench loses the foreground (shared phone)")
    ap.add_argument("--tag", default="", help="suffix for run ids (keeps raw files apart)")
    ap.add_argument("--prepare", action="store_true", help="push corpus + models")
    ap.add_argument("--fetch", action="store_true",
                    help="download + verify + extract the arm tarballs into --models")
    ap.add_argument("--score-only", action="store_true")
    args = ap.parse_args()

    manifest = [json.loads(l) for l in open(CORPUS / "manifest.jsonl")]
    arms = args.arms.split(",")
    conds = args.conditions.split(",")
    RESULTS.mkdir(parents=True, exist_ok=True)

    if not args.score_only:
        if args.fetch:
            fetch(arms, args.models)
        if args.prepare:
            prepare(arms, args.models, conds)
        results = []
        for arm in arms:
            for cond in conds:
                clips = [m for m in manifest
                         if m["condition"] == cond
                         and (m["category"] != "silence" or cond == "quiet")]
                if not clips:
                    raise SystemExit(f"no clips for condition {cond}")
                for p in range(args.passes):
                    run_id = f"{arm}-{cond}{('-' + args.tag) if args.tag else ''}-p{p}"
                    cfg = write_config(run_id, arm, cond, clips, args.models,
                                       args.threads, args.chunk_ms, args.tail_ms,
                                       warmup=True)
                    print(f"[{run_id}] {len(clips)} clips "
                          f"({sum(c['seconds'] for c in clips):.0f}s audio)…", flush=True)
                    t0 = time.time()
                    for attempt in range(args.retries + 1):
                        try:
                            res = run_one(run_id, cfg)
                            break
                        except RunInterrupted as e:
                            print(f"[{run_id}] {e} — attempt {attempt + 1}/"
                                  f"{args.retries + 1}", flush=True)
                            if attempt == args.retries:
                                print(f"[{run_id}] giving up; cell left empty", flush=True)
                                res = None
                                break
                            time.sleep(20)
                    if res is None:
                        continue
                    res["_arm"], res["_condition"], res["_run"] = arm, cond, run_id
                    (RESULTS / "raw" / f"{run_id}.json").write_text(json.dumps(res, indent=1))
                    results.append(res)
                    print(f"[{run_id}] done in {time.time() - t0:.0f}s", flush=True)
    else:
        results = []
        for f in sorted((RESULTS / "raw").glob("*.json")):
            res = json.loads(f.read_text())
            res["_arm"] = res["model_id"]
            res["_condition"] = next(c["condition"] for c in res["clips"])
            res["_run"] = f.stem
            results.append(res)

    table = aggregate(results, manifest)
    (RESULTS / "summary.json").write_text(json.dumps(table, indent=1))
    rows = [("WER %", "wer"), ("partial latency median ms", "latency_median"),
            ("p90 ms", "latency_p90"), ("p95 ms", "latency_p95"),
            ("post-word median ms", "post_word_median"),
            ("finalisation median ms", "finalisation_median"),
            ("finalisation p95 ms", "finalisation_p95"),
            ("revisions / word", "revisions_per_word"),
            ("words seen in partials", "word_coverage"),
            ("RTF", "rtf"), ("RSS peak MB", "rss_peak_mb"),
            ("feed lag mean ms", "feed_lag_mean_ms")]
    print()
    print(md_table(table, conds, rows))
    for arm in table:
        if arm.startswith("_"):
            continue
        print(f"\n{arm}: {json.dumps(table[arm]['_meta'])}")
    print("\npaired (same words, both arms):")
    for cond, row in (table.get("_paired") or {}).items():
        print(f"  {cond:8s} n={row['words']:4d} " +
              "  ".join(f"{a} med={row[a]['median']} p95={row[a]['p95']}"
                        for a in table.get("_arms", []) if isinstance(row.get(a), dict)) +
              f"  median diff={row['median_diff_ms']} ms")


if __name__ == "__main__":
    main()
