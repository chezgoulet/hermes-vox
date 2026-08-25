//go:build !android && !ios

package main

import "github.com/hajimehoshi/ebiten/v2"

// main is the desktop / js-wasm entrypoint (no golang.org/x/mobile/app here —
// that package is mobile-only and would break non-Android builds).
func main() {
	ebiten.SetWindowSize(ScreenWidth, ScreenHeight)
	ebiten.SetWindowTitle("Hermes Vox")
	if err := ebiten.RunGame(NewGame()); err != nil {
		panic(err)
	}
}
