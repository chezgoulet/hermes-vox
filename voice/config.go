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
	// HermesSessionKey is the optional X-Hermes-Session-Key scope: the
	// per-channel identity the gateway derives its long-term-memory scope from
	// (see entity.go). Unset = the gateway's per-transcript default. It names a
	// channel rather than carrying a credential, but it is operator-declared, so
	// it is read from the environment like the base URL.
	HermesSessionKey string
}

// Env names for zero-config + secret-safe loading.
const (
	envBaseURL    = "HERMES_VOX_HERMES_URL"
	envAPIKey     = "HERMES_VOX_HERMES_API_KEY"
	envModel      = "HERMES_VOX_HERMES_MODEL"
	envSessionKey = "HERMES_VOX_HERMES_SESSION_KEY"
)

// LoadFromEnv reads the Config from the environment. Returns a Config with the
// fields populated from HERMES_VOX_*; on a miss the field is left empty.
func LoadFromEnv() Config {
	return Config{
		HermesBaseURL:    os.Getenv(envBaseURL),
		HermesAPIKey:     os.Getenv(envAPIKey),
		HermesModel:      os.Getenv(envModel),
		HermesSessionKey: os.Getenv(envSessionKey),
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

// scopeSetter is the one thing every entity connector has in common: a way to
// declare which channel is talking (see SessionKeyHeader).
type scopeSetter interface{ SetSessionKey(scope string) }

// scoped hands a freshly built connector the Config's declared scope. Every
// constructor below routes through it, so a connector cannot be handed out with
// the scope silently dropped — Config.Client() used to apply the scope while
// the /v1/responses and /v1/runs connectors, built by callers, received none.
// That is the same silent-scope-loss this header exists to fix, one layer up.
func (c Config) scoped(client scopeSetter) { client.SetSessionKey(c.HermesSessionKey) }

// Client builds the /v1/chat/completions connector from the Config. A zero
// Config yields a client that errors on Chat (the entity IS Hermes — never
// fake it). The declared scope rides every connector this Config hands out.
func (c Config) Client() *HermesClient {
	client := NewHermesClient(c.HermesBaseURL, c.HermesAPIKey, c.HermesModel)
	c.scoped(client)
	return client
}

// ResponsesClient builds the /v1/responses connector from the Config, carrying
// the declared scope exactly as Client does. Non-Android callers drive
// /v1/responses from a Config, so without this they had to remember
// SetSessionKey by hand — and a caller (or test) that forgot it silently ran a
// single-tenant path no multi-user install uses.
func (c Config) ResponsesClient() *HermesResponsesClient {
	client := NewHermesResponsesClient(c.HermesBaseURL, c.HermesAPIKey, c.HermesModel)
	c.scoped(client)
	return client
}

// RunClient builds the cancellable /v1/runs connector from the Config, carrying
// the declared scope exactly as Client does.
func (c Config) RunClient() *HermesRunClient {
	client := NewHermesRunClient(c.HermesBaseURL, c.HermesAPIKey, c.HermesModel)
	c.scoped(client)
	return client
}
