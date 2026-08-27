package voice

import (
	"context"
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

	if _, err := c.TurnTextStored(context.Background(), "hi"); err != nil {
		t.Fatal(err)
	}
	if _, err := c.TurnText(context.Background(), "hello"); err != nil {
		t.Fatal(err)
	}
	if c.lastResponseID != "resp_1" {
		t.Fatalf("lastResponseID = %q, want resp_1", c.lastResponseID)
	}
	if len(c.History) != 2 {
		t.Fatalf("history len = %d, want 2 (user + assistant)", len(c.History))
	}

	c.Reset()
	if c.lastResponseID != "" {
		t.Fatalf("lastResponseID after reset = %q, want empty", c.lastResponseID)
	}
	if c.History != nil {
		t.Fatalf("history after reset = %#v, want nil", c.History)
	}
}
