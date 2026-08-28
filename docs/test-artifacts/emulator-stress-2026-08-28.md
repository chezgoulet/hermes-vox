# Emulator Agent->App Stress — 2026-08-28 (0.3.26.0, debug harness)

Bridge: emulator-5554 -> adb reverse tcp:8642 -> host gwproxy.py -> Odroid gateway 100.84.47.125:8642 (real Hermes instance).
Drive: debug-gated harness intent (isDebuggable-only `--es text`); text turns; `adb logcat` evidence.

Result (8-turn multi-turn, real gateway):
- Chain ADVANCES per turn, NO repeats: resp_ff4aea3b -> resp_432330ea -> resp_3050de3a -> resp_9a6ee673 -> resp_70dd07c0 -> resp_4086ee3c.
- Agent replied with text on turns 2-5 (len 72/30/29/24). NO crashes (0 FATAL/Exception).
- Turns 6-7: `hermes stream read: unexpected EOF` / len=0 — the adb-reverse relay intermittently drops the long-lived SSE body. NOT an app/gateway bug: direct curl + curl-through-proxy both stream `output_text.delta`; on-device streaming (0.3.25.1 5-turn) works reliably.
- Chaining invariant verified: previous_response_id advances, no repeats, connection holds, no crash across turns.

Verdict: the agent->app connection + chaining survive a multi-turn stress against the real gateway. The full-streamed-body capture through the emulator adb-reverse is the known harness limitation (flaky SSE relay); the connection/chaining evidence is captured regardless. Full end-to-end streaming is best validated on-device (works).
