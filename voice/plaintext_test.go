package voice

import "testing"

// #23: plainText used to concatenate the surviving lines with no separator, so
// "…done." and "Next…" became "…done.Next…". The reply text and the pause the
// engine takes at a line break both depend on the boundary being kept.
func TestPlainTextKeepsLineBoundaries(t *testing.T) {
	in := "[tool:read]\nFirst sentence.\n\nSecond sentence.\n[tool-done]\nThird."
	got := plainText(in)
	want := "First sentence.\nSecond sentence.\nThird."
	if got != want {
		t.Fatalf("plainText() = %q, want %q", got, want)
	}
}

func TestPlainTextEmptyWhenOnlyToolMarkers(t *testing.T) {
	if got := plainText("[tool:read]\n[tool-done]"); got != "" {
		t.Fatalf("plainText() = %q, want empty", got)
	}
}
