package voice

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

// TestStreamStateRacePollDrain exercises the poll-drain surface (the path the
// Android app uses) with the SSE source and the poller running concurrently. It
// exists to catch regressions of issue #16: dispatch was writing st.text,
// st.respID and st.done without st.mu while PollStreamJSON read them. Run with
// `go test -race` to actually surface the race.
func TestStreamStateRacePollDrain(t *testing.T) {
	chunks := []string{
		`event: response.created`,
		`data: {"type":"response.created","response":{"id":"resp_r","status":"completed"}}`,
		``,
		`event: response.output_text.delta`,
		`data: {"type":"response.output_text.delta","item_id":"i1","delta":"Hello "}`,
		``,
		`event: response.output_text.delta`,
		`data: {"type":"response.output_text.delta","item_id":"i1","delta":"world"}`,
		``,
		`event: response.output_text.done`,
		`data: {"type":"response.output_text.done","text":"Hello world"}`,
		``,
		`event: response.completed`,
		`data: {"type":"response.completed","response":{"id":"resp_r","status":"completed"}}`,
		``,
		`data: [DONE]`,
		``,
	}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/event-stream")
		flusher, ok := w.(http.Flusher)
		if !ok {
			t.Fatal("test server lacks http.Flusher")
		}
		for _, c := range chunks {
			_, _ = io.WriteString(w, c+"\n")
			flusher.Flush()
			time.Sleep(10 * time.Millisecond)
		}
	}))
	defer srv.Close()

	c := NewHermesResponsesClient(srv.URL, "testkey", "hermes-agent")
	id, err := c.StartStream("hi", "")
	if err != nil {
		t.Fatal(err)
	}
	finalText := ""
	for i := 0; i < 300; i++ {
		p, err := c.PollStreamJSON(id)
		if err != nil {
			break // stream retired after the done poll
		}
		var pl struct {
			Done bool   `json:"done"`
			Text string `json:"text"`
		}
		if err := json.Unmarshal([]byte(p), &pl); err != nil {
			t.Fatal(err)
		}
		finalText = pl.Text
		if pl.Done {
			break
		}
		time.Sleep(2 * time.Millisecond)
	}
	if strings.TrimSpace(finalText) != "Hello world" {
		t.Fatalf("final text = %q, want 'Hello world'", finalText)
	}
}

// TestStreamWaitPushWakesOnDelta proves the #39 push model: WaitStream (the
// wake the app uses instead of a fixed poll tick) returns the moment an SSE
// delta is buffered — well before the poll iterator's deadline — and the
// subsequent PollStreamJSON drains exactly that event.
func TestStreamWaitPushWakesOnDelta(t *testing.T) {
	burst := 60 * time.Millisecond
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/event-stream")
		flusher, ok := w.(http.Flusher)
		if !ok {
			t.Fatal("test server lacks http.Flusher")
		}
		// Hold the delta until a stable point, then push it in one flush.
		time.Sleep(burst)
		_, _ = io.WriteString(w, `event: response.output_text.delta`)
		_, _ = io.WriteString(w, "\n")
		_, _ = io.WriteString(w, `data: {"type":"response.output_text.delta","item_id":"i1","delta":"hello"}`)
		_, _ = io.WriteString(w, "\n\n")
		flusher.Flush()
		time.Sleep(burst)
		_, _ = io.WriteString(w, `event: response.completed`)
		_, _ = io.WriteString(w, "\n")
		_, _ = io.WriteString(w, `data: {"type":"response.completed","response":{"id":"resp_r"}}`)
		_, _ = io.WriteString(w, "\n\n")
		flusher.Flush()
	}))
	defer srv.Close()

	c := NewHermesResponsesClient(srv.URL, "testkey", "hermes-agent")
	id, err := c.StartStream("hi", "")
	if err != nil {
		t.Fatal(err)
	}
	// A generous deadline: we assert WaitStream wakes BEFORE it, so the wake is
	// event-driven, not a timer expiry.
	start := time.Now()
	ok, err := c.WaitStream(id, 1500)
	if err != nil {
		t.Fatal(err)
	}
	woke := time.Since(start)
	if !ok {
		t.Fatal("WaitStream did not report data within the deadline")
	}
	if woke < burst || woke >= 1500*time.Millisecond {
		t.Fatalf("WaitStream woke after %v, want an event-driven wake ~%v", woke, burst)
	}
	p, err := c.PollStreamJSON(id)
	if err != nil {
		t.Fatal(err)
	}
	var pl struct {
		Done   bool   `json:"done"`
		Events []struct {
			Type  string `json:"type"`
			Delta string `json:"delta"`
		} `json:"events"`
	}
	if err := json.Unmarshal([]byte(p), &pl); err != nil {
		t.Fatal(err)
	}
	var got []string
	for _, e := range pl.Events {
		if e.Type == "response.output_text.delta" {
			got = append(got, e.Delta)
		}
	}
	if len(got) != 1 || got[0] != "hello" {
		t.Fatalf("delta events = %v, want exactly ['hello']", got)
	}
	// Drain to completion so the entry retires cleanly.
	for i := 0; i < 100; i++ {
		if ok, _ := c.WaitStream(id, 300); !ok {
			break
		}
		p, err := c.PollStreamJSON(id)
		if err != nil {
			break
		}
		if strings.Contains(p, `"done":true`) {
			return
		}
	}
}

// TestStreamWaitTimesOutWhenIdle asserts WaitStream's timeout path: a live
// stream with no data yet returns (false, nil) — a bounded idle wait, not a
// busy-spin and not an error.
func TestStreamWaitTimesOutWhenIdle(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/event-stream")
		flusher, ok := w.(http.Flusher)
		if !ok {
			t.Fatal("test server lacks http.Flusher")
		}
		time.Sleep(500 * time.Millisecond) // no event yet
		_, _ = io.WriteString(w, `data: [DONE]`)
		_, _ = io.WriteString(w, "\n\n")
		flusher.Flush()
	}))
	defer srv.Close()

	c := NewHermesResponsesClient(srv.URL, "testkey", "hermes-agent")
	id, err := c.StartStream("hi", "")
	if err != nil {
		t.Fatal(err)
	}
	start := time.Now()
	ok, err := c.WaitStream(id, 40)
	if err != nil {
		t.Fatal(err)
	}
	if ok {
		t.Fatal("WaitStream reported data, but the stream is idle")
	}
	if time.Since(start) < 40*time.Millisecond {
		t.Fatalf("WaitStream returned before the deadline")
	}
}

// TestStreamStateRaceCallback exercises Stream() with a callback (no poll) to
// confirm the assembled reply is complete and untouched by any concurrent read.
func TestStreamStateRaceCallback(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/event-stream")
		_, _ = io.WriteString(w, `event: response.output_text.delta`)
		_, _ = io.WriteString(w, "\n")
		_, _ = io.WriteString(w, `data: {"type":"response.output_text.delta","item_id":"i1","delta":"vox"}`)
		_, _ = io.WriteString(w, "\n\n")
		_, _ = io.WriteString(w, `event: response.completed`)
		_, _ = io.WriteString(w, "\n")
		_, _ = io.WriteString(w, `data: {"type":"response.completed","response":{"id":"resp_r"}}`)
		_, _ = io.WriteString(w, "\n\n")
	}))
	defer srv.Close()

	c := NewHermesResponsesClient(srv.URL, "testkey", "hermes-agent")
	res, err := c.Stream(t.Context(), "hi", "", nil)
	if err != nil {
		t.Fatal(err)
	}
	if res.Reply != "vox" {
		t.Fatalf("reply = %q, want 'vox'", res.Reply)
	}
	if res.ResponseID != "resp_r" {
		t.Fatalf("response id = %q, want resp_r", res.ResponseID)
	}
}
