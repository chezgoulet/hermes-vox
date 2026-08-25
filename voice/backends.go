package voice

import (
	"context"
	"fmt"
)

// The three mode backends localize ONLY the voice (audio->text, text->audio).
// They deliberately do not own the Hermes connector — the Conversation does.
// That keeps the Backend a pure, pluggable voice surface (so any STT/TTS —
// Mozilla Moonshine, piper/kokoro, ChatTTS, Kokoro-82M, Orpheus, VibeVoice,
// MiniCPM-o full-duplex, etc.) can sit behind it, matching the "agnostic
// engine" goal. Hermes (the entity) is always the Conversation's concern.

// errNotImplemented marks a platform-specific STT/TTS hook that isn't wired on
// this build yet. It is the honest default for the interface contract — the
// stub is explicit, never a silent no-op.
func errNotImplemented(what string) error {
	return fmt.Errorf("voice: %s not wired on this platform yet", what)
}

// Cloud localizes the voice with Hermes-hosted/cloud STT + TTS — the default
// when convenience is the priority.
type Cloud struct{}

func (*Cloud) Name() string { return "cloud" }
func (*Cloud) Transcribe(context.Context, []byte) (string, error) {
	return "", errNotImplemented("cloud Transcribe")
}
func (*Cloud) Synthesize(context.Context, string) ([]byte, error) {
	return nil, errNotImplemented("cloud Synthesize")
}

// Local localizes the voice with on-device STT/TTS (Gemma-4-E2B via LiteRT-LM
// + Moonshine STT + piper/kokoro TTS) — offline, sovereign.
type Local struct{}

func (*Local) Name() string { return "local" }
func (*Local) Transcribe(context.Context, []byte) (string, error) {
	return "", errNotImplemented("local Transcribe")
}
func (*Local) Synthesize(context.Context, string) ([]byte, error) {
	return nil, errNotImplemented("local Synthesize")
}

// SelfHosted localizes the voice via the Thelio/Odroid model server
// (lemonade/llama.cpp/VLLM/Ollama) running MiniCPM-o for full-duplex realtime —
// sovereign, no cloud.
type SelfHosted struct{}

func (*SelfHosted) Name() string { return "selfhosted" }
func (*SelfHosted) Transcribe(context.Context, []byte) (string, error) {
	return "", errNotImplemented("selfhosted Transcribe")
}
func (*SelfHosted) Synthesize(context.Context, string) ([]byte, error) {
	return nil, errNotImplemented("selfhosted Synthesize")
}
