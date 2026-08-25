package voice

import (
	"context"
	"testing"
)

// mockBackend is a test double — canned text/audio so the interface and the
// Conversation are testable without real audio or a live server. It lives in a
// _test file so it never ships in production (best practice).
type mockBackend struct{ name string }

func (m *mockBackend) Name() string { return m.name }
func (m *mockBackend) Transcribe(context.Context, []byte) (string, error) {
	return "user said hi", nil
}
func (m *mockBackend) Synthesize(context.Context, string) ([]byte, error) {
	return []byte("AUDIO"), nil
}

func TestBackendNames(t *testing.T) {
	backends := []Backend{&Cloud{}, &Local{}, &SelfHosted{}}
	want := []string{"cloud", "local", "selfhosted"}
	for i, b := range backends {
		if b.Name() != want[i] {
			t.Fatalf("backend %d name = %q, want %q", i, b.Name(), want[i])
		}
	}
}

func TestBackendsNotWiredYet(t *testing.T) {
	for _, b := range []Backend{&Cloud{}, &Local{}, &SelfHosted{}} {
		if _, err := b.Transcribe(context.Background(), nil); err == nil {
			t.Fatalf("%s Transcribe should be NotImplemented", b.Name())
		}
		if _, err := b.Synthesize(context.Background(), "x"); err == nil {
			t.Fatalf("%s Synthesize should be NotImplemented", b.Name())
		}
	}
}

func TestMockBackend(t *testing.T) {
	m := &mockBackend{name: "mock"}
	if m.Name() != "mock" {
		t.Fatalf("name %q", m.Name())
	}
	if txt, _ := m.Transcribe(context.Background(), nil); txt != "user said hi" {
		t.Fatalf("transcribe %q", txt)
	}
	if audio, _ := m.Synthesize(context.Background(), "x"); string(audio) != "AUDIO" {
		t.Fatalf("synthesize %q", audio)
	}
}

func TestLoadFromEnv(t *testing.T) {
	t.Setenv(envBaseURL, "http://h:8642")
	t.Setenv(envAPIKey, "secret")
	t.Setenv(envModel, "hermes-agent")
	c := LoadFromEnv()
	if c.HermesBaseURL != "http://h:8642" || c.HermesAPIKey != "secret" || c.HermesModel != "hermes-agent" {
		t.Fatalf("config = %+v", c)
	}
}

func TestConfigClient(t *testing.T) {
	c := Config{HermesBaseURL: "http://h:8642", HermesAPIKey: "k", HermesModel: "m"}
	hc := c.Client()
	if hc == nil || hc.baseURL != "http://h:8642" || hc.model != "m" {
		t.Fatalf("client = %+v", hc)
	}
}
