package voice

import (
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestResponsesClient(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/v1/responses" {
			t.Fatalf("path = %s", r.URL.Path)
		}
		if r.Header.Get("Authorization") != "Bearer testkey" {
			t.Fatalf("auth = %q", r.Header.Get("Authorization"))
		}
		b, _ := io.ReadAll(r.Body)
		if !strings.Contains(string(b), "previous_response_id") {
			t.Fatalf("missing previous_response_id: %s", b)
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{"id":"resp_1","output":[{"type":"message","content":[{"type":"output_text","text":"the voice of Hermes"}]}]}`)
	}))
	defer srv.Close()

	c := NewHermesResponsesClient(srv.URL, "testkey", "hermes-agent")
	res, err := c.Response(context.Background(), "hi", "resp_prev")
	if err != nil {
		t.Fatal(err)
	}
	if res.Reply != "the voice of Hermes" {
		t.Fatalf("reply = %q", res.Reply)
	}
	if res.ResponseID != "resp_1" {
		t.Fatalf("id = %q", res.ResponseID)
	}
}

func TestResponsesRejectsNon200(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Error(w, "boom", http.StatusInternalServerError)
	}))
	defer srv.Close()
	c := NewHermesResponsesClient(srv.URL, "k", "m")
	if _, err := c.Response(context.Background(), "x", ""); err == nil {
		t.Fatal("expected error on non-200")
	}
}

func TestResponsesOmitsPreviousIDWhenEmpty(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		b, _ := io.ReadAll(r.Body)
		if strings.Contains(string(b), "previous_response_id") {
			t.Fatalf("previous_response_id should be omitted: %s", b)
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{"id":"resp_1","output":[{"type":"message","content":[{"type":"output_text","text":"ok"}]}]}`)
	}))
	defer srv.Close()
	c := NewHermesResponsesClient(srv.URL, "testkey", "hermes-agent")
	res, err := c.Response(context.Background(), "hi", "")
	if err != nil {
		t.Fatal(err)
	}
	if res.Reply != "ok" {
		t.Fatalf("reply = %q", res.Reply)
	}
}

func TestResponsesNoText(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{"id":"resp_x","output":[]}`)
	}))
	defer srv.Close()
	c := NewHermesResponsesClient(srv.URL, "k", "m")
	if _, err := c.Response(context.Background(), "x", ""); err == nil {
		t.Fatal("expected error: no output_text")
	}
}

func TestResponsesProviderIncluded(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		b, _ := io.ReadAll(r.Body)
		if !strings.Contains(string(b), `"provider":"deepseek"`) {
			t.Fatalf("missing provider in body: %s", b)
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{"id":"resp_1","output":[{"type":"message","content":[{"type":"output_text","text":"ok"}]}]}`)
	}))
	defer srv.Close()
	c := NewHermesResponsesClient(srv.URL, "testkey", "hermes-agent")
	c.SetProvider("deepseek")
	if _, err := c.Response(context.Background(), "hi", ""); err != nil {
		t.Fatal(err)
	}
}

func TestResponsesOmitsProviderWhenEmpty(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		b, _ := io.ReadAll(r.Body)
		if strings.Contains(string(b), "provider") {
			t.Fatalf("provider should be omitted when empty: %s", b)
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{"id":"resp_1","output":[{"type":"message","content":[{"type":"output_text","text":"ok"}]}]}`)
	}))
	defer srv.Close()
	c := NewHermesResponsesClient(srv.URL, "testkey", "hermes-agent")
	if _, err := c.Response(context.Background(), "hi", ""); err != nil {
		t.Fatal(err)
	}
}
