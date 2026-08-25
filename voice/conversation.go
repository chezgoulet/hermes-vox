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
	// lastResponseID is the most recent server response id, chained into the next
	// turn (previous_response_id) so the entity keeps full context incl. tool calls.
	lastResponseID string
}

func NewConversation(b Backend, h *HermesClient) *Conversation {
	return &Conversation{Backend: b, Hermes: h}
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
	return reply, nil
}

// TurnTextStored sends a text turn via the /v1/responses path (server-side
// conversation state). The response id is chained (previous_response_id) so the
// entity keeps full context — including tool calls — across turns, without the
// app managing history. This is the recommended path for Hermes Vox.
func (c *Conversation) TurnTextStored(ctx context.Context, text string) (*ResponseResult, error) {
	if c.Responses == nil {
		return nil, fmt.Errorf("voice: no Hermes responses client configured (set Responses)")
	}
	res, err := c.Responses.Response(ctx, text, c.lastResponseID)
	if err != nil {
		return nil, err
	}
	c.lastResponseID = res.ResponseID
	return res, nil
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
