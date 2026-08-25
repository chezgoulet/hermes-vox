package voice

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"sync"
	"time"
)

// StreamEvent is one parsed Server-Sent Event from the Hermes /v1/responses
// stream (stream:true). The gateway emits the OpenAI Responses event shape,
// verified live 2026-08-25 against Hermes 0.20.0:
//
//	response.created            -> ResponseID (start of the turn)
//	response.output_item.added  -> item: message | function_call | function_call_output
//	response.output_text.delta  -> Delta (incremental assistant text)
//	response.output_text.done   -> final text of the item
//	response.completed          -> final ResponseID + usage
//
// Tool progress arrives as function_call/function_call_output ITEMS (this
// build does NOT emit a separate hermes.tool.progress event) — ToolName +
// ToolArgs + ToolOutput carry them so the UI can render the entity's work
// live and drive the avatar's "working" state from real progress.
type StreamEvent struct {
	Type       string // SSE event name, e.g. "response.output_text.delta"
	ResponseID string // set on created/completed
	ItemType   string // "message" | "function_call" | "function_call_output" (item events)
	ItemID     string
	Name       string // tool name (function_call)
	Arguments  string // raw JSON arguments (function_call)
	Output     string // tool output text (function_call_output)
	Delta      string // incremental text (output_text.delta)
	Text       string // accumulated assistant text so far (every event)
	Done       bool   // true once response.completed was consumed
}

// StreamResult is the finished outcome of a streamed turn.
type StreamResult struct {
	Reply      string
	ResponseID string
}

type sseEnvelope struct {
	Type   string `json:"type"`
	ItemID string `json:"item_id"`
	Delta  string `json:"delta"`
	Item   *struct {
		ID        string          `json:"id"`
		Type      string          `json:"type"`
		Name      string          `json:"name"`
		Arguments json.RawMessage `json:"arguments"`
		Output    json.RawMessage `json:"output"`
		Content   []struct {
			Type string `json:"type"`
			Text string `json:"text"`
		} `json:"content"`
	} `json:"item"`
	Response *struct {
		ID     string `json:"id"`
		Status string `json:"status"`
	} `json:"response"`
}

// streamState tracks one in-flight stream (the poll-drain surface used by the
// gomobile bridge: gomobile cannot marshal rich callbacks, so the mobile layer
// starts a stream, then drains buffered events as JSON).
type streamState struct {
	mu     sync.Mutex
	events []StreamEvent
	text   strings.Builder
	respID string
	done   bool
	err    string
	cancel context.CancelFunc
}

// Stream sends a turn with stream:true and consumes the SSE body, invoking h
// for every event (in arrival order). It blocks until the stream completes or
// ctx is cancelled (barge-in: cancel the ctx — closing the HTTP connection is
// the abort signal for an in-flight /v1/responses generation). Returns the
// assembled reply + response id (for previous_response_id chaining).
func (c *HermesResponsesClient) Stream(ctx context.Context, input string, previousResponseID string, h func(StreamEvent)) (*StreamResult, error) {
	body := map[string]any{"model": c.model, "input": input, "stream": true}
	if previousResponseID != "" {
		body["previous_response_id"] = previousResponseID
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
	req.Header.Set("Accept", "text/event-stream")
	if c.apiKey != "" {
		req.Header.Set("Authorization", "Bearer "+c.apiKey)
	}
	// No client Timeout: SSE bodies are long-lived; ctx owns cancellation.
	client := &http.Client{}
	resp, err := client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		b, _ := io.ReadAll(resp.Body)
		return nil, fmt.Errorf("hermes stream %s: %s", resp.Status, string(b))
	}

	st := &streamState{}
	var result StreamResult
	evName := ""
	scanner := bufio.NewScanner(resp.Body)
	scanner.Buffer(make([]byte, 0, 256*1024), 1024*1024)
	dispatch := func(dataLine string) {
		var env sseEnvelope
		if err := json.Unmarshal([]byte(dataLine), &env); err != nil {
			return
		}
		name := env.Type
		if name == "" {
			name = evName
		}
		ev := StreamEvent{Type: name}
		switch name {
		case "response.created":
			if env.Response != nil {
				ev.ResponseID = env.Response.ID
				st.respID = env.Response.ID
			}
		case "response.output_item.added", "response.output_item.done":
			if env.Item != nil {
				ev.ItemID = env.Item.ID
				ev.ItemType = env.Item.Type
				switch env.Item.Type {
				case "function_call":
					ev.Name = env.Item.Name
					ev.Arguments = strings.Trim(string(env.Item.Arguments), `"`)
					st.text.WriteString(fmt.Sprintf("\n[tool:%s]", ev.Name)) // keep Text a live transcript incl. work
					ev.Text = st.text.String()
				case "function_call_output":
					ev.Output = extractToolOutput(env.Item.Output)
					st.text.WriteString("\n[tool-done]")
					ev.Text = st.text.String()
				case "message":
					for _, ct := range env.Item.Content {
						if ct.Type == "output_text" && ct.Text != "" && name == "response.output_item.done" {
							ev.Delta = ct.Text
						}
					}
					ev.Text = st.text.String()
				}
			}
		case "response.output_text.delta":
			ev.ItemID = env.ItemID
			ev.Delta = env.Delta
			st.text.WriteString(ev.Delta)
			ev.Text = st.text.String()
		case "response.output_text.done":
			ev.Text = st.text.String()
		case "response.completed":
			if env.Response != nil {
				ev.ResponseID = env.Response.ID
				st.respID = env.Response.ID
			}
			ev.Done = true
			ev.Text = st.text.String()
			st.done = true
		default:
			ev.Text = st.text.String()
		}
		st.mu.Lock()
		st.events = append(st.events, ev)
		st.mu.Unlock()
		result.Reply = plainText(st.text.String())
		result.ResponseID = st.respID
		if h != nil {
			h(ev)
		}
	}

	for scanner.Scan() {
		line := scanner.Text()
		switch {
		case strings.HasPrefix(line, "event:"):
			evName = strings.TrimSpace(strings.TrimPrefix(line, "event:"))
		case strings.HasPrefix(line, "data:"):
			data := strings.TrimSpace(strings.TrimPrefix(line, "data:"))
			if data == "[DONE]" || data == "" {
				continue
			}
			dispatch(data)
		}
		// blank lines separate frames; dispatch happens per data line (verified shape)
	}
	if err := scanner.Err(); err != nil && ctx.Err() == nil {
		return &result, fmt.Errorf("hermes stream read: %w", err)
	}
	if result.Reply == "" && !st.done {
		return &result, fmt.Errorf("hermes stream: ended without completion")
	}
	result.Reply = plainText(st.text.String())
	return &result, nil
}

// extractToolOutput pulls human-readable text out of a function_call_output's
// output field (string, or [{type:input_text,text:...}] as shipped live).
func extractToolOutput(raw json.RawMessage) string {
	if len(raw) == 0 {
		return ""
	}
	var s string
	if err := json.Unmarshal(raw, &s); err == nil {
		return s
	}
	var arr []struct {
		Type string `json:"type"`
		Text string `json:"text"`
	}
	if err := json.Unmarshal(raw, &arr); err == nil {
		var sb strings.Builder
		for _, a := range arr {
			sb.WriteString(a.Text)
		}
		return sb.String()
	}
	return string(raw)
}

// plainText strips the [tool:*] markers from the live transcript, yielding the
// assistant's spoken reply only.
func plainText(transcript string) string {
	var sb strings.Builder
	for _, line := range strings.Split(transcript, "\n") {
		if strings.HasPrefix(line, "[tool:") || strings.HasPrefix(line, "[tool-done]") {
			continue
		}
		sb.WriteString(line)
	}
	return strings.TrimSpace(sb.String())
}

// ---- Poll-drain surface (gomobile-friendly) ----

var (
	streamsMu sync.Mutex
	streams   = map[string]*streamState{}
	streamSeq int
)

// StartStream launches a streamed turn in the background and returns a
// streamID. Drain with PollStream; abort with CancelStream (barge-in).
func (c *HermesResponsesClient) StartStream(input string, previousResponseID string) (string, error) {
	ctx, cancel := context.WithCancel(context.Background())
	st := &streamState{cancel: cancel}
	streamsMu.Lock()
	streamSeq++
	id := fmt.Sprintf("hvstream_%d_%d", time.Now().UnixNano(), streamSeq)
	streams[id] = st
	streamsMu.Unlock()
	go func() {
		_, err := c.Stream(ctx, input, previousResponseID, nil)
		st.mu.Lock()
		st.done = true
		if err != nil {
			st.err = err.Error()
		}
		st.mu.Unlock()
		// Entry stays in the map until the caller drains the done=true poll
		// (PollStreamJSON deletes it) — no final-poll race.
	}()
	return id, nil
}

// PollStreamJSON returns the events buffered since the last poll as a JSON
// array (each object mirrors StreamEvent), or "" while the stream is running
// with nothing new. The FINAL poll (after completion) returns {"done":true,
// "reply":..., "response_id":..., "error":"...", "events":[...]}. gomobile:
// max (T, error).
func (c *HermesResponsesClient) PollStreamJSON(streamID string) (string, error) {
	streamsMu.Lock()
	st := streams[streamID]
	streamsMu.Unlock()
	if st == nil {
		return "", fmt.Errorf("voice: no such stream %q (already drained or never started)", streamID)
	}
	st.mu.Lock()
	batch := st.events
	st.events = nil
	done := st.done
	errStr := st.err
	text := plainText(st.text.String())
	transcript := st.text.String()
	respID := st.respID
	st.mu.Unlock()
	if done {
		// Final payload delivered — retire the entry; further polls miss.
		streamsMu.Lock()
		delete(streams, streamID)
		streamsMu.Unlock()
	}
	payload := map[string]any{
		"done":        done,
		"events":      batch,
		"text":        text,
		"transcript":  transcript,
		"response_id": respID,
	}
	if done {
		payload["error"] = errStr
	}
	out, err := json.Marshal(payload)
	if err != nil {
		return "", err
	}
	return string(out), nil
}

// CancelStream aborts an in-flight streamed turn (the barge-in for the
// streaming path): cancels the request context — closing the HTTP connection,
// which aborts the gateway generation.
func (c *HermesResponsesClient) CancelStream(streamID string) error {
	streamsMu.Lock()
	st := streams[streamID]
	streamsMu.Unlock()
	if st == nil {
		return nil // already finished — nothing to cancel
	}
	st.cancel()
	return nil
}
