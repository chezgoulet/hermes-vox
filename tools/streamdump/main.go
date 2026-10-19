// Command streamdump replays SSE fixtures through the Go voice core and prints
// what the app would observe, as JSON. It is the Go half of the stream parity
// harness (#126): the Kotlin core replays the same fixtures in
// StreamParityTest and must produce the same output.
//
// Usage:
//
//	go run ./tools/streamdump testdata/stream/normal.sse     # print one dump
//	go run ./tools/streamdump -update testdata/stream/*.sse  # rewrite the goldens
//
// A fixture is an SSE body served verbatim, line by line with a flush after each
// line, preceded by optional directive lines:
//
//	#! voice                  start the first turn with VoiceTurn (the voice prefix)
//	#! hold                   after the body, hold the connection open until the client closes it
//	#! idle_timeout_ms N      shrink the stream idle watchdog to N ms for this fixture
//	#! cancel_after_events N  barge in (CancelStream) once N events have been polled
//	#! stored JSON            serve JSON for non-streaming turns and run the /compress
//	                          script: turn, TurnStored("/compress"), turn
//
// The driver is the app's own loop: WaitStream, then PollStreamJSON, until done.
// The dump records every event, the poll snapshot at each deterministic point (the
// done poll, the poll before a barge-in), the cancel semantics, and every request
// the gateway saw.
package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/chezgoulet/hermes-vox/mobile"
	"github.com/chezgoulet/hermes-vox/voice"
)

// Fixture is a parsed .sse file.
type Fixture struct {
	Name        string
	Body        string
	Voice       bool
	Hold        bool
	IdleMs      int
	CancelAfter int
	Stored      string
}

// ParseFixture reads the leading "#! " directives and keeps the rest as the body.
func ParseFixture(path string) (Fixture, error) {
	raw, err := os.ReadFile(path)
	if err != nil {
		return Fixture{}, err
	}
	f := Fixture{Name: strings.TrimSuffix(filepath.Base(path), ".sse")}
	rest := string(raw)
	for strings.HasPrefix(rest, "#! ") {
		line := rest
		if i := strings.IndexByte(rest, '\n'); i >= 0 {
			line, rest = rest[:i], rest[i+1:]
		} else {
			rest = ""
		}
		key, val, _ := strings.Cut(strings.TrimPrefix(line, "#! "), " ")
		switch key {
		case "voice":
			f.Voice = true
		case "hold":
			f.Hold = true
		case "idle_timeout_ms":
			f.IdleMs, err = strconv.Atoi(val)
		case "cancel_after_events":
			f.CancelAfter, err = strconv.Atoi(val)
		case "stored":
			f.Stored = val
		default:
			err = fmt.Errorf("unknown directive %q", key)
		}
		if err != nil {
			return Fixture{}, fmt.Errorf("%s: %w", path, err)
		}
	}
	f.Body = rest
	return f, nil
}

// Request is what the fixture gateway saw.
type Request struct {
	Method             string `json:"method"`
	Path               string `json:"path"`
	Authorization      string `json:"authorization"`
	SessionKey         string `json:"session_key"`
	Accept             string `json:"accept,omitempty"` // recorded for streamed turns only
	Model              string `json:"model"`
	Input              string `json:"input"`
	Stream             bool   `json:"stream"`
	PreviousResponseID string `json:"previous_response_id"`
}

// Turn is one streamed turn as the app observed it.
type Turn struct {
	Events    []json.RawMessage `json:"events"`
	Snapshots []map[string]any  `json:"snapshots"`
	Cancel    *CancelResult     `json:"cancel,omitempty"`
}

// CancelResult is what a barge-in left behind.
type CancelResult struct {
	PollAfterCancelFails bool `json:"poll_after_cancel_fails"`
	SecondCancelOK       bool `json:"second_cancel_ok"`
}

// Stored is the non-streaming /compress turn.
type Stored struct {
	Reply string `json:"reply"`
	Error string `json:"error"`
}

// Dump is the whole observable outcome of one fixture.
type Dump struct {
	Fixture              string    `json:"fixture"`
	Turns                []Turn    `json:"turns"`
	Stored               *Stored   `json:"stored,omitempty"`
	HeldConnectionClosed *bool     `json:"held_connection_closed,omitempty"`
	Requests             []Request `json:"requests"`
}

const (
	apiKey     = "parity-key"
	sessionKey = "agent:vox:parity"
	turnBudget = 10 * time.Second
)

// Replay runs one fixture and returns its dump.
func Replay(f Fixture) (*Dump, error) {
	if f.IdleMs > 0 {
		defer voice.SetStreamIdleTimeout(voice.SetStreamIdleTimeout(time.Duration(f.IdleMs) * time.Millisecond))
	}
	var (
		mu       sync.Mutex
		requests []Request
	)
	closed := make(chan bool, 4)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		raw, _ := io.ReadAll(r.Body)
		var body struct {
			Model              string `json:"model"`
			Input              string `json:"input"`
			Stream             bool   `json:"stream"`
			PreviousResponseID string `json:"previous_response_id"`
		}
		_ = json.Unmarshal(raw, &body)
		mu.Lock()
		requests = append(requests, Request{
			Method: r.Method, Path: r.URL.Path,
			Authorization: r.Header.Get("Authorization"), SessionKey: r.Header.Get(voice.SessionKeyHeader),
			Model: body.Model, Input: body.Input,
			Stream: body.Stream, PreviousResponseID: body.PreviousResponseID,
		})
		if body.Stream {
			requests[len(requests)-1].Accept = r.Header.Get("Accept")
		}
		mu.Unlock()
		if !body.Stream {
			w.Header().Set("Content-Type", "application/json")
			_, _ = io.WriteString(w, f.Stored)
			return
		}
		w.Header().Set("Content-Type", "text/event-stream")
		fl := w.(http.Flusher)
		for _, line := range strings.SplitAfter(f.Body, "\n") {
			if line == "" {
				continue
			}
			_, _ = io.WriteString(w, line)
			fl.Flush()
		}
		if f.Hold {
			select {
			case <-r.Context().Done():
				closed <- true
			case <-time.After(turnBudget):
				closed <- false
			}
		}
	}))
	defer srv.Close()

	s := mobile.NewHermesSession(srv.URL, apiKey, "hermes-agent")
	s.SetSessionKey(sessionKey)
	d := &Dump{Fixture: f.Name}

	first, err := turn(s, f, "hi", f.Voice)
	if err != nil {
		return nil, err
	}
	d.Turns = append(d.Turns, *first)
	if f.Stored != "" {
		reply, err := s.TurnStored("/compress")
		st := &Stored{Reply: reply}
		if err != nil {
			st.Error = err.Error()
		}
		d.Stored = st
		second, err := turn(s, f, "after", false)
		if err != nil {
			return nil, err
		}
		d.Turns = append(d.Turns, *second)
	}
	if f.Hold {
		var ok bool
		select {
		case ok = <-closed:
		case <-time.After(turnBudget):
		}
		d.HeldConnectionClosed = &ok
	}
	mu.Lock()
	d.Requests = requests
	mu.Unlock()
	return d, nil
}

type session interface {
	StartStream(string) (string, error)
	VoiceTurn(string) (string, error)
	WaitStream(string, int) (bool, error)
	PollStreamJSON(string) (string, error)
	CancelStream(string) error
}

// turn drives one streamed turn the way VoiceController does.
func turn(s session, f Fixture, text string, voiceTurn bool) (*Turn, error) {
	start := s.StartStream
	if voiceTurn {
		start = s.VoiceTurn
	}
	sid, err := start(text)
	if err != nil {
		return nil, err
	}
	t := &Turn{Events: []json.RawMessage{}, Snapshots: []map[string]any{}}
	deadline := time.Now().Add(turnBudget)
	for time.Now().Before(deadline) {
		_, _ = s.WaitStream(sid, 50)
		payload, err := s.PollStreamJSON(sid)
		if err != nil {
			return nil, fmt.Errorf("%s: poll: %w", f.Name, err)
		}
		var p map[string]json.RawMessage
		if err := json.Unmarshal([]byte(payload), &p); err != nil {
			return nil, err
		}
		var events []json.RawMessage
		_ = json.Unmarshal(p["events"], &events)
		t.Events = append(t.Events, events...)
		var done bool
		_ = json.Unmarshal(p["done"], &done)
		if f.CancelAfter > 0 && len(t.Events) >= f.CancelAfter {
			t.Snapshots = append(t.Snapshots, snapshot(p))
			_ = s.CancelStream(sid)
			_, pollErr := s.PollStreamJSON(sid)
			t.Cancel = &CancelResult{
				PollAfterCancelFails: pollErr != nil,
				SecondCancelOK:       s.CancelStream(sid) == nil,
			}
			return t, nil
		}
		if done {
			t.Snapshots = append(t.Snapshots, snapshot(p))
			return t, nil
		}
	}
	return nil, fmt.Errorf("%s: turn did not finish within %v", f.Name, turnBudget)
}

// snapshot is a poll payload without its event batch.
func snapshot(p map[string]json.RawMessage) map[string]any {
	out := map[string]any{}
	for k, v := range p {
		if k != "events" {
			out[k] = v
		}
	}
	return out
}

// Encode is the canonical golden encoding.
func Encode(d *Dump) ([]byte, error) {
	b, err := json.MarshalIndent(d, "", "  ")
	if err != nil {
		return nil, err
	}
	return append(b, '\n'), nil
}

// GoldenPath is where a fixture's expected dump lives.
func GoldenPath(fixture string) string {
	return strings.TrimSuffix(fixture, ".sse") + ".golden.json"
}

func main() {
	update := flag.Bool("update", false, "rewrite each fixture's .golden.json instead of printing")
	flag.Parse()
	if flag.NArg() == 0 {
		fmt.Fprintln(os.Stderr, "usage: streamdump [-update] fixture.sse...")
		os.Exit(2)
	}
	for _, path := range flag.Args() {
		f, err := ParseFixture(path)
		if err == nil {
			var d *Dump
			if d, err = Replay(f); err == nil {
				var out []byte
				if out, err = Encode(d); err == nil {
					if *update {
						err = os.WriteFile(GoldenPath(path), out, 0o644)
					} else {
						_, err = os.Stdout.Write(out)
					}
				}
			}
		}
		if err != nil {
			fmt.Fprintln(os.Stderr, "streamdump:", err)
			os.Exit(1)
		}
	}
}
