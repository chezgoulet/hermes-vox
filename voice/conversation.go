package voice

import (
	"context"
	"fmt"
)

// Conversation composes a voice Backend (STT/TTS localization) with the Hermes
// connector (the entity). The turn loop: audio -> Transcribe -> Hermes.Chat ->
// Synthesize. The entity stays Hermes in every mode — the Backend only localizes
// the voice, Hermes owns the reasoning/tools/memory/context.
type Conversation struct {
	Backend Backend
	Hermes  *HermesClient
	History []ChatMessage
}

func NewConversation(b Backend, h *HermesClient) *Conversation {
	return &Conversation{Backend: b, Hermes: h}
}

// TurnText sends a text turn straight to Hermes (the mind) and returns its reply.
// Used on non-audio platforms (the shell emits text; the Backend covers audio).
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
