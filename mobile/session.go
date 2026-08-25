package mobile

import (
	"context"
	"fmt"
	"time"

	"github.com/chezgoulet/hermes-vox/voice"
)

// HermesSession is a Java-friendly handle to the entity connector. The native
// Android shell calls NewHermesSession + TurnText/TurnStored to talk to the SAME
// agent (the entity) — the Go source of truth (HermesClient + Conversation), not
// a Kotlin re-implementation.
type HermesSession struct {
	conv *voice.Conversation
}

// NewHermesSession connects to the Local Hermes agent (the entity). baseURL is
// the gateway API server (e.g. http://100.84.47.125:8642), apiKey is the Hermes
// API_SERVER_KEY (secret — entered by the user, never committed), model is the
// model route (usually "hermes-agent" → the real profile agent). The backend is
// the entity's own voice (Cloud = Hermes-hosted local STT/TTS).
func NewHermesSession(baseURL, apiKey, model string) *HermesSession {
	client := voice.NewHermesClient(baseURL, apiKey, model)
	conv := voice.NewConversation(&voice.Cloud{}, client)
	// The recommended server-side-context path (/v1/responses) for the entity.
	conv.Responses = voice.NewHermesResponsesClient(baseURL, apiKey, model)
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

// TurnStored sends a text turn via the /v1/responses path (server-side context,
// previous_response_id), so the entity keeps its full history incl. tool calls
// across turns. Recommended for the voice client. Returns the reply.
func (s *HermesSession) TurnStored(text string) (string, error) {
	if s == nil || s.conv == nil {
		return "", fmt.Errorf("voice: no Hermes session — call NewHermesSession first (the entity IS Hermes)")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 120*time.Second)
	defer cancel()
	res, err := s.conv.TurnTextStored(ctx, text)
	if err != nil {
		return "", err
	}
	return res.Reply, nil
}
