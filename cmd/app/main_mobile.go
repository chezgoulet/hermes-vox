//go:build android || ios

package main

import (
	"github.com/chezgoulet/hermes-vox/game"
	"github.com/hajimehoshi/ebiten/v2"
	"golang.org/x/mobile/app"
)

// main is the Android / iOS entrypoint for the standalone path.
func main() {
	app.Main(func(a app.App) {
		if err := ebiten.RunGame(game.NewGame()); err != nil {
			panic(err)
		}
	})
}
