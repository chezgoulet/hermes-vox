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
