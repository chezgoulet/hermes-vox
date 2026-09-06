package mobile

import (
	"context"
	"encoding/json"
	"fmt"
	"time"

	"github.com/chezgoulet/hermes-vox/voice"
)

// HermesSession is a Java-friendly handle to the entity connector. The native
// Android shell calls NewHermesSession + the Turn/Stream/Run family to talk
// to the SAME agent (the entity) — the Go source of truth (HermesClient +
// Conversation + HermesResponsesClient + HermesRunClient), not a Kotlin
// re-implementation.
type HermesSession struct {
	conv    *voice.Conversation
	streams *voice.HermesResponsesClient
	runs    *voice.HermesRunClient
	gw      *voice.HermesGatewayClient
	lastID  string // server-side context: the latest response id (chained automatically)
}

// NewHermesSession connects to the Local Hermes agent (the entity). baseURL is
// the gateway API server (e.g. http://100.84.47.125:8642), apiKey is the Hermes
// API_SERVER_KEY (secret — entered by the user, never committed), model is the
// model route (usually "hermes-agent" → the real profile agent).
func NewHermesSession(baseURL, apiKey, model string) *HermesSession {
	client := voice.NewHermesClient(baseURL, apiKey, model)
	conv := voice.NewConversation(&voice.Cloud{}, client)
	responses := voice.NewHermesResponsesClient(baseURL, apiKey, model)
	conv.Responses = responses
	return &HermesSession{
		conv:    conv,
		streams: responses,
		runs:    voice.NewHermesRunClient(baseURL, apiKey, model),
		gw:      voice.NewHermesGatewayClient(baseURL, apiKey),
	}
}

// SetModel overrides the model route across every connector the session uses
// (/v1/responses, /v1/chat/completions, /v1/runs). The /models chooser sets it
// so the gateway switches the entity's inference backend per request.
func (s *HermesSession) SetModel(model string) {
	if s == nil {
		return
	}
	if s.conv != nil && s.conv.Hermes != nil {
		s.conv.Hermes.SetModel(model)
	}
	if s.streams != nil {
		s.streams.SetModel(model)
	}
	if s.runs != nil {
		s.runs.SetModel(model)
	}
}

// SetProvider sets the per-request provider override ("" = gateway default) on
// EVERY connector the session uses (/v1/responses + /v1/chat/completions), so a
// cleared-provider model-only request is never silently ignored. The app writes
// this alongside model from the catalog.
func (s *HermesSession) SetProvider(provider string) {
	if s == nil {
		return
	}
	if s.conv != nil && s.conv.Hermes != nil {
		s.conv.Hermes.SetProvider(provider)
	}
	if s.streams != nil {
		s.streams.SetProvider(provider)
	}
}

// Ping verifies the entity connection (GET /v1/models, bearer auth). Non-nil
// error = wrong URL/key/host. Onboarding uses this — never fake success.
func (s *HermesSession) Ping() error {
	if s == nil || s.streams == nil {
		return fmt.Errorf("voice: no Hermes session")
	}
	return s.streams.Ping()
}

// ModelOptions returns the raw /api/model/options catalog JSON
// ({providers:[{slug,name,models[],total_models,is_current,authenticated,source,capabilities,warning}]}).
// This is the REAL gateway catalog the /models native chooser reads (the
// on-device ModelCatalog is separate — device models, not gateway models).
func (s *HermesSession) ModelOptions() (string, error) {
	if s == nil || s.gw == nil {
		return "", fmt.Errorf("voice: no Hermes session")
	}
	return s.gw.ModelOptions()
}

// GatewayHealth returns the raw /v1/health JSON (status/version) — the /health card.
func (s *HermesSession) GatewayHealth() (string, error) {
	if s == nil || s.gw == nil {
		return "", fmt.Errorf("voice: no Hermes session")
	}
	return s.gw.Health()
}

// DeleteLastResponse drops the stored server-side response id (the /new session
// reset): DELETE /v1/responses/{id} where {id} is the current chained response.
func (s *HermesSession) DeleteLastResponse() error {
	if s == nil || s.gw == nil {
		return fmt.Errorf("voice: no Hermes session")
	}
	if s.lastID == "" {
		return nil // nothing stored server-side to clear
	}
	return s.gw.DeleteResponse(s.lastID)
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

// TurnStored sends a text turn via the non-streaming /v1/responses path
// (server-side context via previous_response_id). Returns the reply.
func (s *HermesSession) TurnStored(text string) (string, error) {
	if s == nil || s.conv == nil {
		return "", fmt.Errorf("voice: no Hermes session — call NewHermesSession first")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 120*time.Second)
	defer cancel()
	res, err := s.conv.TurnTextStored(ctx, text)
	if err != nil {
		return "", err
	}
	s.lastID = res.ResponseID
	return res.Reply, nil
}

// ---- Streaming turns (REAL SSE — the entity's events as they arrive) ----

// StartStream begins a streamed entity turn (/v1/responses stream:true,
// server-side context chained automatically across calls) and returns a
// streamID. Drain with PollStreamJSON (events buffered since last poll);
// finish with CancelStream (barge-in) or let it complete (final poll carries
// done=true + the full reply + response_id).
func (s *HermesSession) StartStream(text string) (string, error) {
	if s == nil || s.streams == nil {
		return "", fmt.Errorf("voice: no Hermes session")
	}
	prev := s.lastID
	return s.streams.StartStream(text, prev)
}

// PollStreamJSON drains the events buffered since the last poll as one JSON
// payload: {"done":bool,"events":[...],"text":...,"transcript":...,
// "response_id":...,"error":...} — "" only when there is nothing new yet is NOT
// the case; it always returns the current snapshot shape. The FINAL poll
// (done=true) retires the stream. gomobile: at most (T, error).
func (s *HermesSession) PollStreamJSON(streamID string) (string, error) {
	if s == nil || s.streams == nil {
		return "", fmt.Errorf("voice: no Hermes session")
	}
	payload, err := s.streams.PollStreamJSON(streamID)
	if err == nil && payload != "" {
		var m struct {
			Done       bool   `json:"done"`
			ResponseID string `json:"response_id"`
		}
		if json.Unmarshal([]byte(payload), &m) == nil && m.ResponseID != "" {
			s.lastID = m.ResponseID
		}
	}
	return payload, err
}

// WaitStream is the push-side wake for the poll-drain surface (#39): it blocks
// until the stream has NEW data buffered, the turn completes (deadlineMs), or
// the timeout elapses. Returns true when the caller should PollStreamJSON; false
// on an idle timeout (never an error). The native shell calls this INSTEAD of
// sleeping a fixed 240ms tick so SSE deltas are rendered as they arrive.
// gomobile: at most (T, error).
func (s *HermesSession) WaitStream(streamID string, deadlineMs int) (bool, error) {
	if s == nil || s.streams == nil {
		return false, fmt.Errorf("voice: no Hermes session")
	}
	return s.streams.WaitStream(streamID, deadlineMs)
}

// CancelStream aborts an in-flight streamed turn — the barge-in for the
// streaming path (closing the HTTP connection aborts the gateway generation).
func (s *HermesSession) CancelStream(streamID string) error {
	if s == nil || s.streams == nil {
		return fmt.Errorf("voice: no Hermes session")
	}
	return s.streams.CancelStream(streamID)
}

// ResetConversation drops the server-side chain handle (a NEW conversation;
// the gateway-side history remains until its own retention clears it).
func (s *HermesSession) ResetConversation() {
	if s != nil {
		s.lastID = ""
		s.conv.Reset()
	}
}

// ---- Cancellable runs (the long-form leg; the original barge-in path) ----

// StartRun starts a cancellable agent run (POST /v1/runs — accepted as
// 200/201/202 by the client). Returns a run_id to poll/cancel.
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

// CancelRun aborts a running agent generation (POST /v1/runs/{id}/stop).
func (s *HermesSession) CancelRun(runID string) error {
	if s == nil || s.runs == nil {
		return fmt.Errorf("voice: no Hermes session")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	return s.runs.CancelRun(ctx, runID)
}
