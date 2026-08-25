package mobile

import (
	"context"
	"fmt"
	"time"

	"github.com/chezgoulet/hermes-vox/voice"
)

// HermesSession is a Java-friendly handle to the entity connector. The native
// Android shell calls NewHermesSession + TurnText to talk to the SAME agent
// (the entity) — the Go source of truth (HermesClient + Conversation), not a
// Kotlin re-implementation.
type HermesSession struct {
	conv *voice.Conversation
}

// NewHermesSession connects to the Local Hermes agent (the entity). baseURL is
// the gateway API server (e.g. http://100.84.47.125:8642), apiKey is the Hermes
// API_SERVER_KEY (secret — provided at runtime, never committed), model is the
// model route (usually "hermes-agent" → the real profile agent). The backend is
// the entity's own voice (Cloud = Hermes-hosted local STT/TTS).
func NewHermesSession(baseURL, apiKey, model string) *HermesSession {
	client := voice.NewHermesClient(baseURL, apiKey, model)
	conv := voice.NewConversation(&voice.Cloud{}, client)
	return &HermesSession{conv: conv}
}

// TurnText sends a text turn to the entity (blocking) and returns its reply.
// The native shell calls this from a background thread so the UI isn't blocked.
func (s *HermesSession) TurnText(text string) (string, error) {
	if s == nil || s.conv == nil {
		return "", fmt.Errorf("voice: no Hermes session — call NewHermesSession first (the entity IS Hermes)")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 120*time.Second)
	defer cancel()
	return s.conv.TurnText(ctx, text)
}
