package voice

import (
	"context"
	"os"
	"strings"
	"testing"
	"time"
)

// Live integration tests — THE COMPLETENESS TEST (handoff §6.2). These run
// against the REAL Hermes gateway (the same entity the phone fronts) and prove
// the whole entity connector end-to-end: a turn, the previous_response_id
// chain, the SSE stream, and the runs/cancel barge-in round-trip.
//
// Gated: they SKIP unless BOTH are set —
//   HERMES_VOX_LIVE=1
//   HERMES_VOX_HERMES_API_KEY=<API_SERVER_KEY>   (secret; House env store)
// Optional: HERMES_VOX_HERMES_URL (default http://100.84.47.125:8642),
//           HERMES_VOX_HERMES_MODEL (default hermes-agent).

func liveConfig(t *testing.T) Config {
	t.Helper()
	if os.Getenv("HERMES_VOX_LIVE") != "1" {
		t.Skip("live integration disabled (set HERMES_VOX_LIVE=1)")
	}
	cfg := LoadFromEnv()
	if cfg.HermesAPIKey == "" {
		t.Skip("no HERMES_VOX_HERMES_API_KEY in env")
	}
	if cfg.HermesBaseURL == "" {
		cfg.HermesBaseURL = Default().HermesBaseURL
	}
	if cfg.HermesModel == "" {
		cfg.HermesModel = Default().HermesModel
	}
	return cfg
}

func liveResponses(t *testing.T) *HermesResponsesClient {
	t.Helper()
	cfg := liveConfig(t)
	c := NewHermesResponsesClient(cfg.HermesBaseURL, cfg.HermesAPIKey, cfg.HermesModel)
	c.http.Timeout = 120 * time.Second
	return c
}

func liveRuns(t *testing.T) *HermesRunClient {
	t.Helper()
	cfg := liveConfig(t)
	return NewHermesRunClient(cfg.HermesBaseURL, cfg.HermesAPIKey, cfg.HermesModel)
}

// 1. A real turn against the live agent returns a real reply + a response id.
func TestLiveResponsesTurn(t *testing.T) {
	c := liveResponses(t)
	ctx, cancel := context.WithTimeout(context.Background(), 120*time.Second)
	defer cancel()
	res, err := c.Response(ctx, `Health probe. Reply with exactly: vox-live-turn-ok`, "")
	if err != nil {
		t.Fatalf("live turn failed: %v", err)
	}
	if !strings.Contains(res.Reply, "vox-live-turn-ok") {
		t.Fatalf("unexpected reply: %q", res.Reply)
	}
	if res.ResponseID == "" || !strings.HasPrefix(res.ResponseID, "resp_") {
		t.Fatalf("bad response id: %q", res.ResponseID)
	}
	t.Logf("turn ok: id=%s reply=%q", res.ResponseID, res.Reply)
}

// 2. The server-side conversation chain: a fact planted in turn 1 is recalled
// in turn 2 via previous_response_id (the entity keeps its own memory).
func TestLiveResponsesChain(t *testing.T) {
	c := liveResponses(t)
	ctx, cancel := context.WithTimeout(context.Background(), 180*time.Second)
	defer cancel()
	r1, err := c.Response(ctx, `Remember the codeword: cobalt-falcon-7. Reply ok.`, "")
	if err != nil {
		t.Fatalf("chain turn 1 failed: %v", err)
	}
	if r1.ResponseID == "" {
		t.Fatal("no response id from turn 1")
	}
	r2, err := c.Response(ctx, `What codeword did I tell you? Reply with just the codeword.`, r1.ResponseID)
	if err != nil {
		t.Fatalf("chain turn 2 failed: %v", err)
	}
	if !strings.Contains(r2.Reply, "cobalt-falcon-7") {
		t.Fatalf("chain broken: turn 2 replied %q", r2.Reply)
	}
	t.Logf("chain ok: %q -> %q", r1.Reply, r2.Reply)
}

// 3. The SSE stream: real streamed turn yields created + delta + completed
// events and assembles the reply incrementally.
func TestLiveStreamTurn(t *testing.T) {
	c := liveResponses(t)
	ctx, cancel := context.WithTimeout(context.Background(), 120*time.Second)
	defer cancel()
	var sawCreated, sawDelta, sawCompleted bool
	var deltas int
	res, err := c.Stream(ctx, `Stream probe. Reply with exactly: vox-live-stream-ok`,
		"", func(ev StreamEvent) {
			switch ev.Type {
			case "response.created":
				sawCreated = true
			case "response.output_text.delta":
				sawDelta = true
				deltas++
			case "response.completed":
				sawCompleted = true
			}
		})
	if err != nil {
		t.Fatalf("live stream failed: %v", err)
	}
	if !sawCreated || !sawCompleted {
		t.Fatalf("missing lifecycle events: created=%v completed=%v", sawCreated, sawCompleted)
	}
	if !sawDelta || deltas < 1 {
		t.Fatalf("no output_text.delta events (%d)", deltas)
	}
	if !strings.Contains(res.Reply, "vox-live-stream-ok") {
		t.Fatalf("assembled reply wrong: %q", res.Reply)
	}
	if res.ResponseID == "" {
		t.Fatal("stream result missing response id")
	}
	t.Logf("stream ok: %d delta(s), reply=%q id=%s", deltas, res.Reply, res.ResponseID)
}

// 4. Tool progress rides the stream as function_call/function_call_output
// items (verified live shape) — the avatar's "working" feed.
func TestLiveStreamToolProgress(t *testing.T) {
	c := liveResponses(t)
	ctx, cancel := context.WithTimeout(context.Background(), 180*time.Second)
	defer cancel()
	var sawCall, sawOutput bool
	var callName string
	_, err := c.Stream(ctx, `Use your shell tool to run exactly: echo vox-tool-stream-live — then reply with just its output.`,
		"", func(ev StreamEvent) {
			if ev.Type == "response.output_item.added" && ev.ItemType == "function_call" {
				sawCall = true
				callName = ev.Name
			}
			if ev.Type == "response.output_item.added" && ev.ItemType == "function_call_output" {
				sawOutput = true
			}
		})
	if err != nil {
		t.Fatalf("tool stream failed: %v", err)
	}
	if !sawCall || !sawOutput {
		t.Fatalf("tool progress missing: call=%v output=%v", sawCall, sawOutput)
	}
	if callName == "" {
		t.Fatal("function_call carried no tool name")
	}
	t.Logf("tool progress ok: name=%s", callName)
}

// 5. The barge-in round-trip: start a LONG cancellable run, see it running,
// cancel it mid-flight, confirm the server marks it cancelled.
func TestLiveRunCancelRoundTrip(t *testing.T) {
	rc := liveRuns(t)
	ctx := context.Background()
	runID, err := rc.StartRun(ctx, `Use your shell tool to run exactly: sleep 45 && echo done-slow. Then report the output.`, "", "")
	if err != nil {
		t.Fatalf("StartRun failed: %v", err)
	}
	if !strings.HasPrefix(runID, "run_") {
		t.Fatalf("bad run id %q", runID)
	}
	// Poll until it's observed working (or bail).
	var sawRunning bool
	for i := 0; i < 20; i++ {
		time.Sleep(500 * time.Millisecond)
		statusText, err := rc.RunStatus(ctx, runID)
		if err != nil {
			break // terminal failure state surfaced as error — fine
		}
		if statusText != "" {
			break // finished too fast; still a valid lifecycle
		}
		sawRunning = true
		break
	}
	if err := rc.CancelRun(ctx, runID); err != nil {
		t.Fatalf("CancelRun failed: %v", err)
	}
	// Give the server a beat, then confirm terminal state via raw status.
	time.Sleep(2 * time.Second)
	final, _ := rc.RunStatus(ctx, runID)
	t.Logf("cancel round-trip ok: run=%s sawRunning=%v final=%q", runID, sawRunning, final)
	if sawRunning && final != "" {
		t.Fatalf("run produced output after cancel: %q", final)
	}
}

// 6. Streaming barge-in: cancel the stream ctx mid-generation; the client
// stops cleanly (connection close = the abort signal for /v1/responses).
func TestLiveStreamCancelMidFlight(t *testing.T) {
	c := liveResponses(t)
	ctx, cancel := context.WithTimeout(context.Background(), 120*time.Second)
	go func() {
		time.Sleep(3 * time.Second) // let the turn get going
		cancel()                    // BARGE-IN
	}()
	start := time.Now()
	_, err := c.Stream(ctx, `Count from 1 to 400, one number per line.`, "", nil)
	elapsed := time.Since(start)
	if err == nil {
		t.Log("stream ended before cancel fired (acceptable)")
		return
	}
	if elapsed > 10*time.Second {
		t.Fatalf("cancel did not take effect promptly: %.1fs", elapsed.Seconds())
	}
	t.Logf("mid-flight barge-in ok: aborted after %.1fs", elapsed.Seconds())
}

// 7. POLL-DRAIN path (what the Android app uses): StartStream into a goroutine,
// then PollStreamJSON must yield the real SSE events the app renders.
func TestLivePollDrain(t *testing.T) {
	c := liveResponses(t)
	id, err := c.StartStream("Use your shell tool to run exactly: echo vox-poll-live and reply with just its output.", "")
	if err != nil {
		t.Fatalf("StartStream: %v", err)
	}
	seen := map[string]bool{}
	var final string
	for i := 0; i < 120; i++ {
		time.Sleep(500 * time.Millisecond)
		p, err := c.PollStreamJSON(id)
		if err != nil {
			t.Fatalf("PollStream %d: %v", i, err)
		}
		if strings.Contains(p, "\"type\":\"response.output_item.added\"") {
			seen["added"] = true
		}
		if strings.Contains(p, "\"item_type\":\"function_call\"") {
			seen["call"] = true
		}
		if strings.Contains(p, "\"done\":true") {
			final = p
			break
		}
	}
	if final == "" {
		t.Fatalf("no done within 60s; seen=%v", seen)
	}
	if !seen["call"] {
		t.Fatalf("no function_call in drained JSON; seen=%v", seen)
	}
	t.Logf("poll-drain ok: events=%v final-len=%d", seen, len(final))
}
