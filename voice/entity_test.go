package voice

import (
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// ---- EntityURL: the endpoint a user actually types ----

// TestEntityURLToleratesPastedTrailingSlash is the field bug in a test. The base
// URL is hand-typed in onboarding / Settings -> Entity, and "http://host:8642/"
// is what people paste. Naive concatenation asks for "//v1/models", which the
// Hermes API server answers with 404 (verified live against the gateway — as
// does a trailing-slash path), so the failure surfaces as a misleading "Could
// not reach the entity — check URL + key". Every connector joins through
// EntityURL.
func TestEntityURLToleratesPastedTrailingSlash(t *testing.T) {
	cases := []struct{ name, base, path, want string }{
		{"plain", "http://h:8642", "/v1/models", "http://h:8642/v1/models"},
		{"trailing slash", "http://h:8642/", "/v1/models", "http://h:8642/v1/models"},
		{"several trailing slashes", "http://h:8642///", "/v1/models", "http://h:8642/v1/models"},
		{"surrounding whitespace", "  http://h:8642/  ", "/v1/models", "http://h:8642/v1/models"},
		{"path keeps its own slashes", "http://h:8642/", "/v1/runs/run_1/stop", "http://h:8642/v1/runs/run_1/stop"},
		{"base subpath is preserved", "http://h:8642/base", "/v1/models", "http://h:8642/base/v1/models"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got := EntityURL(tc.base, tc.path)
			if got != tc.want {
				t.Fatalf("EntityURL(%q, %q) = %q, want %q", tc.base, tc.path, got, tc.want)
			}
			// The contract, not just the string: no doubled slash after the
			// scheme — that is the shape the gateway 404s on.
			rest := got
			if i := strings.Index(rest, "://"); i >= 0 {
				rest = rest[i+3:]
			}
			if strings.Contains(rest, "//") {
				t.Fatalf("joined URL %q contains a doubled slash (the gateway 404s on that)", got)
			}
		})
	}
}

// TestTrailingSlashBaseReachesTheRealPath is the end-to-end form of the same
// contract: a client built from a trailing-slash base URL must hit "/v1/models"
// on the server. Pre-fix it hit "//v1/models".
func TestTrailingSlashBaseReachesTheRealPath(t *testing.T) {
	var gotPath string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath = r.URL.Path
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{"object":"list","data":[]}`)
	}))
	defer srv.Close()

	c := NewHermesResponsesClient(srv.URL+"/", "testkey", "hermes-agent")
	if err := c.Ping(); err != nil {
		t.Fatalf("Ping with a trailing-slash base URL failed: %v", err)
	}
	if gotPath != "/v1/models" {
		t.Fatalf("server saw path %q, want %q", gotPath, "/v1/models")
	}
}
