package voice

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// ER Phase 1 — the voice-mode signal arrives as a CLIENT-SIDE per-turn prefix
// on the user text (the same mechanism the Hermes CLI voice mode uses), so the
// per-conversation prompt cache stays intact and the prefix reaches the model
// on ANY stock gateway (BYOG universality — no gateway patch). These tests pin
// the wire contract: where the prefix rides, what it says, and that non-voice
// turns are untouched.

func TestVoiceTurnPrefixRidesUserText(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		b, _ := io.ReadAll(r.Body)
		var body map[string]any
		if err := json.Unmarshal(b, &body); err != nil {
			t.Fatalf("body: %v", err)
		}
		input, _ := body["input"].(string)
		if !strings.HasPrefix(input, UserTurnPrefix) {
			t.Fatalf("voice turn input missing prefix: %q", input)
		}
		if !strings.HasSuffix(input, "what's the wind at the grasslands?") {
			t.Fatalf("user text mangled: %q", input)
		}
		// The prefix is part of the user input — no new schema fields, no
		// instructions mutation, nothing gateway-specific on the wire.
		if _, ok := body["voice"]; ok {
			t.Fatalf("voice field must not exist (BYOG: plain user text only): %v", body)
		}
		if _, ok := body["instructions"]; ok {
			t.Fatalf("instructions must not be set by VoiceTurn (never a system mutation): %v", body)
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{"id":"resp_v1","output":[{"type":"message","content":[{"type":"output_text","text":"on it"}]}]}`)
	}))
	defer srv.Close()

	c := NewHermesResponsesClient(srv.URL, "testkey", "hermes-agent")
	res, err := c.Response(context.Background(), UserTurnPrefix+"what's the wind at the grasslands?", "")
	if err != nil {
		t.Fatal(err)
	}
	if res.Reply != "on it" {
		t.Fatalf("reply = %q", res.Reply)
	}
}

func TestPlainTurnHasNoVoicePrefix(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		b, _ := io.ReadAll(r.Body)
		var body map[string]any
		if err := json.Unmarshal(b, &body); err != nil {
			t.Fatalf("body: %v", err)
		}
		input, _ := body["input"].(string)
		if strings.Contains(input, "[Voice input") {
			t.Fatalf("plain turn must be prefix-free, got: %q", input)
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{"id":"resp_v2","output":[{"type":"message","content":[{"type":"output_text","text":"ok"}]}]}`)
	}))
	defer srv.Close()

	c := NewHermesResponsesClient(srv.URL, "testkey", "hermes-agent")
	if _, err := c.Response(context.Background(), "show me the full diff with code blocks", ""); err != nil {
		t.Fatal(err)
	}
}

func TestVoiceTurnPrefixContract(t *testing.T) {
	// The locked wording from docs/BUILD-ER-enhanced-realtime.md Phase 1:
	// call register (a) + anti-tool-spiral (b), one line, envelope-bracketed.
	if !strings.HasPrefix(UserTurnPrefix, "[Voice input") {
		t.Fatalf("prefix must open as a voice envelope: %q", UserTurnPrefix)
	}
	if !strings.HasSuffix(UserTurnPrefix, "] ") {
		t.Fatalf("prefix must close with '] ' (single trailing space before user text): %q", UserTurnPrefix)
	}
	for _, want := range []string{
		"2-3 sentences",
		"no code or markdown",
		"do NOT do exhaustive tool work",
		"unless the caller explicitly asks",
	} {
		if !strings.Contains(UserTurnPrefix, want) {
			t.Fatalf("prefix missing %q", want)
		}
	}
	if strings.ContainsAny(UserTurnPrefix, "\n\r") {
		t.Fatalf("prefix must be a single line: %q", UserTurnPrefix)
	}
}
