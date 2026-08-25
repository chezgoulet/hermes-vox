package voice

import (
	"context"
	"fmt"
)

func errNotImplemented(what string) error {
	return fmt.Errorf("voice: %s not wired on this platform yet", what)
}

// Cloud voices with Hermes-hosted/cloud STT + TTS — the default when convenience
// is the priority (Hermes's own speech stack, or a cloud provider). Hermes is
// still the mind; this is the voice localization.
type Cloud struct{ Hermes *HermesClient }

func (c *Cloud) Name() string { return "cloud" }
func (c *Cloud) Transcribe(ctx context.Context, audio []byte) (string, error) {
	return "", errNotImplemented("cloud Transcribe")
}
func (c *Cloud) Synthesize(ctx context.Context, text string) ([]byte, error) {
	return nil, errNotImplemented("cloud Synthesize")
}

// Local voices with on-device STT/TTS (Gemma-4-E2B via LiteRT-LM + Moonshine /
// piper) — offline, sovereign. Hermes is the mind (on-device small or server).
type Local struct{ Hermes *HermesClient }

func (l *Local) Name() string { return "local" }
func (l *Local) Transcribe(ctx context.Context, audio []byte) (string, error) {
	return "", errNotImplemented("local Transcribe")
}
func (l *Local) Synthesize(ctx context.Context, text string) ([]byte, error) {
	return nil, errNotImplemented("local Synthesize")
}

// SelfHosted voices via the Thelio/Odroid model server (lemonade/llama.cpp/VLLM/
// Ollama) running MiniCPM-o for full-duplex realtime — sovereign, no cloud.
type SelfHosted struct{ Hermes *HermesClient }

func (s *SelfHosted) Name() string { return "selfhosted" }
func (s *SelfHosted) Transcribe(ctx context.Context, audio []byte) (string, error) {
	return "", errNotImplemented("selfhosted Transcribe")
}
func (s *SelfHosted) Synthesize(ctx context.Context, text string) ([]byte, error) {
	return nil, errNotImplemented("selfhosted Synthesize")
}

// mockBackend is a test double: returns canned text/audio so the interface and
// the Hermes connector can be tested without real audio or a live server.
type mockBackend struct{ name string }

func (m *mockBackend) Name() string                     { return m.name }
func (m *mockBackend) Transcribe(context.Context, []byte) (string, error) {
	return "user said hi", nil
}
func (m *mockBackend) Synthesize(context.Context, string) ([]byte, error) {
	return []byte("AUDIO"), nil
}
