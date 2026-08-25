package mobile

import (
	"context"
	"fmt"
	"time"

	"github.com/chezgoulet/hermes-vox/voice"
)

// HermesSession is a Java-friendly handle to the entity connector. The native
// Android shell calls NewHermesSession + TurnText/TurnStored/StartRun to talk to
// the SAME agent (the entity) — the Go source of truth (HermesClient +
// Conversation + HermesRunClient), not a Kotlin re-implementation.
type HermesSession struct {
	conv *voice.Conversation
	runs *voice.HermesRunClient
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
	return &HermesSession{conv: conv, runs: voice.NewHermesRunClient(baseURL, apiKey, model)}
}

// TurnText sends a text turn to the entity (blocking) and returns its reply.
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

// StartRun starts a cancellable agent run (the barge-in path — the runs API).
// Returns a run_id that the shell can poll (RunStatus) and cancel (CancelRun).
func (s *HermesSession) StartRun(text string) (string, error) {
	if s == nil || s.runs == nil {
		return "", fmt.Errorf("voice: no Hermes session")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	return s.runs.StartRun(ctx, text, "", "")
}

// RunStatus polls a run; returns the agent's reply when complete, "" while the
// run is still working. gomobile bind supports at most (T, error).
func (s *HermesSession) RunStatus(runID string) (string, error) {
	if s == nil || s.runs == nil {
		return "", fmt.Errorf("voice: no Hermes session")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	return s.runs.RunStatus(ctx, runID)
}

// CancelRun aborts a running agent generation — the barge-in (run_stop).
func (s *HermesSession) CancelRun(runID string) error {
	if s == nil || s.runs == nil {
		return fmt.Errorf("voice: no Hermes session")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	return s.runs.CancelRun(ctx, runID)
}
