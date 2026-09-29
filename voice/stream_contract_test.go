package voice

import (
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

// sseServer serves the given raw SSE lines (each written with a trailing
// newline and flushed), then optionally holds the connection open.
func sseServer(t *testing.T, lines []string, hold time.Duration) *httptest.Server {
	t.Helper()
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/event-stream")
		f := w.(http.Flusher)
		for _, l := range lines {
			_, _ = io.WriteString(w, l+"\n")
			f.Flush()
		}
		if hold > 0 {
			select {
			case <-time.After(hold):
			case <-r.Context().Done():
			}
		}
	}))
}

func collect(t *testing.T, srv *httptest.Server) ([]StreamEvent, *StreamResult, error) {
	t.Helper()
	c := NewHermesResponsesClient(srv.URL, "k", "hermes-agent")
	var evs []StreamEvent
	res, err := c.Stream(context.Background(), "hi", "", func(e StreamEvent) { evs = append(evs, e) })
	return evs, res, err
}

// Commentary (phase=commentary message items, per the API server docs) is
// progress, not the answer: it must never reach Delta (which the app speaks)
// nor the assembled reply.
func TestStreamCommentaryIsNotTheReply(t *testing.T) {
	srv := sseServer(t, []string{
		`event: response.created`,
		`data: {"type":"response.created","response":{"id":"resp_c"}}`,
		``,
		`data: {"type":"response.output_item.added","item":{"id":"c1","type":"message","phase":"commentary","content":[]}}`,
		``,
		`data: {"type":"response.output_text.delta","item_id":"c1","delta":"Checking the repo first."}`,
		``,
		`data: {"type":"response.output_item.done","item":{"id":"c1","type":"message","phase":"commentary","content":[{"type":"output_text","text":"Checking the repo first."}]}}`,
		``,
		`data: {"type":"response.output_item.added","item":{"id":"m1","type":"message","content":[]}}`,
		``,
		`data: {"type":"response.output_text.delta","item_id":"m1","delta":"All tests pass."}`,
		``,
		`data: {"type":"response.completed","response":{"id":"resp_c","status":"completed"}}`,
		``,
	}, 0)
	defer srv.Close()
	evs, res, err := collect(t, srv)
	if err != nil {
		t.Fatal(err)
	}
	if res.Reply != "All tests pass." {
		t.Fatalf("reply = %q, want only the answer", res.Reply)
	}
	var spoken, progress string
	for _, e := range evs {
		spoken += e.Delta
		progress += e.Commentary
	}
	if strings.Contains(spoken, "Checking") {
		t.Fatalf("commentary leaked into Delta: %q", spoken)
	}
	if !strings.Contains(progress, "Checking the repo first.") {
		t.Fatalf("commentary not surfaced: %q", progress)
	}
}

// response.failed carries the gateway's reason; it must reach the caller
// instead of the generic "ended without completion".
func TestStreamFailedSurfacesReason(t *testing.T) {
	srv := sseServer(t, []string{
		`data: {"type":"response.created","response":{"id":"resp_f"}}`,
		``,
		`data: {"type":"response.failed","response":{"id":"resp_f","status":"failed","error":{"code":"server_error","message":"provider quota exhausted"}}}`,
		``,
	}, 0)
	defer srv.Close()
	_, _, err := collect(t, srv)
	if err == nil || !strings.Contains(err.Error(), "provider quota exhausted") {
		t.Fatalf("err = %v, want the gateway's reason", err)
	}
}

// An incomplete response that already said something is still the answer.
func TestStreamIncompleteWithTextCompletes(t *testing.T) {
	srv := sseServer(t, []string{
		`data: {"type":"response.output_text.delta","item_id":"m1","delta":"Partial answer"}`,
		``,
		`data: {"type":"response.incomplete","response":{"id":"resp_i","status":"incomplete","incomplete_details":{"reason":"max_output_tokens"}}}`,
		``,
	}, 0)
	defer srv.Close()
	_, res, err := collect(t, srv)
	if err != nil {
		t.Fatal(err)
	}
	if res.Reply != "Partial answer" || res.ResponseID != "resp_i" {
		t.Fatalf("got %+v", res)
	}
}

// Keepalive comments are skipped, multi-line data joins with "\n", and a
// producer that omits the blank separator still dispatches every event.
func TestStreamSSEFraming(t *testing.T) {
	srv := sseServer(t, []string{
		`: keepalive`,
		`event: response.output_text.delta`,
		`data: {"type":"response.output_text.delta",`,
		`data: "item_id":"m1","delta":"one "}`,
		``,
		`: keepalive`,
		`data: {"type":"response.output_text.delta","item_id":"m1","delta":"two"}`,
		`data: {"type":"response.completed","response":{"id":"resp_x"}}`,
		``,
		`data: [DONE]`,
		``,
	}, 0)
	defer srv.Close()
	_, res, err := collect(t, srv)
	if err != nil {
		t.Fatal(err)
	}
	if res.Reply != "one two" || res.ResponseID != "resp_x" {
		t.Fatalf("got %+v", res)
	}
}

// A connection that goes silent (no events, no keepalives) fails fast with a
// clear error instead of hanging until the app's wall-clock deadline.
func TestStreamIdleWatchdog(t *testing.T) {
	old := streamIdleTimeout
	streamIdleTimeout = 150 * time.Millisecond
	defer func() { streamIdleTimeout = old }()
	srv := sseServer(t, []string{
		`data: {"type":"response.output_text.delta","item_id":"m1","delta":"hel"}`,
		``,
	}, 5*time.Second)
	defer srv.Close()
	start := time.Now()
	_, _, err := collect(t, srv)
	if err != errStreamStalled {
		t.Fatalf("err = %v, want errStreamStalled", err)
	}
	if time.Since(start) > 2*time.Second {
		t.Fatalf("watchdog took %v", time.Since(start))
	}
}

// Keepalives reset the watchdog: a long tool call is not a dead link.
func TestStreamKeepaliveResetsWatchdog(t *testing.T) {
	old := streamIdleTimeout
	streamIdleTimeout = 200 * time.Millisecond
	defer func() { streamIdleTimeout = old }()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/event-stream")
		f := w.(http.Flusher)
		for i := 0; i < 6; i++ { // 600ms of "tool work", keepalive every 100ms
			_, _ = io.WriteString(w, ": keepalive\n\n")
			f.Flush()
			time.Sleep(100 * time.Millisecond)
		}
		_, _ = io.WriteString(w, "data: {\"type\":\"response.output_text.delta\",\"item_id\":\"m\",\"delta\":\"done\"}\n\n")
		_, _ = io.WriteString(w, "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"r\"}}\n\n")
		f.Flush()
	}))
	defer srv.Close()
	_, res, err := collect(t, srv)
	if err != nil {
		t.Fatal(err)
	}
	if res.Reply != "done" {
		t.Fatalf("reply = %q", res.Reply)
	}
}

// Error bodies are capped so a misbehaving proxy page cannot flood logs/UI.
func TestStreamErrorBodyBounded(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusBadGateway)
		_, _ = io.WriteString(w, strings.Repeat("x", 100_000))
	}))
	defer srv.Close()
	_, _, err := collect(t, srv)
	if err == nil || len(err.Error()) > maxErrorBody+100 {
		t.Fatalf("error not bounded: len=%d", len(err.Error()))
	}
}

func TestRunStatusTerminalStates(t *testing.T) {
	for _, tc := range []struct {
		body, want string
	}{
		{`{"status":"interrupted","error":"Gateway shutdown interrupted the run."}`, "hermes run interrupted: Gateway shutdown interrupted the run."},
		{`{"status":"failed","error":{"message":"boom"}}`, "hermes run failed: boom"},
		{`{"status":"cancelled"}`, "hermes run cancelled"},
	} {
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			_, _ = io.WriteString(w, tc.body)
		}))
		_, err := NewHermesRunClient(srv.URL, "k", "m").RunStatus(context.Background(), "run_1")
		srv.Close()
		if err == nil || err.Error() != tc.want {
			t.Fatalf("%s: err = %v, want %q", tc.body, err, tc.want)
		}
	}
	// In-flight states are not failures.
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = io.WriteString(w, `{"status":"waiting_for_approval"}`)
	}))
	defer srv.Close()
	if out, err := NewHermesRunClient(srv.URL, "k", "m").RunStatus(context.Background(), "run_1"); err != nil || out != "" {
		t.Fatalf("waiting_for_approval: out=%q err=%v", out, err)
	}
}
