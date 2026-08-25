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
