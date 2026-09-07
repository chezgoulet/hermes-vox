# SPRINT MAP — 0.5.3 fix batch (from Opus review). For delegation to a CHEAP model.

Target: chezgoulet/hermes-vox, base = main HEAD 9c1a95f5 (0.5.2). All findings from
docs/REVIEW-0.5.2-findings.md. Plan produced by Torc after a planner-model attempt
that kept defaulting to build-env verification; this map is concrete + KEEP-list'd
so the implementer (deepseek-v4-flash-vision-exp) can execute mechanically.

The implementer cannot infer. Every task states: exact file:line, the change,
KEEP-list, verification command, and risk. Read the referenced function/helper if a
task names one.

=== DELIVERY ORDER (user-visible first, then medium, then low) ===
Commit 1 (H1) — long-reply timeout bug.          [user-visible, highest impact]
Commit 2 (H3) — wire up execSubmit (crash race). [already-written fix, 4 call sites]
Commit 3 (H2) — unify the response chain (compress). [data-preservation, Go]
Commit 4 (M1) — CancelStream map leak.            [Go, 3 lines]
Commit 5 (M2) — conn-test probe: don't generate.  [Kotlin]
Commit 6 (M3) — ModelDownloader cleanup no-op.    [Kotlin]
Commit 7 (M4) — RealtimeActivity dead/plaintext.  [delete]
Commit 8 (M5) — sprite cache evict-one.           [Kotlin, perf]
Commit 9 (L1,L2,L3) — lows (bundle where safe).   [L2+L3 Kotlin, L1 Go]
Commit 10 (L4,L5) — defer/optional.               [document as deferred, not built]

Each commit is separate (independent, non-overlapping files). No bundling across
findings unless stated — the cheap implementer is best on small diffs.

---

## TASK H1 — Long replies destroyed by a poll-iteration cap
FILE: android/app/src/main/java/com/hermesvox/VoiceController.kt
LINE: the streaming loop `while (!done && tries < 600)` (~:722), `tries++` (~:785),
      `throw Exception("timeout")` on exit-without-done (~:788).
CHANGE: Replace the iteration cap with a wall-clock deadline. e.g.
  BEFORE: `while (!done && tries < 600) { ...; tries++ }`  → on fall-through `throw "timeout"`
  AFTER:
    `val deadline = android.os.SystemClock.uptimeMillis() + 120_000L`
    `while (!done && android.os.SystemClock.uptimeMillis() < deadline) { ... }`
    Drop `tries` (keep it only if used elsewhere in log lines — if so leave it but
    don't make it the exit condition).
KEEP-LIST: do NOT touch barge-in/escape-rule, reveal-freeze, silenceAll, the
  stream-poll event handling, gate-release. Only the loop condition + the throw
  path change.
VERIFY: cd android && JAVA_HOME=/home/c/jdk-17.0.12+7 gradle :app:testDebugUnitTest
  -q --no-daemon (exit 0) AND assembleRelease compiles.
RISK: a reply genuinely longer than 120s would still be cut; acceptable (that's the
  intended timeout), and far better than current ~15-20s truncation.

## TASK H3 — Wire up the execSubmit crash guard
FILE: android/app/src/main/java/com/hermesvox/VoiceController.kt
LINE: helper `execSubmit` defined but dead (:61-64); raw `exec.execute` at :269,
      :349, :707, :890; `exec.shutdown()` at :608.
CHANGE: Replace all FOUR `exec.execute { ... }` sites with the guard. The helper
  re-checks `stopped`/`isShutdown` and catches RejectedExecutionException. Pattern:
  - :707 (runStreamedTurn): `if (!execSubmit { ... }) { turnInFlight = false;
    releaseTurnGate(gen, "stopped"); return }`
  - :269 (capture loop): `if (!execSubmit { ... }) { return }`
  - :349 (partial-STT worker): `if (!execSubmit { ... }) { return }`
  - :890 (streaming-TTS worker): `if (!execSubmit { ... }) { sRunning = false; return }`
  Read the helper's exact signature/return semantics and match it. Delete the now-
  redundant manual pre-checks at :268 and :701 if the helper covers them.
KEEP-LIST: do NOT change exec.shutdown() ordering, the stop() drain, or teardown.
  Only route the submits through the guard.
VERIFY: testDebugUnitTest exit 0 + assembleRelease compiles. Ideally add a unit test
  that submits to a stopped executor via execSubmit and asserts it doesn't throw.
RISK: this is the crash class 0.5.0.1 was written for; two sites (:349, :890) have
  NO guard today, so this genuinely closes a live crash race.

## TASK H2 — Unify the response chain so /compress doesn't orphan the conversation
FILE: mobile/session.go and voice/conversation.go (Go)
LINE: session.go:128-140 (TurnStored → Conversation.TurnTextStored), conversation.go
      :57-67 (reads/writes conv.lastResponseID), session.go:149-155 (streaming reads
      s.lastID). Two independent chain ids in the SAME session.
CHANGE: Have TurnStored use the session-level id directly and drop the separate
  Conversation chain:
    func (s *HermesSession) TurnStored(text string) (string, error) {
        ... res, err := s.streams.Response(ctx, text, s.lastID)
        if err != nil { return "", err }
        s.lastID = res.ResponseID
        return res.Reply, nil
    }
  Then make Conversation not hold a second lastResponseID (either delete it or have
  TurnTextStored take the prior id as a param). Read the current TurnStored and the
  /compress caller (MainActivity.kt:1117) to preserve the return contract.
KEEP-LIST: do NOT change the streaming request shape or the SSE handling. Only the
  id source of truth. Preserve the response string Vox displays.
VERIFY: go build ./... AND go test ./voice/... (exit 0). Add a regression test that
  a turnStored immediately after a streamed turn carries the streamed turn's id.
RISK: /compress currently compacts the wrong (empty) chain and clobbers s.lastID,
  silently orphaning a long conversation — the fix is the opposite of its purpose.

## TASK M1 — CancelStream leaks the streamState map entry
FILE: voice/stream.go (Go)
LINE: map registered :291; removed only when PollStreamJSON drains done (:340-343);
  CancelStream (:401-409) cancels context but never deletes.
CHANGE: Make CancelStream own the removal:
    func (c *HermesResponsesClient) CancelStream(streamID string) error {
        streamsMu.Lock()
        st := streams[streamID]
        delete(streams, streamID)
        streamsMu.Unlock()
        if st == nil { return nil }
        st.cancel()
        return nil
    }
  PollStreamJSON/WaitStream already return a clean "no such stream" error for a
  retired id, and VoiceController.kt swallow that (:733/:735).
KEEP-LIST: do NOT change the streaming event handling or poll semantics.
VERIFY: go build ./... && go test ./voice/... (exit 0).
RISK: unbounded native-heap growth across a long interrupt-heavy session (leak).

## TASK M2 — Conn-test probe must not POST a real generation
FILE: android/app/src/main/java/com/hermesvox/VoiceController.kt
LINE: probeConnection stream leg (:1386-1391), same pattern in testConnection
      (:1337-1345). It POSTs {"model":"","input":"hello","stream":true}.
CHANGE: The probe should test reachability+auth, not generation. Options (pick the
  cleanest): (a) drop the stream leg and rely on GET /v1/models (the ping leg) for
  auth; or (b) replace the POST with stream:false + a real model id, and immediately
  close: `try { c.inputStream.close() } finally { c.disconnect() }`. Do NOT leave an
  open stream that runs a generation on the live session.
KEEP-LIST: do NOT change the ping leg or the live gateway key resolution
  (GatewayKey.resolve). Only the probe's stream leg.
VERIFY: testDebugUnitTest exit 0 + assembleRelease compiles.
RISK: an abandoned mid-flight generation pollutes the session the user is in.

## TASK M3 — ModelDownloader cleanup no-op (shadowed var)
FILE: android/app/src/main/java/com/hermesvox/ModelDownloader.kt
LINE: :67 `var tmpDir: File? = null` (never assigned), :94 shadows with a new
      `val tmpDir` inside try, :96/:100 catch/finally `tmpDir?.deleteRecursively()`
      always sees null.
CHANGE: Assign the outer var instead of shadowing: :94 `tmpDir = File(...)`, hoist
  its uses; OR compute the staging path before the try. Also wrap the connection in
  try/finally { conn.disconnect() } so non-200 / unknown-length / exceptions also
  disconnect.
KEEP-LIST: do NOT change SHA-256 pinning or the canonical-path zip-slip checks
  (those are correct). Only the cleanup + disconnect.
VERIFY: testDebugUnitTest exit 0 + assembleRelease compiles.
RISK: a failed unpack leaks a large .tmp dir on device (self-heals only on a retry
  of the same model).

## TASK M4 — RealtimeActivity is dead + trusts plaintext key
FILE: android/app/src/main/java/com/hermesvox/RealtimeActivity.kt (:40-44) and
      AndroidManifest.xml (:45).
CHANGE: Delete RealtimeActivity.kt + its manifest entry. It has no launcher
  (grep found no startActivity), and it reads a plaintext key via intent extra
  instead of GatewayKey.resolve — an exported surface accepting a bearer credential.
  MainActivity + AvatarView already cover its function.
KEEP-LIST: only touch this file + manifest. Do NOT change MainActivity's fenced
  intent path (that's debug-build-gated and correct).
VERIFY: testDebugUnitTest exit 0 + assembleRelease compiles.
RISK: LOW — it's unreachable today (dead code), but it's a trap for whoever wires
  it next. Deleting is the clean fix.

## TASK M5 — Sprite cache evicts by full clear() (re-bake burst)
FILE: android/app/src/main/java/com/hermesvox/AvatarView.kt
LINE: :439-451, `if (cache.size >= CACHE_CAP) cache.clear()` before baking.
CHANGE: Evict one entry instead of all. Use LinkedHashMap(cap, 0.75f, true) with
  removeEldestEntry, or android.util.LruCache. Preserve the cache-key design
  (spriteKey :428-436) and CACHE_CAP=48.
KEEP-LIST: do NOT change the render loop's zero-alloc path, the flow-field, or the
  13-archetype swarm. Only the cache eviction policy.
VERIFY: testDebugUnitTest exit 0 + assembleRelease compiles.
RISK: with cycle-all on, a palette rotation crossing the cap causes a visible hitch
  + GC spike on the frames the crossfade should look smoothest.

## TASK L1 (Go) — Conversation.History unbounded
FILE: voice/conversation.go:44-50.
CHANGE: The /v1/responses path is committed architecture. Delete TurnText/Chat/
  HermesClient if unreachable, or cap the history to a fixed size. Deleting is
  preferred (it's dead surface exported via gomobile).
KEEP-LIST: do NOT touch the /v1/responses path. Only the /v1/chat/completions
  legacy surface.
VERIFY: go build ./... && go test ./voice/...

## TASK L2 (Kotlin) — VoxLog per-line open is unsynchronized I/O
FILE: android/app/src/main/java/com/hermesvox/VoxLog.kt:59-68.
CHANGE: Hold a single BufferedWriter guarded by the existing rotationLock, or hand
  appends to a single-thread executor. 
KEEP-LIST: do NOT change the log format or the rotation cadence semantics.
VERIFY: testDebugUnitTest exit 0 + assembleRelease compiles.

## TASK L3 (Kotlin) — sendText reuses live turnGen
FILE: android/app/src/main/java/com/hermesvox/VoiceController.kt:625-629.
CHANGE: Consider turnGen++ in sendText, keeping the T3 epoch guard (epoch, not gen)
  as the double-count defense. Note: the :129-134 comment explains why a gen-keyed
  guard would be wrong — read it before changing. LOW priority; only do this if it
  doesn't disturb the intended invariant.
KEEP-LIST: do NOT change the epoch guard or turnInFlight logic.
VERIFY: testDebugUnitTest exit 0 + assembleRelease compiles.

## TASK L4/L5 — Deferred (document, don't build)
L4 (god-file split) and L5 (journal/ layout) are explicitly DEFERRED — they're
maintainability/comments, not bugs. NO code change. Note this in the commit
message / release notes.

=== BATCH-WIDE KEEP-LIST (must survive every task) ===
- per-conversation prompt caching: NO message rebuild / O(n²) resend.
- strict role alternation; NO synthetic user message injection.
- the 13-archetype swarm (AvatarView) + VisualStyle category system + cycle-all stay intact.
- barge-in / reveal-freeze / escape-rule / reply-settle / crash-guard behavior unchanged.
- the conn-test GatewayKey.resolve auth path unchanged except M2's probe leg.

=== TESTS TO ADD (per bug, as new test files / cases) ===
- H1: assert a reply >600 events doesn't get thrown as "timeout" (wall-clock allows it).
- H3: execSubmit to a stopped executor returns a clean false (no throw).
- H2: turnStored-after-stream uses the streamed id (Go regression).
- M2: probe with includeStream=false does not POST a generation.
- M5: cache evicts one, not clears all (assert size stays bounded, no full flush).
- M1: CancelStream removes the map entry (Go).

SPRINT MAP COMPLETE — 10 commits, delivery order H1→H3→H2→M1→M2→M3→M4→M5→(L1-L3)→(L4/L5 deferred).
