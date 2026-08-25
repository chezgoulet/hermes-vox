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

// HermesRunClient talks to the Hermes /v1/runs API — the streaming-friendly,
// *cancellable* long-form session path (the documented `run_stop` capability).
// The app starts a run (the agent reasons + calls tools server-side), subscribes
// to / polls its progress, and on barge-in calls CancelRun to abort the generation.
type HermesRunClient struct {
	baseURL string
	apiKey  string
	model   string
	http    *http.Client
}

func NewHermesRunClient(baseURL, apiKey, model string) *HermesRunClient {
	return &HermesRunClient{
		baseURL: baseURL,
		apiKey:  apiKey,
		model:   model,
		http:    &http.Client{Timeout: 180 * time.Second},
	}
}

type runStartResp struct {
	RunID string `json:"run_id"`
}

// StartRun creates an agent run and returns its run_id. The agent reasons +
// calls tools server-side; subscribe via RunStatus/events, or poll to completion.
func (c *HermesRunClient) StartRun(ctx context.Context, input string, previousResponseID, instructions string) (string, error) {
	body := map[string]any{"model": c.model, "input": input}
	if previousResponseID != "" {
		body["previous_response_id"] = previousResponseID
	}
	if instructions != "" {
		body["instructions"] = instructions
	}
	buf, err := json.Marshal(body)
	if err != nil {
		return "", err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.baseURL+"/v1/runs", bytes.NewReader(buf))
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
		return "", fmt.Errorf("hermes run %s: %s", resp.Status, string(b))
	}
	var out runStartResp
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		return "", err
	}
	if out.RunID == "" {
		return "", fmt.Errorf("hermes run: no run_id in response")
	}
	return out.RunID, nil
}

type runStatusResp struct {
	Status string      `json:"status"`
	Output interface{} `json:"output"`
}

// RunStatus returns the current run status; when complete, returns the agent's
// reply (extracts text defensively from common output shapes).
func (c *HermesRunClient) RunStatus(ctx context.Context, runID string) (reply string, done bool, err error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, c.baseURL+"/v1/runs/"+runID, nil)
	if err != nil {
		return "", false, err
	}
	if c.apiKey != "" {
		req.Header.Set("Authorization", "Bearer "+c.apiKey)
	}
	resp, err := c.http.Do(req)
	if err != nil {
		return "", false, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		b, _ := io.ReadAll(resp.Body)
		return "", false, fmt.Errorf("hermes run status %s: %s", resp.Status, string(b))
	}
	var out runStatusResp
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		return "", false, err
	}
	done = out.Status == "completed" || out.Status == "succeeded" || out.Status == "complete"
	reply = extractRunText(out.Output)
	return reply, done, nil
}

// CancelRun aborts a running agent generation — the barge-in (run_stop). The
// server flushes/aborts the run; pending work is cancelled.
func (c *HermesRunClient) CancelRun(ctx context.Context, runID string) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.baseURL+"/v1/runs/"+runID+"/stop", nil)
	if err != nil {
		return err
	}
	if c.apiKey != "" {
		req.Header.Set("Authorization", "Bearer "+c.apiKey)
	}
	resp, err := c.http.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 && resp.StatusCode != 204 {
		b, _ := io.ReadAll(resp.Body)
		return fmt.Errorf("hermes run stop %s: %s", resp.Status, string(b))
	}
	return nil
}

// extractRunText pulls the reply text out of the run's output, which may be a
// string, a string array, or an OpenAI-style output[] list.
func extractRunText(output interface{}) string {
	switch v := output.(type) {
	case nil:
		return ""
	case string:
		return v
	case []interface{}:
		var sb []byte
		for _, item := range v {
			if m, ok := item.(map[string]interface{}); ok {
				if txt, ok := m["text"].(string); ok {
					sb = append(sb, txt...)
				}
				if content, ok := m["content"].([]interface{}); ok {
					for _, c := range content {
						if cm, ok := c.(map[string]interface{}); ok {
							if t, ok := cm["text"].(string); ok {
								sb = append(sb, t...)
							}
						}
					}
				}
			}
		}
		return string(sb)
	default:
		b, _ := json.Marshal(v)
		return string(b)
	}
}
