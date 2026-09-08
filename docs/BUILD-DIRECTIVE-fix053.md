# BUILD DIRECTIVE — qwen3.8-max implements the 0.5.3 sprint map. Build the BEST version.

You are the engineer building the 0.5.3 fix batch. Repo: /home/c/vox-fix053
(branch fix-batch-053, base = main 9c1a95f5/0.5.2). Read the sprint map FIRST:
docs/SPRINT-MAP.md. It is the authoritative task list — 10 commits, each with
exact file:line, the change, KEEP-list, verification, and risk. The full Opus
findings (with reasoning) are in docs/REVIEW-0.5.2-findings.md — read both.

This is NOT a mechanical transcription task. You are the engineer. As you build
each task, you have license — and are expected — to:

1. IMPROVE it. If you see a cleaner, safer, or more robust way to achieve the SAME
   outcome, do that instead. The goal is the best version of the fix, not the
   literal line-by-line change.
2. CATCH what the reviewer missed. If you find a bug, a gap, or a sibling path the
   review didn't cover while you're in that code, fix it and say so.
3. Use better ideas for implementation. If a task's suggested approach is suboptimal
   (e.g. a better cache eviction policy, a cleaner Go refactor, a more idiomatic
   Kotlin pattern), prefer the better one — as long as it's SAFE and achieves the
   intended outcome.
4. KEEP the invariants. The batch-wide KEEP-list in the sprint map is sacred:
   per-conversation prompt caching (no message rebuild / O(n²) resend), strict role
   alternation, the 13-archetype swarm + VisualStyle category system + cycle-all stay
   intact, barge-in/reveal-freeze/escape-rule/reply-settle/crash-guard behavior
   unchanged, the conn-test GatewayKey.resolve auth path unchanged (except M2's probe
   leg). If a "better idea" would violate any of these, it is NOT better — don't take it.

## Build discipline (non-negotiable)
- Work in delivery order: H1 → H3 → H2 → M1 → M2 → M3 → M4 → M5 → (L1,L2,L3) →
  (L4,L5 deferred — document, don't build).
- ONE COMMIT PER TASK. Commit message: the task id + short description,
  e.g. `fix(voice): H1 — wall-clock deadline for stream loop (no 600-event cap)`.
- After each per-file change, run its verification before moving on:
  * Kotlin: `cd android && JAVA_HOME=/home/c/jdk-17.0.12+7 gradle :app:testDebugUnitTest
    -q --no-daemon` (exit 0) and `:app:assembleRelease` compiles.
  * Go: `cd /home/c/vox-fix053 && go build ./... && go test ./voice/...` (exit 0).
  The sandbox has the AAR libs + local.properties installed so the baseline compiles.
  Do NOT leave a task half-done or a broken build between commits.
- ADD a regression test per bug where the sprint map says so (H1, H3, H2, M2, M5, M1).
  Follow the existing test conventions you'll find in voice/*_test.go and the Kotlin
  test dir. These are behavior-contract tests, not snapshot tests.
- If a task turns out to be WORSE than the sprint map's intent (i.e. you judge the
  recommended fix risky), prefer safety: do the safe version and explicitly note in
  the commit message what you changed and why.
- L4 (god-file split) and L5 (journal/ layout) are DEFERRED. Do NOT refactor them.
  You may note them in the release notes as not-done.

## Improve-as-you-go log
Maintain a short section at the end of your final output titled "IMPROVEMENTS MADE"
listing every deviation you made from the sprint map that wasn't a literal
transcription — the improvement, why, and the file:line. This is how we capture the
value of you being the engineer, not a transcriber.

## Context you need
- The Kotlin app is under android/app/src/main/java/com/hermesvox/. The Go gomobile
  module is under voice/, mobile/, cmd/, journal/ (the repo ROOT is the Go module —
  do NOT confuse it with the android/ subdir). mobile.aar is produced by gomobile.
- The conn-test key-resolution path: VoiceController reads prefs via GatewayKey.resolve
  (the live path decrypts); the probe must use the SAME resolution (this was a real
  0.5.1/0.5.2 lesson).
- The streaming turn loop, execSubmit guard, barge-in/escape rule, and reveal-freeze
  are all in VoiceController.kt — the biggest, most careful file. Read before you
  change it; the review found the trickier logic is already extracted into pure
  tested objects (BargeGate, ReplySettleRule, etc.) — preserve that factoring.

## Deliverable
All 10 commits on the branch (or fewer if you defer L4/L5 and document it), each
verified, with regression tests for the bug fixes. Print "BUILD COMPLETE" at the end,
then the IMPROVEMENTS MADE section.
