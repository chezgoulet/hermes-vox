package voice

import (
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
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

func TestHermesClientChatProviderIncluded(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		b, _ := io.ReadAll(r.Body)
		if !strings.Contains(string(b), `"provider":"deepseek"`) {
			t.Fatalf("missing provider in body: %s", b)
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{"choices":[{"message":{"role":"assistant","content":"ok"}}]}`)
	}))
	defer srv.Close()
	c := NewHermesClient(srv.URL, "testkey", "hermes-agent")
	c.SetProvider("deepseek")
	if _, err := c.Chat(context.Background(), []ChatMessage{{Role: "user", Content: "hi"}}); err != nil {
		t.Fatal(err)
	}
}

func TestHermesClientChatOmitsProviderWhenEmpty(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		b, _ := io.ReadAll(r.Body)
		if strings.Contains(string(b), "provider") {
			t.Fatalf("provider should be omitted when empty: %s", b)
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{"choices":[{"message":{"role":"assistant","content":"ok"}}]}`)
	}))
	defer srv.Close()
	c := NewHermesClient(srv.URL, "testkey", "hermes-agent")
	if _, err := c.Chat(context.Background(), []ChatMessage{{Role: "user", Content: "hi"}}); err != nil {
		t.Fatal(err)
	}
}
