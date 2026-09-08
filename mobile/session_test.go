package mobile

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

// TestTurnStoredRidesTheStreamedChain is the H2 regression: /compress (the only
// TurnStored caller) must ride the SAME server-side response chain as the streaming
// voice turns. Before the fix, TurnStored chained off a separate
// Conversation.lastResponseID that the streaming path never touched (always ""), so
// /compress compacted an EMPTY chain and then clobbered s.lastID with the fresh id —
// silently orphaning the live conversation it was meant to preserve. Now both paths
// share s.lastID as the single source of truth.
func TestTurnStoredRidesTheStreamedChain(t *testing.T) {
	var compressPrev string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/v1/responses" {
			http.NotFound(w, r)
			return
		}
		raw, _ := io.ReadAll(r.Body)
		var body map[string]any
		_ = json.Unmarshal(raw, &body)
		if stream, _ := body["stream"].(bool); stream {
			// A streamed voice turn: response.created carries the id the session latches
			// into s.lastID, then a delta, then completed.
			w.Header().Set("Content-Type", "text/event-stream")
			fl, _ := w.(http.Flusher)
			emit := func(line string) {
				_, _ = io.WriteString(w, line+"\n")
				if fl != nil {
					fl.Flush()
				}
			}
			emit(`event: response.created`)
			emit(`data: {"type":"response.created","response":{"id":"resp_voice_1","status":"in_progress"}}`)
			emit(``)
			emit(`event: response.output_text.delta`)
			emit(`data: {"type":"response.output_text.delta","item_id":"i1","delta":"hello there"}`)
			emit(``)
			emit(`event: response.completed`)
			emit(`data: {"type":"response.completed","response":{"id":"resp_voice_1","status":"completed"}}`)
			emit(``)
			emit(`data: [DONE]`)
			emit(``)
			return
		}
		// The non-streaming /compress turn: record the chain id it rode in on.
		compressPrev, _ = body["previous_response_id"].(string)
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{"id":"resp_compress","output":[{"type":"message","content":[{"type":"output_text","text":"compacted"}]}]}`)
	}))
	defer srv.Close()

	s := NewHermesSession(srv.URL, "k", "hermes-agent")

	// 1. A voice turn streams to completion -> PollStreamJSON latches s.lastID.
	sid, err := s.StartStream("hi there")
	if err != nil {
		t.Fatal(err)
	}
	drained := false
	for i := 0; i < 300; i++ {
		payload, err := s.PollStreamJSON(sid)
		if err != nil {
			break // stream retired after the done poll
		}
		var pl struct {
			Done bool `json:"done"`
		}
		_ = json.Unmarshal([]byte(payload), &pl)
		if pl.Done {
			drained = true
			break
		}
		time.Sleep(2 * time.Millisecond)
	}
	if !drained {
		t.Fatal("the streamed turn never reported done")
	}
	if s.lastID != "resp_voice_1" {
		t.Fatalf("after the streamed turn s.lastID = %q, want resp_voice_1", s.lastID)
	}

	// 2. /compress must ride that SAME chain (previous_response_id = resp_voice_1),
	//    not a separate empty one.
	reply, err := s.TurnStored("/compress")
	if err != nil {
		t.Fatal(err)
	}
	if reply != "compacted" {
		t.Fatalf("reply = %q, want 'compacted'", reply)
	}
	if compressPrev != "resp_voice_1" {
		t.Fatalf("/compress chained off %q, want resp_voice_1 — H2 regression: the live conversation was orphaned", compressPrev)
	}

	// 3. ...and the compress reply's id becomes the new chain head, so the session
	//    keeps going on one chain (never reset to an unrelated fresh one).
	if s.lastID != "resp_compress" {
		t.Fatalf("after /compress s.lastID = %q, want resp_compress", s.lastID)
	}
}
