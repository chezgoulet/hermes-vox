package voice

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestConversationTurnText(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{"choices":[{"message":{"role":"assistant","content":"hi from hermes"}}]}`)
	}))
	defer srv.Close()

	c := NewConversation(&mockBackend{"mock"}, NewHermesClient(srv.URL, "k", "m"))
	reply, err := c.TurnText(context.Background(), "hello")
	if err != nil {
		t.Fatal(err)
	}
	if reply != "hi from hermes" {
		t.Fatalf("reply %q", reply)
	}
	if len(c.History) != 2 {
		t.Fatalf("history len = %d, want 2 (user + assistant)", len(c.History))
	}
}

func TestConversationTurnAudio(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{"choices":[{"message":{"role":"assistant","content":"reply text"}}]}`)
	}))
	defer srv.Close()

	c := NewConversation(&mockBackend{"mock"}, NewHermesClient(srv.URL, "k", "m"))
	reply, out, err := c.TurnAudio(context.Background(), []byte("AUDIO"))
	if err != nil {
		t.Fatal(err)
	}
	if reply != "reply text" {
		t.Fatalf("reply %q", reply)
	}
	if string(out) != "AUDIO" {
		t.Fatalf("synthesized audio %q", out)
	}
}

func TestConversationRequiresHermes(t *testing.T) {
	c := NewConversation(&mockBackend{"mock"}, nil)
	if _, err := c.TurnText(context.Background(), "x"); err == nil {
		t.Fatal("expected error: the entity IS Hermes (no Hermes client)")
	}
}

func TestConversationReset(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		if r.URL.Path == "/v1/responses" {
			_, _ = io.WriteString(w, `{"id":"resp_1","output":[{"type":"message","content":[{"type":"output_text","text":"the voice of Hermes"}]}]}`)
			return
		}
		_, _ = io.WriteString(w, `{"choices":[{"message":{"role":"assistant","content":"chat reply"}}]}`)
	}))
	defer srv.Close()

	c := NewConversation(&mockBackend{"mock"}, NewHermesClient(srv.URL, "k", "m"))
	c.Responses = NewHermesResponsesClient(srv.URL, "k", "m")

	// H2: TurnTextStored is stateless — the caller passes the chain head and reads the
	// new id off the result (Conversation no longer holds a lastResponseID).
	res, err := c.TurnTextStored(context.Background(), "hi", "")
	if err != nil {
		t.Fatal(err)
	}
	if res.ResponseID != "resp_1" {
		t.Fatalf("ResponseID = %q, want resp_1", res.ResponseID)
	}
	if _, err := c.TurnText(context.Background(), "hello"); err != nil {
		t.Fatal(err)
	}
	if len(c.History) != 2 {
		t.Fatalf("history len = %d, want 2 (user + assistant)", len(c.History))
	}

	c.Reset()
	if c.History != nil {
		t.Fatalf("history after reset = %#v, want nil", c.History)
	}
}

// TestTurnTextStoredChainsCallerID is the voice-side half of the H2 regression: the
// response chain head is owned by the CALLER, so TurnTextStored sends exactly the
// prevID it is given as previous_response_id and returns the new id. This is what
// lets HermesSession.TurnStored (/compress) ride the SAME server-side chain as the
// streaming voice turns instead of a separate, empty Conversation-held id.
func TestTurnTextStoredChainsCallerID(t *testing.T) {
	var gotPrev string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var body map[string]any
		_ = json.NewDecoder(r.Body).Decode(&body)
		gotPrev, _ = body["previous_response_id"].(string)
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{"id":"resp_2","output":[{"type":"message","content":[{"type":"output_text","text":"compacted"}]}]}`)
	}))
	defer srv.Close()

	c := NewConversation(&mockBackend{"mock"}, NewHermesClient(srv.URL, "k", "m"))
	c.Responses = NewHermesResponsesClient(srv.URL, "k", "m")

	res, err := c.TurnTextStored(context.Background(), "/compress", "resp_voice_1")
	if err != nil {
		t.Fatal(err)
	}
	if gotPrev != "resp_voice_1" {
		t.Fatalf("previous_response_id = %q, want resp_voice_1 (the caller's chain head)", gotPrev)
	}
	if res.ResponseID != "resp_2" {
		t.Fatalf("ResponseID = %q, want resp_2", res.ResponseID)
	}
}
