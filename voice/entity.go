package voice

import (
	"net/http"
	"strings"
)

// EntityURL joins the configured entity base URL with a gateway path.
//
// The base URL is USER-TYPED (onboarding / Settings -> Entity), and a pasted
// URL with a trailing slash is the normal case ("http://host:8642/"). Naive
// concatenation then asks for "http://host:8642//v1/models" — and the Hermes
// API server 404s on BOTH the doubled slash and a trailing-slash path
// (verified live: "//v1/models" and "/v1/models/" each return 404 Not Found).
// The failure surfaces as a misleading "Could not reach the entity — check URL
// + key" in onboarding, so the trim lives here, once, for every connector.
//
// The path is expected to carry its own leading slash ("/v1/responses").
func EntityURL(baseURL, path string) string {
	return strings.TrimRight(strings.TrimSpace(baseURL), "/") + path
}

// SessionKeyHeader is the API server's per-channel identity header. The gateway
// advertises it in GET /v1/capabilities as features.session_key_header, accepts
// it on /v1/chat/completions, /v1/responses and /v1/runs, and echoes it back on
// the response. It derives a STABLE long-term-memory scope per channel —
// independent of the transcript-scoped X-Hermes-Session-Id, which rotates on
// /new.
//
// Why this client needs it: several people (or several devices) can point at
// ONE gateway, and API_SERVER_KEY is a single shared bearer credential — it
// names the deployment, not the caller. With no session key, every Vox install
// on that gateway writes into the same long-term-memory scope and the entity
// cannot tell who is talking. Declaring a scope per device/owner (the gateway's
// own documented example shape is "agent:main:webui:dm:user-42") keeps
// per-person memory apart while remaining the SAME entity (KEEP-list #1: no
// second brain, no persona layer — this only names the channel).
//
// Empty = the pre-existing behavior (the gateway scopes memory per transcript),
// so single-user installs are byte-for-byte unchanged.
const SessionKeyHeader = "X-Hermes-Session-Key"

// MaxSessionKeyLen mirrors the API server's header cap. The gateway rejects
// values over 256 chars (and any containing CR, LF or NUL), so the app
// validates before sending rather than eating a 400 mid-turn.
const MaxSessionKeyLen = 256

// setEntityHeaders stamps the shared entity credentials on a request: the
// bearer API key, and the session scope when the caller declared one. Both are
// omitted when empty, so the no-key and no-scope paths behave exactly as they
// did before this header existed.
func setEntityHeaders(req *http.Request, apiKey, sessionKey string) {
	if apiKey != "" {
		req.Header.Set("Authorization", "Bearer "+apiKey)
	}
	if sessionKey != "" {
		req.Header.Set(SessionKeyHeader, sessionKey)
	}
}

// ---- Declaring the scope (one setter per connector) ----
//
// The setters live here, beside the header they feed, the same way ping.go
// carries HermesResponsesClient.Ping: every connector that talks to the entity
// gets the same one-line way to declare who it is.

// SetSessionKey declares this client's long-term-memory scope (see
// SessionKeyHeader). "" clears it, returning the client to the gateway's
// per-transcript default. Locked like every sibling setter: Chat reads the
// scope on the request path, and the app may re-declare it mid-session.
func (c *HermesClient) SetSessionKey(scope string) {
	c.mu.Lock()
	c.sessionKey = scope
	c.mu.Unlock()
}

// sessionScope reads the declared scope under the client lock. Kept separate
// from the request build so header stamping never nests locks.
func (c *HermesClient) sessionScope() string {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return c.sessionKey
}

// SetSessionKey declares this client's long-term-memory scope (see
// SessionKeyHeader). It lives under the same lock as model/provider because the
// app may re-declare it mid-session after the user edits Settings.
func (c *HermesResponsesClient) SetSessionKey(scope string) {
	c.mu.Lock()
	c.sessionKey = scope
	c.mu.Unlock()
}

// sessionScope reads the declared scope under the client lock. Kept separate
// from buildBody's lock so header stamping never nests locks.
func (c *HermesResponsesClient) sessionScope() string {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return c.sessionKey
}

// SetSessionKey declares this client's long-term-memory scope (see
// SessionKeyHeader) for the cancellable /v1/runs path. Locked like the other
// two: StartRun/RunStatus/CancelRun read it, and cancel can fire mid-run.
func (c *HermesRunClient) SetSessionKey(scope string) {
	c.mu.Lock()
	c.sessionKey = scope
	c.mu.Unlock()
}

// sessionScope reads the declared scope under the client lock.
func (c *HermesRunClient) sessionScope() string {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return c.sessionKey
}
