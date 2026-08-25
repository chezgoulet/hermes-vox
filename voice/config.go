package voice

import (
	"os"
)

// Config is the runtime configuration for the voice engine. It is loaded from
// the environment so secrets (the Hermes API key) are never committed.
type Config struct {
	// HermesBaseURL is the Hermes instance (e.g. http://<host>:8642). Empty
	// disables the "entity IS Hermes" connector (the Conversation returns an
	// error, never a fake reply).
	HermesBaseURL string
	// HermesAPIKey is the Hermes API_SERVER_KEY. SECRET — from the environment.
	HermesAPIKey string
	// HermesModel is the model Hermes serves (the virtual "hermes-agent" or an
	// explicit model).
	HermesModel string
}

// Env names for zero-config + secret-safe loading.
const (
	envBaseURL = "HERMES_VOX_HERMES_URL"
	envAPIKey  = "HERMES_VOX_HERMES_API_KEY"
	envModel   = "HERMES_VOX_HERMES_MODEL"
)

// LoadFromEnv reads the Config from the environment. Returns a Config with the
// fields populated from HERMES_VOX_*; on a miss the field is left empty.
func LoadFromEnv() Config {
	return Config{
		HermesBaseURL: os.Getenv(envBaseURL),
		HermesAPIKey:  os.Getenv(envAPIKey),
		HermesModel:   os.Getenv(envModel),
	}
}

// Default returns a sane default Config (empty Hermes — the caller must wire an
// entity for the Conversation to be functional). Kept explicit so a zero-value
// Config is never silently "connected."
func Default() Config {
	return Config{HermesModel: "hermes-agent"}
}

// Client builds a HermesClient from the Config. A nil Config yields a client
// that errors on Chat (the entity IS Hermes — never fake it).
func (c Config) Client() *HermesClient {
	return NewHermesClient(c.HermesBaseURL, c.HermesAPIKey, c.HermesModel)
}
