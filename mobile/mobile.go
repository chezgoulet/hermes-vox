// Package mobile is the gomobile-bind surface for the native Android shell.
// It exposes the Ebitengine game to the Java/Kotlin side via `gomobile bind`,
// so the native shell hosts the Ebitengine view without the standalone-build
// native-linking quirk.
package mobile

import (
	"github.com/chezgoulet/hermes-vox/game"
	ebitenmobile "github.com/hajimehoshi/ebiten/v2/mobile"
)

var running bool

// IsRunning reports whether the game has started.
func IsRunning() bool { return running }

// Start kicks off the Ebitengine game. The native shell calls this once its
// GLSurfaceView is ready.
func Start() {
	running = true
	ebitenmobile.SetGame(game.NewGame())
}

// UpdateTouchesOnAndroid dispatches touch events from the Java side.
func UpdateTouchesOnAndroid(action int, id int, x int, y int) {}
