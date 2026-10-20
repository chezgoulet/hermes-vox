# sttbench-stream — the on-device streaming-STT A/B

Companion to `tools/sttbench` (offline models on a host CPU). This one measures
*streaming* recognizers **on the phone**, in real time, because the questions D5
asks — how long until a word is visible, how much RSS, does it keep up — only
have answers on the device.

| file | what it does |
|---|---|
| `build_ab_corpus.py` | renders the sttbench clips into `quiet` / `tv` (babble proxy) / `kitchen` (pink+clatter proxy) and `accent`, and derives word onsets by forced alignment (faster-whisper `small.en` → DP against the reference). Cached in `.cache/align`. |
| `run_ab.py` | pushes corpus + model dirs to the bench APK, launches one run per (arm, condition) over adb, waits for the `.done` marker, scores and prints the table. `--score-only` rescores raw JSON without the phone. |
| `check_onset_stability.py` | cross-checks the onsets with a second checkpoint (`base.en`) — the ±ms uncertainty quoted in `docs/STT-AB.md`. |
| `../../scripts/stt-ab.sh` | the one command: build `:bench`, install under the phone lock, run everything, restore Hermes Vox to the foreground. |

Device side is `android/bench/` (package `com.hermesvox.bench`).

```bash
python3 tools/sttbench-stream/build_ab_corpus.py --align   # needs faster-whisper + numpy + soundfile
scripts/stt-ab.sh --arms zip20m,zip0626 --conditions quiet,tv,kitchen,accent
python3 tools/sttbench-stream/run_ab.py --score-only --only '*-p0.json'
```

Generated data (`corpus/`, `.cache/`, `results/`) is gitignored: it is rebuilt by
those commands. Results quoted in the doc live in `results/raw/`, one JSON per run,
including every partial with its audio- and wall-clock timestamp, so any number in
the table can be recomputed or challenged without the phone.
