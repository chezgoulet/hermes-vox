package voice

import (
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestHermesClientChat(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/v1/chat/completions" {
			t.Fatalf("path = %s", r.URL.Path)
		}
		if r.Header.Get("Authorization") != "Bearer testkey" {
			t.Fatalf("auth = %q", r.Header.Get("Authorization"))
		}
		if b, _ := io.ReadAll(r.Body); len(b) == 0 {
			t.Fatalf("empty body")
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{"choices":[{"message":{"role":"assistant","content":"the voice of Hermes"}}]}`)
	}))
	defer srv.Close()

	c := NewHermesClient(srv.URL, "testkey", "hermes-agent")
	got, err := c.Chat(context.Background(), []ChatMessage{{Role: "user", Content: "hi"}})
	if err != nil {
		t.Fatal(err)
	}
	if got != "the voice of Hermes" {
		t.Fatalf("got %q", got)
	}
}

func TestHermesClientRejectsNon200(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Error(w, "boom", http.StatusInternalServerError)
	}))
	defer srv.Close()
	c := NewHermesClient(srv.URL, "k", "m")
	if _, err := c.Chat(context.Background(), []ChatMessage{{Role: "user", Content: "x"}}); err == nil {
		t.Fatal("expected error on non-200")
	}
}

func TestBackendNames(t *testing.T) {
	hc := NewHermesClient("http://x", "k", "m")
	backends := []Backend{&Cloud{hc}, &Local{hc}, &SelfHosted{hc}}
	want := []string{"cloud", "local", "selfhosted"}
	for i, b := range backends {
		if b.Name() != want[i] {
			t.Fatalf("backend %d name = %q, want %q", i, b.Name(), want[i])
		}
	}
}

func TestBackendsNotWiredYet(t *testing.T) {
	hc := NewHermesClient("http://x", "k", "m")
	for _, b := range []Backend{&Cloud{hc}, &Local{hc}, &SelfHosted{hc}} {
		if _, err := b.Transcribe(context.Background(), nil); err == nil {
			t.Fatalf("%s Transcribe should be NotImplemented", b.Name())
		}
		if _, err := b.Synthesize(context.Background(), "x"); err == nil {
			t.Fatalf("%s Synthesize should be NotImplemented", b.Name())
		}
	}
}

func TestMockBackend(t *testing.T) {
	m := &mockBackend{name: "mock"}
	if m.Name() != "mock" {
		t.Fatalf("name %q", m.Name())
	}
	if txt, _ := m.Transcribe(context.Background(), nil); txt != "user said hi" {
		t.Fatalf("transcribe %q", txt)
	}
	if audio, _ := m.Synthesize(context.Background(), "x"); string(audio) != "AUDIO" {
		t.Fatalf("synthesize %q", audio)
	}
}
