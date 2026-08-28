package voice

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"sync"
	"time"
)

// HermesResponsesClient talks to the Hermes /v1/responses endpoint — the
// server-side-conversation path (Hermes 0.20.0 API Server). The entity keeps its
// full history (incl. tool calls) on the server via previous_response_id, so the
// app doesn't manage context. This is the RECOMMENDED connection for Hermes Vox.
// The app can additionally send a provider+model per request (the blessed
// override) so the gateway switches the entity's inference backend WITHOUT
// touching the entity (memory/skills/context preserved).
type HermesResponsesClient struct {
	baseURL  string
	apiKey   string
	mu       sync.RWMutex
	model    string
	provider string // optional per-request inference-backend override ("" = gateway default)
	http     *http.Client
}

func NewHermesResponsesClient(baseURL, apiKey, model string) *HermesResponsesClient {
	return &HermesResponsesClient{
		baseURL: baseURL,
		apiKey:  apiKey,
		model:   model,
		http:    &http.Client{Timeout: 120 * time.Second},
	}
}

// SetModel overrides the model route the client sends in /v1/responses.
func (c *HermesResponsesClient) SetModel(model string) {
	c.mu.Lock()
	c.model = model
	c.mu.Unlock()
}

// SetProvider sets the per-request provider override ("" = gateway default).
// The gateway swaps the entity's inference backend per request; the entity stays.
func (c *HermesResponsesClient) SetProvider(provider string) {
	c.mu.Lock()
	c.provider = provider
	c.mu.Unlock()
}

// buildBody assembles the request body, including the provider override when set.
func (c *HermesResponsesClient) buildBody(input string, previousResponseID string, stream bool) (map[string]any, error) {
	c.mu.RLock()
	defer c.mu.RUnlock()
	body := map[string]any{"model": c.model, "input": input, "stream": stream}
	if c.provider != "" {
		body["provider"] = c.provider
	}
	if previousResponseID != "" {
		body["previous_response_id"] = previousResponseID
	}
	return body, nil
}

// ResponseResult is the outcome of a Hermes /v1/responses call.
type ResponseResult struct {
	Reply      string // the agent's output text
	ResponseID string // the server response id -> chain via previous_response_id
}

type responsesAPI struct {
	ID     string `json:"id"`
	Output []struct {
		Type    string `json:"type"`
		Content []struct {
			Type string `json:"type"`
			Text string `json:"text"`
		} `json:"content"`
	} `json:"output"`
}

// Response sends a turn to the entity and returns the reply + the new response id.
// Pass previousResponseID (from the prior ResponseResult) to keep the full
// server-side conversation (incl. tool calls) across turns. Non-streaming for now;
// runs/cancel (the barge-in abort) is a follow-on layer.
func (c *HermesResponsesClient) Response(ctx context.Context, input string, previousResponseID string) (*ResponseResult, error) {
	body, err := c.buildBody(input, previousResponseID, false)
	if err != nil {
		return nil, err
	}
	buf, err := json.Marshal(body)
	if err != nil {
		return nil, err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.baseURL+"/v1/responses", bytes.NewReader(buf))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/json")
	if c.apiKey != "" {
		req.Header.Set("Authorization", "Bearer "+c.apiKey)
	}
	resp, err := c.http.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		b, _ := io.ReadAll(resp.Body)
		return nil, fmt.Errorf("hermes responses %s: %s", resp.Status, string(b))
	}
	var out responsesAPI
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		return nil, err
	}
	// Extract the first output_text from the message output items.
	var text string
	for _, o := range out.Output {
		if o.Type != "message" {
			continue
		}
		for _, c := range o.Content {
			if c.Type == "output_text" && c.Text != "" {
				text += c.Text
			}
		}
	}
	if text == "" {
		return nil, fmt.Errorf("hermes responses: no output_text in response")
	}
	return &ResponseResult{Reply: text, ResponseID: out.ID}, nil
}
