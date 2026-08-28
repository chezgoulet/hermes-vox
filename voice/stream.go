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
//	response.completed          -> final ResponseID + usage
//
// NOTE: the json tags MUST mirror what the Android/Kotlin side reads
// (e.optString("type") / optString("item_type") / …). A struct without tags
// serializes capitalized field names and the app renders nothing.
type StreamEvent struct {
	Type       string `json:"type"`        // SSE event name, e.g. "response.output_text.delta"
	ResponseID string `json:"response_id"` // set on created/completed
	ItemType   string `json:"item_type"`   // "message" | "function_call" | "function_call_output"
	ItemID     string `json:"item_id"`
	Name       string `json:"name"`      // tool name (function_call)
	Arguments  string `json:"arguments"` // raw JSON arguments (function_call)
	Output     string `json:"output"`    // tool output text (function_call_output)
	Delta      string `json:"delta"`     // incremental text (output_text.delta)
	Text       string `json:"text"`      // accumulated assistant text so far
	Done       bool   `json:"done"`      // true once response.completed was consumed
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
	// notify is the push-side wake (#39): each buffered SSE event (and the
	// terminal done) signals it so the app wakes the moment data lands instead
	// of sleeping a fixed 240ms poll tick. Buffered(1) coalesces bursts (one
	// signal is enough — PollStreamJSON drains the whole batch). nil only for
	// the callback (non-poll) path, which never calls WaitStream.
	notify chan struct{}
}

// Stream sends a turn with stream:true and invokes h for EVERY event in
// arrival order. It blocks until the stream completes or ctx is cancelled
// (barge-in). Returns the assembled reply + response id for chaining.
func (c *HermesResponsesClient) Stream(ctx context.Context, input string, previousResponseID string, h func(StreamEvent)) (*StreamResult, error) {
	st := &streamState{}
	return c.streamInto(ctx, input, previousResponseID, st, h)
}

// streamInto is the shared SSE consumer. StartStream passes its own
// map-registered streamState so buffered events land where the app polls them.
// (Stream() creates a private one for the callback path.)
func (c *HermesResponsesClient) streamInto(ctx context.Context, input string, previousResponseID string, st *streamState, h func(StreamEvent)) (*StreamResult, error) {
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
		st.mu.Lock()
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
					st.text.WriteString(fmt.Sprintf("\n[tool:%s]", ev.Name))
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
		st.events = append(st.events, ev)
		result.Reply = plainText(st.text.String())
		result.ResponseID = st.respID
		st.mu.Unlock()
		// #39: a push signal lands with the event, so a Waiting caller wakes the
		// moment a delta is buffered (not on a poll tick). Buffer capacity is 1:
		// a burst coalesces to a single wake, which PollStreamJSON drains fully.
		if st.notify != nil {
			select {
			case st.notify <- struct{}{}:
			default:
			}
		}
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
	}
	if err := scanner.Err(); err != nil && ctx.Err() == nil {
		return &result, fmt.Errorf("hermes stream read: %w", err)
	}
	st.mu.Lock()
	completed := st.done
	reply := plainText(st.text.String())
	st.mu.Unlock()
	if result.Reply == "" && !completed {
		return &result, fmt.Errorf("hermes stream: ended without completion")
	}
	result.Reply = reply
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
// streamID. Drain with PollStreamJSON; abort with CancelStream (barge-in).
// IMPORTANT: the goroutine consumes the SSE into the SAME map-registered
// streamState that PollStreamJSON drains (via streamInto), so events the app
// renders are the events the gateway actually sent.
func (c *HermesResponsesClient) StartStream(input string, previousResponseID string) (string, error) {
	ctx, cancel := context.WithCancel(context.Background())
	st := &streamState{cancel: cancel, notify: make(chan struct{}, 1)}
	streamsMu.Lock()
	streamSeq++
	id := fmt.Sprintf("hvstream_%d_%d", time.Now().UnixNano(), streamSeq)
	streams[id] = st
	streamsMu.Unlock()
	go func() {
		_, err := c.streamInto(ctx, input, previousResponseID, st, nil)
		st.mu.Lock()
		st.done = true
		if err != nil {
			st.err = err.Error()
		}
		st.mu.Unlock()
		// #39: wake a Waiter on the terminal path too (a connection error can
		// complete the turn without a buffered response.completed to signal it).
		select {
		case st.notify <- struct{}{}:
		default:
		}
		// Entry stays in the map until the caller drains the done=true poll
		// (PollStreamJSON deletes it) — no final-poll race.
	}()
	return id, nil
}

// PollStreamJSON returns the events buffered since the last poll as one JSON
// payload: {"done":bool,"events":[...],"text":...,"transcript":...,
// "response_id":...,"error":"..."} — "" is not returned; the current snapshot
// is always returned. The FINAL poll (done=true) retires the entry.
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
	// Consume any pending push-signal so a drained batch never wakes WaitStream
	// spuriously on the next call (the signal is paired with the batch). Safe on
	// a nil/unset notify (callback path) — select's default handles it.
	select {
	case <-st.notify:
	default:
	}
	st.mu.Unlock()
	if done {
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

// WaitStream is the push-side wake for the poll-drain surface (#39). It blocks
// until the stream has NEW data buffered, the turn completes (done), or
// deadlineMs elapses. Returns true when the caller should PollStreamJSON
// (something new is ready), false on an idle timeout — NOT an error. This lets
// the app render deltas the moment they arrive instead of sleeping a fixed poll
// tick (a 240ms sleep that artificially delayed every token).
func (c *HermesResponsesClient) WaitStream(streamID string, deadlineMs int) (bool, error) {
	streamsMu.Lock()
	st := streams[streamID]
	streamsMu.Unlock()
	if st == nil {
		return false, fmt.Errorf("voice: no such stream %q (already drained or never started)", streamID)
	}
	st.mu.Lock()
	if len(st.events) > 0 || st.done {
		// Data (or the terminal done) already buffered: consume any paired signal
		// so the next call doesn't wake instantly, then report "ready now".
		select {
		case <-st.notify:
		default:
		}
		st.mu.Unlock()
		return true, nil
	}
	notify := st.notify
	st.mu.Unlock()
	timer := time.NewTimer(time.Duration(deadlineMs) * time.Millisecond)
	defer timer.Stop()
	select {
	case <-notify:
		return true, nil
	case <-timer.C:
		return false, nil
	}
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
