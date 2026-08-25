package voice

import "context"

// Backend is the mode-selectable voice surface. Hermes is ALWAYS the mind (the
// entity); the backend only localizes STT (voice->text) and TTS (text->voice).
//
// Live implementations: Cloud (Hermes-hosted STT+TTS), Local (on-device
// Gemma-4-E2B + Moonshine/piper), SelfHosted (Thelio lemonade/llama.cpp). STT
// and TTS are platform-specific (audio) — the interface is the contract, and a
// mock is provided for testability. The conversation always goes through
// Hermes (the HermesClient), so the entity stays Hermes in every mode.
type Backend interface {
	Name() string
	Transcribe(ctx context.Context, audio []byte) (string, error)
	Synthesize(ctx context.Context, text string) ([]byte, error)
}
