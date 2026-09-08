# SPRINT-PLAN DIRECTIVE — Plan the fix batch (qwen3.8-max). You plan; a CHEAPER model implements.

You are planning a fix sprint for the Hermes Vox codebase at /home/c/vox-review
(main, HEAD 9c1a95f5). Read the full findings report at
/home/c/vox-review/docs/REVIEW-0.5.2-findings.md (also at
/home/c/vox-review/REVIEW-DIRECTIVE.md for context).

Your ONLY deliverable is a SPRINT MAP (a plan), not code. You will NOT implement
anything. The implementation will be delegated to a MUCH CHEAPER model
(deepseek-v4-flash-vision-exp) — so your plan must be EXTREMELY concrete and
unambiguous, because the implementer cannot infer, improvise, or tolerate
ambiguity. Every task must be executable nearly mechanically.

The whole codebase is repo-law; each task must state its KEEP-list (what it must
NOT touch) explicitly.

## The findings to address (from the review — all of them)

HIGH:
- H1 — 600-iteration cap destroys long replies. VoiceController.kt:722 (loop),
  :785 (tries++), :788 (throw "timeout"). Fix: wall-clock deadline, drop tries.
- H2 — /compress runs on a different response chain then hijacks the voice chain,
  orphaning the conversation. mobile/session.go:128-140 + voice/conversation.go:57-67
  vs session.go:149-155. Fix: collapse to one chain (TurnStored uses session
  lastID directly), delete Conversation.lastResponseID duplicate source.
- H3 — execSubmit written but never wired; two submit sites have no guard.
  VoiceController.kt:61-64 (dead), :269/:349/:707/:890 (raw exec.execute), :608
  (shutdown). Fix: replace all four sites with execSubmit.

MEDIUM:
- M1 — barge-in leaks streamState in Go streams map forever. voice/stream.go:291,
  :307-308, :340-343, :401-409 + VoiceController.kt:727. Fix: CancelStream
  deletes the map entry.
- M2 — Settings conn-test POSTs a real generation then abandons it.
  VoiceController.kt:1386-1391 (+1337-1345). Fix: probe reachability+auth not
  generation; close/disconnect.
- M3 — ModelDownloader failure-cleanup no-op (shadowed var); unpack leaks staging.
  ModelDownloader.kt:67, :94, :96, :100. Fix: assign outer var, disconnect on
  all paths.
- M4 — RealtimeActivity expects plaintext key via intent extra, no launcher, dead.
  RealtimeActivity.kt:40-44, AndroidManifest.xml:45. Fix: delete (or resolve).
- M5 — sprite cache evicts by full clear() -> re-bake burst under pressure.
  AvatarView.kt:439-451. Fix: LRU/LinkedHashMap evict-one.

LOW:
- L1 — Conversation.History grows unbounded on a dead but exported path.
  voice/conversation.go:44-50. Fix: delete TurnText/Chat/HermesClient or cap.
- L2 — VoxLog.append reopens file per line, unsynchronized. VoxLog.kt:59-68.
  Fix: single BufferedWriter under existing lock, or single-thread executor.
- L3 — sendText reuses live turnGen instead of a new one. VoiceController.kt:625-629.
  Fix: turnGen++ in sendText (keep epoch guard).
- L4 — god-files (VoiceController.kt 1619, MainActivity.kt 1305, AvatarView.kt 1296).
  Note: logic already well-extracted into pure tested objects. Fix: lift the
  streaming-TTS queue (~200 lines) into its own class IF it pays off; LOW priority.
- L5 — journal/ contains no Go source (one md file). Cosmetic: resolve layout.

## What your SPRINT MAP must contain

Structure the plan as an ORDERED list of tasks (spikes), grouped by dependency
and risk. Recommend a delivery order. Groups:
1. **Batching** — which findings can be bundled into one commit without risk,
   which must be separate. The cheapest-model implementer is best at small,
   well-scoped diffs; propose commit granularity (e.g. one commit per finding,
   or a couple bundled where truly independent and non-overlapping).
2. **Per-task spec** — for EACH finding, give the implementer:
   - TASK_ID (e.g. H1, M2), the exact file(s):line(s) to change
   - the precise change (before → after, code-level)
   - the KEEP-list (what this task must NOT touch)
   - a verification step (the exact command/test that proves it: usually
     `cd android && JAVA_HOME=/home/c/jdk-17.0.12+7 gradle :app:testDebugUnitTest
     -q --no-daemon` exit 0, plus assembleRelease compiles; for Go: `go test
     ./voice/...` / `go build ./...` / `go vet ./*/...`)
   - the risk (what could break if done wrong)
3. **Ordering rationale** — why the sequence (H1/H3/H2 user-visible first; M1/M2
   medium; then lows; L4 optional).
4. **Tests to add** — a NEW regression test per bug fix where sensible (these are
   behavior-contract tests per the repo's AGENTS.md, not snapshot tests).
5. **KEEP-list (batch-wide)** — the invariants that must survive across all
   tasks: never break per-conversation prompt caching (no O(n²) message rebuild),
   strict role alternation, no cache-breaking mid-conversation, the 13-archetype
   swarm + VisualStyle category system + cycle-all stay intact, barge-in/
   reveal-freeze/escape-rule/reply-settle/crash-guard behavior unchanged, the
   conn-test's GatewayKey.resolve auth path unchanged.

Be concrete and complete. The implementer is cheap — it cannot fill gaps. If a
task needs the implementer to read a specific helper or follow a pattern, say so
by name. Do NOT plan anything speculative; every task must trace to a finding.
Print "SPRINT COMPLETE" at the end.
