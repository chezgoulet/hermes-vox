//go:build android || ios

package main

import (
	"github.com/hajimehoshi/ebiten/v2"
	"golang.org/x/mobile/app"
)

// main is the Android / iOS entrypoint. It uses golang.org/x/mobile/app so
// `gomobile build` produces the .apk, and hands the Ebitengine game to the
// mobile app lifecycle.
func main() {
	app.Main(func(a app.App) {
		if err := ebiten.RunGame(NewGame()); err != nil {
			panic(err)
		}
	})
}
