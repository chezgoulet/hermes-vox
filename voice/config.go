package voice

import (
	"os"
)

// Config is the runtime configuration for the voice engine. It is loaded from
// the environment so secrets (the Hermes API key) are never committed.
type Config struct {
	// HermesBaseURL is the local Hermes agent endpoint. Verified live: the House
	// Hermes gateway API server at http://100.84.47.125:8642 (the same entity the
	// phone app fronts). Empty disables the "entity IS Hermes" connector.
	HermesBaseURL string
	// HermesAPIKey is the Hermes API_SERVER_KEY (bearer). SECRET — from the
	// environment (House env store), never committed.
	HermesAPIKey string
	// HermesModel is the model Hermes serves. The virtual "hermes-agent" routes to
	// the REAL profile agent (memory/identity/tools) — this is what makes the
	// phone experience the same entity.
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

// Default returns a default Config pointed at the verified local Hermes agent
// (base URL + model), with the API key left for the environment (secret-safe).
// The key must be provided via env; a zero key yields a client that errors on
// the entity (never a fake reply).
func Default() Config {
	return Config{
		HermesBaseURL: "http://100.84.47.125:8642",
		HermesModel:   "hermes-agent",
	}
}

// Client builds a HermesClient from the Config. A nil Config yields a client
// that errors on Chat (the entity IS Hermes — never fake it).
func (c Config) Client() *HermesClient {
	return NewHermesClient(c.HermesBaseURL, c.HermesAPIKey, c.HermesModel)
}
