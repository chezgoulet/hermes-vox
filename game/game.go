package game

import (
	"github.com/hajimehoshi/ebiten/v2"
	"github.com/hajimehoshi/ebiten/v2/ebitenutil"
)

const (
	ScreenWidth  = 640
	ScreenHeight = 480
)

// Game is the Hermes Vox conversation shell. It will drive the VoiceBackend
// (local / self-hosted / cloud), the mic -> inference -> TTS -> avatar loop,
// with Hermes as the entity. This is the scaffold the voice UI is built into.
type Game struct{}

func NewGame() *Game { return &Game{} }

func (g *Game) Update() error { return nil }

func (g *Game) Draw(screen *ebiten.Image) {
	ebitenutil.DebugPrint(screen, "Hermes Vox — the voice of Hermes")
}

func (g *Game) Layout(outsideWidth, outsideHeight int) (int, int) {
	return ScreenWidth, ScreenHeight
}
