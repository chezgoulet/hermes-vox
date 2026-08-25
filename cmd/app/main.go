//go:build !android && !ios

package main

import (
	"github.com/chezgoulet/hermes-vox/game"
	"github.com/hajimehoshi/ebiten/v2"
)

// main is the desktop / js-wasm entrypoint.
func main() {
	ebiten.SetWindowSize(game.ScreenWidth, game.ScreenHeight)
	ebiten.SetWindowTitle("Hermes Vox")
	if err := ebiten.RunGame(game.NewGame()); err != nil {
		panic(err)
	}
}
