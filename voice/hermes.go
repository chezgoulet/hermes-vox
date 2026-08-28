package voice

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"time"
)

// ChatMessage is a single message in the Hermes conversation (OpenAI-compatible).
type ChatMessage struct {
	Role    string `json:"role"`
	Content string `json:"content"`
}

type chatResponse struct {
	Choices []struct {
		Message ChatMessage `json:"message"`
	} `json:"choices"`
}

// HermesClient talks to the Hermes instance — the "entity IS Hermes" connector.
// Every turn goes through it (Hermes owns reasoning, tools, memory, context).
// It speaks the OpenAI-compatible Hermes API Server protocol (POST
// /v1/chat/completions, bearer API_SERVER_KEY), per the oxproxion pattern.
type HermesClient struct {
	baseURL string // e.g. http://<hermes-host>:8642
	apiKey  string // the Hermes API_SERVER_KEY
	model   string // Hermes virtual model ("hermes-agent")
	http    *http.Client
}

func NewHermesClient(baseURL, apiKey, model string) *HermesClient {
	return &HermesClient{
		baseURL: baseURL,
		apiKey:  apiKey,
		model:   model,
		http:    &http.Client{Timeout: 30 * time.Second},
	}
}

// SetModel overrides the model route the client sends in /v1/chat/completions.
func (c *HermesClient) SetModel(model string) { c.model = model }

// Chat sends the conversation to Hermes and returns its reply. This is how the
// entity (Hermes) actually reasons and acts — the backend is only the voice.
func (c *HermesClient) Chat(ctx context.Context, messages []ChatMessage) (string, error) {
	body := map[string]any{"model": c.model, "messages": messages, "stream": false}
	buf, err := json.Marshal(body)
	if err != nil {
		return "", err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.baseURL+"/v1/chat/completions", bytes.NewReader(buf))
	if err != nil {
		return "", err
	}
	req.Header.Set("Content-Type", "application/json")
	if c.apiKey != "" {
		req.Header.Set("Authorization", "Bearer "+c.apiKey)
	}
	resp, err := c.http.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		b, _ := io.ReadAll(resp.Body)
		return "", fmt.Errorf("hermes %s: %s", resp.Status, string(b))
	}
	var out chatResponse
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		return "", err
	}
	if len(out.Choices) == 0 {
		return "", fmt.Errorf("hermes: no choices in response")
	}
	return out.Choices[0].Message.Content, nil
}
