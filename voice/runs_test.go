package voice

import (
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestStartRun(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/v1/runs" {
			t.Fatalf("path = %s", r.URL.Path)
		}
		if r.Header.Get("Authorization") != "Bearer testkey" {
			t.Fatalf("auth = %q", r.Header.Get("Authorization"))
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{"run_id":"run_abc123"}`)
	}))
	defer srv.Close()

	c := NewHermesRunClient(srv.URL, "testkey", "hermes-agent")
	id, err := c.StartRun(context.Background(), "hi", "", "")
	if err != nil {
		t.Fatal(err)
	}
	if id != "run_abc123" {
		t.Fatalf("id = %q", id)
	}
}

func TestStartRunNoID(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{"status":"accepted"}`)
	}))
	defer srv.Close()
	c := NewHermesRunClient(srv.URL, "k", "m")
	if _, err := c.StartRun(context.Background(), "x", "", ""); err == nil {
		t.Fatal("expected error: no run_id")
	}
}

func TestCancelRun(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/v1/runs/run_1/stop" {
			t.Fatalf("path = %s", r.URL.Path)
		}
		w.WriteHeader(204)
	}))
	defer srv.Close()
	c := NewHermesRunClient(srv.URL, "k", "m")
	if err := c.CancelRun(context.Background(), "run_1"); err != nil {
		t.Fatal(err)
	}
}

func TestRunStatusComplete(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/v1/runs/run_1" {
			t.Fatalf("path = %s", r.URL.Path)
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{"status":"completed","output":"the voice of Hermes"}`)
	}))
	defer srv.Close()
	c := NewHermesRunClient(srv.URL, "k", "m")
	reply, err := c.RunStatus(context.Background(), "run_1")
	if err != nil {
		t.Fatal(err)
	}
	if reply != "the voice of Hermes" {
		t.Fatalf("reply = %q", reply)
	}
}
