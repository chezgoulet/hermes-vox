package voice

import (
	"context"
	"fmt"
)

// Conversation composes a voice Backend (STT/TTS localization) with the Hermes
// connector (the entity). The turn loop: audio -> Transcribe -> Hermes -> Synthesize.
// The entity stays Hermes in every mode — the Backend only localizes the voice,
// Hermes owns the reasoning/tools/memory/context.
type Conversation struct {
	Backend Backend
	Hermes  *HermesClient
	History []ChatMessage

	// Responses is the RECOMMENDED /v1/responses connection (server-side
	// conversation state incl. tool calls). Optional; nil-safe. When set, prefer
	// TurnTextStored for a turn so the entity keeps full context across turns.
	Responses *HermesResponsesClient
	// H2 (0.5.3): Conversation holds NO response-chain id. The chain head
	// (previous_response_id) is owned by the CALLER — HermesSession.lastID — and
	// passed into TurnTextStored, so the streaming voice turns and the non-streaming
	// /compress turn ride the SAME server-side chain. A second id here was never
	// synced with the session's: /compress compacted an empty chain, then clobbered
	// the live one, orphaning the conversation it was meant to preserve.
}

func NewConversation(b Backend, h *HermesClient) *Conversation {
	return &Conversation{Backend: b, Hermes: h}
}

// Reset drops the client-side chat history, starting a fresh conversation. The
// server-side response chain head is owned by the caller (HermesSession.lastID —
// see TurnTextStored / H2), so there is no chain id here to clear; the legacy
// /v1/chat/completions History is the only client-side state.
func (c *Conversation) Reset() {
	c.History = nil
}

// TurnText sends a text turn straight to Hermes (the mind) and returns its reply.
// Used on non-audio platforms (the shell emits text; the Backend covers audio).
// History is kept client-side (the stateless chat path).
func (c *Conversation) TurnText(ctx context.Context, text string) (string, error) {
	if c.Hermes == nil {
		return "", fmt.Errorf("voice: no Hermes client configured — the entity IS Hermes")
	}
	c.History = append(c.History, ChatMessage{Role: "user", Content: text})
	reply, err := c.Hermes.Chat(ctx, c.History)
	if err != nil {
		return "", err
	}
	c.History = append(c.History, ChatMessage{Role: "assistant", Content: reply})
	c.capHistory()
	return reply, nil
}

// historyCap bounds the legacy client-side chat History (last N messages).
// TurnText re-POSTs the whole History array on every turn, so an unbounded
// append is an O(n^2) upload over a session the moment a caller wires it up.
// The committed /v1/responses path (TurnTextStored + server-side state) is the
// long-context path; this legacy /v1/chat/completions history only needs a
// short rolling window for turn-local continuity.
const historyCap = 20

// capHistory keeps only the newest historyCap messages, dropping the oldest.
// A fresh slice is allocated so the oversized backing array is released too.
func (c *Conversation) capHistory() {
	if n := len(c.History); n > historyCap {
		c.History = append([]ChatMessage(nil), c.History[n-historyCap:]...)
	}
}

// TurnTextStored sends a text turn via the /v1/responses path (server-side
// conversation state). prevID is the CALLER's current chain head, sent as
// previous_response_id so the entity keeps full context — including tool calls —
// across turns; the returned ResponseResult carries the NEW id to chain next.
// H2 (0.5.3): this is STATELESS — the caller (HermesSession) owns the one chain id
// shared with the streaming voice turns, so a non-streaming turn (e.g. /compress)
// can no longer ride a separate, empty chain. This is the recommended path for
// Hermes Vox.
func (c *Conversation) TurnTextStored(ctx context.Context, text string, prevID string) (*ResponseResult, error) {
	if c.Responses == nil {
		return nil, fmt.Errorf("voice: no Hermes responses client configured (set Responses)")
	}
	return c.Responses.Response(ctx, text, prevID)
}

// TurnAudio is the full voice turn: Transcribe -> Hermes -> Synthesize. Returns
// the Hermes reply text and the synthesized audio for playback / the avatar.
func (c *Conversation) TurnAudio(ctx context.Context, audio []byte) (string, []byte, error) {
	if c.Backend == nil {
		return "", nil, fmt.Errorf("voice: no backend configured")
	}
	text, err := c.Backend.Transcribe(ctx, audio)
	if err != nil {
		return "", nil, err
	}
	reply, err := c.TurnText(ctx, text)
	if err != nil {
		return "", nil, err
	}
	out, err := c.Backend.Synthesize(ctx, reply)
	if err != nil {
		return reply, nil, err
	}
	return reply, out, nil
}
