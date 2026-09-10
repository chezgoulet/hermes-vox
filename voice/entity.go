package voice

import "strings"

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
