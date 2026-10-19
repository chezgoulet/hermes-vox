package main

import (
	"bytes"
	"os"
	"path/filepath"
	"testing"
)

// TestGoldens is the Go side of the parity harness: every fixture under
// testdata/stream must replay to exactly its committed golden. The Kotlin side
// (StreamParityTest) checks the same goldens, so a drift on either side fails.
// Regenerate with: go run ./tools/streamdump -update testdata/stream/*.sse
func TestGoldens(t *testing.T) {
	fixtures, err := filepath.Glob(filepath.Join("..", "..", "testdata", "stream", "*.sse"))
	if err != nil {
		t.Fatal(err)
	}
	if len(fixtures) == 0 {
		t.Fatal("no fixtures found under testdata/stream")
	}
	for _, path := range fixtures {
		t.Run(filepath.Base(path), func(t *testing.T) {
			want, err := os.ReadFile(GoldenPath(path))
			if err != nil {
				t.Fatalf("missing golden (run streamdump -update): %v", err)
			}
			f, err := ParseFixture(path)
			if err != nil {
				t.Fatal(err)
			}
			d, err := Replay(f)
			if err != nil {
				t.Fatal(err)
			}
			got, err := Encode(d)
			if err != nil {
				t.Fatal(err)
			}
			if !bytes.Equal(got, want) {
				t.Fatalf("%s drifted from its golden.\n--- got\n%s\n--- want\n%s", f.Name, got, want)
			}
		})
	}
}
