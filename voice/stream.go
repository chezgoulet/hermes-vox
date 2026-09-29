package voice

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/http"
	"strings"
	"sync"
	"sync/atomic"
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
// Per the API server docs, mid-turn commentary (progress preambles, text a
// model writes alongside its tool calls) arrives as its own message item with
// "phase": "commentary". It is surfaced as Commentary — never as Delta — so
// the app can show it as live progress without speaking it as the answer.
//
// NOTE: the json tags MUST mirror what the Android/Kotlin side reads
// (e.optString("type") / optString("item_type") / …). A struct without tags
// serializes capitalized field names and the app renders nothing.
type StreamEvent struct {
	Type       string `json:"type"`        // SSE event name, e.g. "response.output_text.delta"
	ResponseID string `json:"response_id"` // set on created/completed
	ItemType   string `json:"item_type"`   // "message" | "function_call" | "function_call_output"
	ItemID     string `json:"item_id"`
	Name       string `json:"name"`       // tool name (function_call)
	Arguments  string `json:"arguments"`  // raw JSON arguments (function_call)
	Output     string `json:"output"`     // tool output text (function_call_output)
	Delta      string `json:"delta"`      // incremental ANSWER text (output_text.delta)
	Commentary string `json:"commentary"` // mid-turn progress text (phase=commentary) — never spoken as the reply
	Text       string `json:"text"`       // accumulated assistant text so far
	Done       bool   `json:"done"`       // true once response.completed was consumed
}

// StreamResult is the finished outcome of a streamed turn.
type StreamResult struct {
	Reply      string
	ResponseID string
}

type sseError struct {
	Code    string `json:"code"`
	Message string `json:"message"`
}

type sseEnvelope struct {
	Type   string `json:"type"`
	ItemID string `json:"item_id"`
	Delta  string `json:"delta"`
	// Message/Code are set on a top-level "error" event.
	Message string `json:"message"`
	Code    string `json:"code"`
	Item    *struct {
		ID        string          `json:"id"`
		Type      string          `json:"type"`
		Phase     string          `json:"phase"`
		Name      string          `json:"name"`
		Arguments json.RawMessage `json:"arguments"`
		Output    json.RawMessage `json:"output"`
		Content   []struct {
			Type string `json:"type"`
			Text string `json:"text"`
		} `json:"content"`
	} `json:"item"`
	Response *struct {
		ID                string    `json:"id"`
		Status            string    `json:"status"`
		Error             *sseError `json:"error"`
		IncompleteDetails *struct {
			Reason string `json:"reason"`
		} `json:"incomplete_details"`
	} `json:"response"`
}

// streamIdleTimeout bounds how long a stream may go without receiving ANY
// bytes. Every Hermes SSE stream emits a ": keepalive" comment after 10s of
// silence, so a minute of nothing means the connection is gone (a Wi-Fi to
// cellular hand-off, a tailnet drop) rather than a slow tool call. Without the
// watchdog a dead socket was only noticed at the app's 120s wall-clock limit.
// A var so tests can shorten it.
var streamIdleTimeout = 60 * time.Second

// streamHTTP is the shared client for SSE turns. Sharing it lets consecutive
// voice turns reuse the kept-alive connection (no fresh TCP + TLS handshake on
// every utterance — first-token latency matters on a call). There is no overall
// Timeout because SSE bodies are long-lived: the request context owns
// cancellation (barge-in) and streamIdleTimeout catches dead connections. The
// dial/TLS/header timeouts make an unreachable gateway fail in seconds instead
// of hanging on the OS connect timeout.
var streamHTTP = &http.Client{
	Transport: &http.Transport{
		Proxy: http.ProxyFromEnvironment,
		DialContext: (&net.Dialer{
			Timeout:   10 * time.Second,
			KeepAlive: 30 * time.Second,
		}).DialContext,
		ForceAttemptHTTP2:     true,
		MaxIdleConns:          4,
		IdleConnTimeout:       90 * time.Second,
		TLSHandshakeTimeout:   10 * time.Second,
		ResponseHeaderTimeout: 60 * time.Second,
	},
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
	// commentary holds the ids of phase=commentary message items, so their
	// text deltas (if a gateway streams them) stay out of the spoken reply.
	commentary map[string]bool
	// failure is the gateway's own reason when the turn ends in response.failed
	// / an error event — surfaced instead of a generic "ended without completion".
	failure string
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
	body, err := c.buildBody(input, previousResponseID, true)
	if err != nil {
		return nil, err
	}
	buf, err := json.Marshal(body)
	if err != nil {
		return nil, err
	}
	// The idle watchdog cancels this derived context; the caller's ctx stays
	// the barge-in handle.
	ctx, cancelIdle := context.WithCancel(ctx)
	defer cancelIdle()
	var stalled atomic.Bool
	idle := time.AfterFunc(streamIdleTimeout, func() {
		stalled.Store(true)
		cancelIdle()
	})
	defer idle.Stop()

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, EntityURL(c.baseURL, "/v1/responses"), bytes.NewReader(buf))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "text/event-stream")
	setEntityHeaders(req, c.apiKey, c.sessionScope())
	resp, err := streamHTTP.Do(req)
	if err != nil {
		if stalled.Load() {
			return nil, errStreamStalled
		}
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return nil, fmt.Errorf("hermes stream %s: %s", resp.Status, readErrorBody(resp.Body))
	}

	var result StreamResult
	dispatch := func(evName, dataLine string) {
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
					commentary := env.Item.Phase == "commentary"
					if commentary {
						if st.commentary == nil {
							st.commentary = map[string]bool{}
						}
						st.commentary[env.Item.ID] = true
					}
					for _, ct := range env.Item.Content {
						if ct.Type == "output_text" && ct.Text != "" && name == "response.output_item.done" {
							if commentary {
								ev.Commentary += ct.Text
							} else {
								ev.Delta = ct.Text
							}
						}
					}
					ev.Text = st.text.String()
				}
			}
		case "response.output_text.delta":
			ev.ItemID = env.ItemID
			if st.commentary[env.ItemID] {
				ev.Commentary = env.Delta
			} else {
				ev.Delta = env.Delta
				st.text.WriteString(ev.Delta)
			}
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
		case "response.incomplete":
			// The turn stopped short (e.g. an output budget). Whatever was said is
			// still the entity's answer, so it completes normally when there is
			// text; with nothing said it is a failure with the gateway's reason.
			if env.Response != nil && env.Response.ID != "" {
				ev.ResponseID = env.Response.ID
				st.respID = env.Response.ID
			}
			if plainText(st.text.String()) != "" {
				ev.Done = true
				st.done = true
			} else {
				reason := "incomplete"
				if env.Response != nil && env.Response.IncompleteDetails != nil && env.Response.IncompleteDetails.Reason != "" {
					reason = "incomplete: " + env.Response.IncompleteDetails.Reason
				}
				st.failure = "hermes response " + reason
			}
			ev.Text = st.text.String()
		case "response.failed", "error":
			msg := env.Message
			if env.Response != nil && env.Response.Error != nil && env.Response.Error.Message != "" {
				msg = env.Response.Error.Message
			}
			if msg == "" {
				msg = "the gateway reported a failure"
			}
			st.failure = "hermes response failed: " + msg
			ev.Text = st.text.String()
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

	// SSE framing: an event is its "event:"/"data:" lines up to a blank line;
	// multiple data lines join with "\n"; lines starting with ":" are comments
	// (Hermes' keepalive). A data line arriving while a complete JSON payload
	// is already buffered is dispatched first, which tolerates producers that
	// omit the blank separator.
	var (
		evName string
		data   []string
	)
	flush := func() {
		if len(data) > 0 {
			payload := strings.Join(data, "\n")
			if payload != "[DONE]" && payload != "" {
				dispatch(evName, payload)
			}
		}
		evName, data = "", nil
	}
	scanner := bufio.NewScanner(resp.Body)
	scanner.Buffer(make([]byte, 0, 256*1024), 1024*1024)
	for scanner.Scan() {
		idle.Reset(streamIdleTimeout) // any bytes — keepalives included — prove the link is up
		line := scanner.Text()
		switch {
		case line == "":
			flush()
		case strings.HasPrefix(line, ":"):
			// comment / keepalive
		case strings.HasPrefix(line, "event:"):
			if len(data) > 0 {
				flush()
			}
			evName = strings.TrimSpace(strings.TrimPrefix(line, "event:"))
		case strings.HasPrefix(line, "data:"):
			d := strings.TrimPrefix(strings.TrimPrefix(line, "data:"), " ")
			if len(data) > 0 && json.Valid([]byte(strings.Join(data, "\n"))) {
				name := evName
				flush()
				evName = name
			}
			data = append(data, d)
		}
	}
	flush()
	if stalled.Load() {
		return &result, errStreamStalled
	}
	if err := scanner.Err(); err != nil && ctx.Err() == nil {
		return &result, fmt.Errorf("hermes stream read: %w", err)
	}
	st.mu.Lock()
	completed := st.done
	failure := st.failure
	reply := plainText(st.text.String())
	st.mu.Unlock()
	if !completed && failure != "" {
		return &result, errors.New(failure)
	}
	if result.Reply == "" && !completed {
		return &result, fmt.Errorf("hermes stream: ended without completion")
	}
	result.Reply = reply
	return &result, nil
}

// errStreamStalled is returned when the idle watchdog fires: no bytes (not
// even a keepalive) for streamIdleTimeout.
var errStreamStalled = errors.New("hermes stream: connection lost (no data from the gateway)")

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
// which aborts the gateway generation — AND retires the map entry.
//
// M1 (0.5.3): CancelStream owns the removal. The entry used to survive until a
// done=true poll drained it (PollStreamJSON), but on a barge-in the Kotlin worker
// breaks out of its loop (genCancelled) BEFORE that final poll, so the streamState —
// the accumulated reply text, the buffered events, the notify channel — leaked into
// the map for the life of the process. An interrupt-heavy session grew the native
// heap without bound. CancelStream is the terminal operation for the stream from the
// caller's side, so it deletes under the lock, then cancels. A later poll/wait on the
// retired id returns the clean "no such stream" error the app already swallows, and a
// delete racing PollStreamJSON's own done-removal is a harmless no-op.
func (c *HermesResponsesClient) CancelStream(streamID string) error {
	streamsMu.Lock()
	st := streams[streamID]
	delete(streams, streamID)
	streamsMu.Unlock()
	if st == nil {
		return nil // already finished/retired — nothing to cancel
	}
	st.cancel()
	return nil
}
