package voice

import (
	"context"
	"strconv"
	"sync"
	"testing"
)

// TestScopeRedeclarationIsRaceFree exercises the setters while requests are in
// flight — the app re-declares the scope when the user edits Settings, which can
// land mid-turn (or mid-run, on the cancel path). Run under `go test -race`:
// this is the case that used to be unexercised, so a clean -race run meant
// "never tried", not "proven safe".
//
// Neutering the client locks in a scratch copy makes this test report a DATA
// RACE on SetSessionKey vs sessionScope, so the contract is enforced, not
// assumed.
func TestScopeRedeclarationIsRaceFree(t *testing.T) {
	rec := newProbeRecorder()
	srv := recordingEntityServer(t, rec)
	defer srv.Close()

	chat := NewHermesClient(srv.URL, "testkey", "hermes-agent")
	runs := NewHermesRunClient(srv.URL, "testkey", "hermes-agent")
	ctx := context.Background()

	stop := make(chan struct{})
	var wg sync.WaitGroup
	wg.Add(1)
	go func() {
		defer wg.Done()
		for i := 0; ; i++ {
			select {
			case <-stop:
				return
			default:
			}
			scope := "agent:vox:dev:member-" + strconv.Itoa(i%8)
			chat.SetSessionKey(scope)
			runs.SetSessionKey(scope)
		}
	}()

	for i := 0; i < 40; i++ {
		if _, err := chat.Chat(ctx, []ChatMessage{{Role: "user", Content: "hi"}}); err != nil {
			t.Fatalf("chat: %v", err)
		}
		if _, err := runs.StartRun(ctx, "hi", "", ""); err != nil {
			t.Fatalf("run: %v", err)
		}
	}
	close(stop)
	wg.Wait()
}
