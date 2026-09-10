package voice

import (
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
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

// ---- X-Hermes-Session-Key: who is talking ----

// entityProbe is one recorded request: what the server saw.
type entityProbe struct {
	auth  string
	scope string
}

// probeRecorder collects entityProbe by "METHOD path". The test server runs on
// its own goroutine(s), so the map is mutex-guarded — `go test -race` must stay
// clean here by construction, not by luck.
type probeRecorder struct {
	mu   sync.Mutex
	seen map[string]entityProbe
}

func newProbeRecorder() *probeRecorder {
	return &probeRecorder{seen: map[string]entityProbe{}}
}

func (p *probeRecorder) record(key, auth, scope string) {
	p.mu.Lock()
	p.seen[key] = entityProbe{auth: auth, scope: scope}
	p.mu.Unlock()
}

func (p *probeRecorder) get(key string) (entityProbe, bool) {
	p.mu.Lock()
	defer p.mu.Unlock()
	got, ok := p.seen[key]
	return got, ok
}

// recordingEntityServer answers every endpoint the entity connectors use and
// records the identity headers on each request.
func recordingEntityServer(t *testing.T, rec *probeRecorder) *httptest.Server {
	t.Helper()
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		rec.record(r.Method+" "+r.URL.Path, r.Header.Get("Authorization"), r.Header.Get(SessionKeyHeader))
		stream := r.Header.Get("Accept") == "text/event-stream" || strings.Contains(string(body), `"stream":true`)
		switch {
		case r.URL.Path == "/v1/models":
			w.Header().Set("Content-Type", "application/json")
			_, _ = io.WriteString(w, `{"object":"list","data":[]}`)
		case strings.HasSuffix(r.URL.Path, "/stop"):
			w.WriteHeader(http.StatusNoContent)
		case strings.HasPrefix(r.URL.Path, "/v1/runs/"):
			w.Header().Set("Content-Type", "application/json")
			_, _ = io.WriteString(w, `{"status":"completed","output":"ok"}`)
		case r.URL.Path == "/v1/runs":
			w.Header().Set("Content-Type", "application/json")
			_, _ = io.WriteString(w, `{"run_id":"run_1"}`)
		case r.URL.Path == "/v1/chat/completions":
			w.Header().Set("Content-Type", "application/json")
			_, _ = io.WriteString(w, `{"choices":[{"message":{"role":"assistant","content":"ok"}}]}`)
		case stream:
			w.Header().Set("Content-Type", "text/event-stream")
			_, _ = io.WriteString(w, "event: response.created\n")
			_, _ = io.WriteString(w, `data: {"type":"response.created","response":{"id":"resp_1","status":"completed"}}`+"\n\n")
			_, _ = io.WriteString(w, "event: response.output_text.delta\n")
			_, _ = io.WriteString(w, `data: {"type":"response.output_text.delta","item_id":"i1","delta":"ok"}`+"\n\n")
			_, _ = io.WriteString(w, "event: response.completed\n")
			_, _ = io.WriteString(w, `data: {"type":"response.completed","response":{"id":"resp_1","status":"completed"}}`+"\n\n")
			_, _ = io.WriteString(w, "data: [DONE]\n\n")
		default:
			w.Header().Set("Content-Type", "application/json")
			_, _ = io.WriteString(w, `{"id":"resp_1","output":[{"type":"message","content":[{"type":"output_text","text":"ok"}]}]}`)
		}
	}))
}

// TestSessionKeyRidesEveryEntityRequest is the multi-user contract: ONE gateway
// can serve several people (or several devices), and API_SERVER_KEY is a single
// shared bearer credential — it names the deployment, not the caller. The
// declared scope must therefore ride EVERY request the entity sees
// (/v1/chat/completions, /v1/responses non-streaming AND streaming, /v1/runs
// start/status/cancel, the /v1/models probe). A path that forgets it writes
// another person's turns into the shared memory scope — exactly the bug this
// header exists to fix.
func TestSessionKeyRidesEveryEntityRequest(t *testing.T) {
	rec := newProbeRecorder()
	srv := recordingEntityServer(t, rec)
	defer srv.Close()

	const scope = "agent:vox:tablet:member-42"
	ctx := context.Background()

	chat := NewHermesClient(srv.URL, "testkey", "hermes-agent")
	chat.SetSessionKey(scope)
	if _, err := chat.Chat(ctx, []ChatMessage{{Role: "user", Content: "hi"}}); err != nil {
		t.Fatalf("chat: %v", err)
	}

	streams := NewHermesResponsesClient(srv.URL, "testkey", "hermes-agent")
	streams.SetSessionKey(scope)
	if _, err := streams.Response(ctx, "hi", ""); err != nil {
		t.Fatalf("response: %v", err)
	}
	if err := streams.Ping(); err != nil {
		t.Fatalf("ping: %v", err)
	}
	if _, err := streams.Stream(ctx, "hi", "", nil); err != nil {
		t.Fatalf("stream: %v", err)
	}

	runs := NewHermesRunClient(srv.URL, "testkey", "hermes-agent")
	runs.SetSessionKey(scope)
	if _, err := runs.StartRun(ctx, "hi", "", ""); err != nil {
		t.Fatalf("start run: %v", err)
	}
	if _, err := runs.RunStatus(ctx, "run_1"); err != nil {
		t.Fatalf("run status: %v", err)
	}
	if err := runs.CancelRun(ctx, "run_1"); err != nil {
		t.Fatalf("cancel run: %v", err)
	}

	wantPaths := []string{
		"POST /v1/chat/completions",
		"POST /v1/responses",
		"GET /v1/models",
		"POST /v1/runs",
		"GET /v1/runs/run_1",
		"POST /v1/runs/run_1/stop",
	}
	for _, p := range wantPaths {
		got, ok := rec.get(p)
		if !ok {
			t.Errorf("%s never reached the server", p)
			continue
		}
		if got.auth != "Bearer testkey" {
			t.Errorf("%s: Authorization = %q, want the shared bearer", p, got.auth)
		}
		if got.scope != scope {
			t.Errorf("%s: %s = %q, want %q", p, SessionKeyHeader, got.scope, scope)
		}
	}
}

// TestScopeDeclaredAfterConstructionAppliesToTheNextRequest pins the setter the
// native app uses: MainActivity builds HermesSession(url, key, model) and then
// declares the scope (the gomobile signature stays untouched), so the value must
// take effect on the very next turn without rebuilding the client.
func TestScopeDeclaredAfterConstructionAppliesToTheNextRequest(t *testing.T) {
	rec := newProbeRecorder()
	srv := recordingEntityServer(t, rec)
	defer srv.Close()

	c := NewHermesResponsesClient(srv.URL, "testkey", "hermes-agent")
	if _, err := c.Response(context.Background(), "hi", ""); err != nil {
		t.Fatalf("response: %v", err)
	}
	c.SetSessionKey("agent:vox:tablet:member-7")
	if _, err := c.Response(context.Background(), "hi", ""); err != nil {
		t.Fatalf("response after scope: %v", err)
	}

	got, ok := rec.get("POST /v1/responses")
	if !ok {
		t.Fatal("POST /v1/responses never reached the server")
	}
	if got.scope != "agent:vox:tablet:member-7" {
		t.Fatalf("latest request scope = %q, want the value declared after construction", got.scope)
	}
}

// TestNoScopeDeclaredSendsNoSessionKeyHeader is the compatibility half of the
// change: an install that declares nothing must behave exactly as it did before
// this header existed — no header at all, leaving the gateway's per-transcript
// memory default in place. A blank value must never be sent, because the gateway
// treats any present value as a declared scope.
func TestNoScopeDeclaredSendsNoSessionKeyHeader(t *testing.T) {
	rec := newProbeRecorder()
	srv := recordingEntityServer(t, rec)
	defer srv.Close()

	ctx := context.Background()
	chat := NewHermesClient(srv.URL, "testkey", "hermes-agent")
	if _, err := chat.Chat(ctx, []ChatMessage{{Role: "user", Content: "hi"}}); err != nil {
		t.Fatalf("chat: %v", err)
	}
	streams := NewHermesResponsesClient(srv.URL, "testkey", "hermes-agent")
	if _, err := streams.Response(ctx, "hi", ""); err != nil {
		t.Fatalf("response: %v", err)
	}
	runs := NewHermesRunClient(srv.URL, "testkey", "hermes-agent")
	if _, err := runs.StartRun(ctx, "hi", "", ""); err != nil {
		t.Fatalf("start run: %v", err)
	}

	for _, p := range []string{"POST /v1/chat/completions", "POST /v1/responses", "POST /v1/runs"} {
		got, ok := rec.get(p)
		if !ok {
			t.Errorf("%s never reached the server", p)
			continue
		}
		if got.scope != "" {
			t.Errorf("%s: %s = %q, want no header when no scope is declared", p, SessionKeyHeader, got.scope)
		}
		if got.auth != "Bearer testkey" {
			t.Errorf("%s: Authorization = %q — declaring no scope must not disturb the bearer", p, got.auth)
		}
	}
}

// TestConfigLoadsSessionKeyFromEnv covers the non-Android entry point (the Go
// layer / house tooling): the scope is operator-declared config, so it loads from
// HERMES_VOX_HERMES_SESSION_KEY like the base URL and model — never baked in.
func TestConfigLoadsSessionKeyFromEnv(t *testing.T) {
	t.Setenv(envBaseURL, "http://h:8642")
	t.Setenv(envAPIKey, "secret")
	t.Setenv(envModel, "m")
	t.Setenv(envSessionKey, "agent:vox:cli:member-9")

	cfg := LoadFromEnv()
	if cfg.HermesSessionKey != "agent:vox:cli:member-9" {
		t.Fatalf("HermesSessionKey = %q", cfg.HermesSessionKey)
	}
	if got := cfg.Client(); got == nil || got.sessionKey != "agent:vox:cli:member-9" {
		t.Fatalf("Config.Client() did not carry the session key onto the client")
	}

	// Unset: empty, not a default.
	t.Setenv(envSessionKey, "")
	if got := LoadFromEnv().HermesSessionKey; got != "" {
		t.Fatalf("HermesSessionKey = %q with the env unset, want empty", got)
	}
}
