// Package main is a self-contained Ebitengine starter demo. Delete or replace
// this file when creating an application of your own.
package main

import (
	"image/color"
	"math"

	"github.com/hajimehoshi/ebiten/v2"
	"github.com/hajimehoshi/ebiten/v2/inpututil"
	"github.com/hajimehoshi/ebiten/v2/text"
	"golang.org/x/image/font/basicfont"
)

// ScreenWidth and ScreenHeight define the initial window size in logical pixels.
const (
	ScreenWidth  = 640
	ScreenHeight = 480
	FrameRate    = 60
	Label        = "Hello, world."
	LabelWidth   = 13 * 7
	LabelHeight  = 13
	BounceLoss   = 0.82
	Friction     = 0.995
	StopSpeed    = 1.0
)

// Game implements a draggable text label with simple inertial motion.
type Game struct {
	x, y        float64
	vx, vy      float64
	dragging    bool
	dragOffsetX float64
	dragOffsetY float64
	lastMouseX  float64
	lastMouseY  float64
}

// NewGame returns a new Game instance.
func NewGame() *Game {
	return &Game{
		x:  float64(ScreenWidth-LabelWidth) / 2,
		y:  float64(ScreenHeight-LabelHeight) / 2,
		vx: 2.8,
		vy: 1.9,
	}
}

// Update runs once per game tick.
func (g *Game) Update() error {
	mouseX, mouseY := ebiten.CursorPosition()
	mx, my := float64(mouseX), float64(mouseY)

	if inpututil.IsMouseButtonJustPressed(ebiten.MouseButtonLeft) && g.contains(mx, my) {
		g.dragging = true
		g.dragOffsetX = mx - g.x
		g.dragOffsetY = my - g.y
		g.vx, g.vy = 0, 0
	}

	if g.dragging {
		if ebiten.IsMouseButtonPressed(ebiten.MouseButtonLeft) {
			g.vx = mx - g.lastMouseX
			g.vy = my - g.lastMouseY
			g.x = mx - g.dragOffsetX
			g.y = my - g.dragOffsetY
		} else {
			g.dragging = false
		}
	} else {
		g.x += g.vx
		g.y += g.vy
		g.vx *= Friction
		g.vy *= Friction
		if math.Hypot(g.vx, g.vy) < StopSpeed {
			g.vx, g.vy = 0, 0
		}
	}

	g.bounce()
	g.lastMouseX, g.lastMouseY = mx, my
	return nil
}

// Draw renders the current frame.
func (g *Game) Draw(screen *ebiten.Image) {
	screen.Fill(color.RGBA{R: 30, G: 30, B: 46, A: 255})
	text.Draw(screen, Label, basicfont.Face7x13, int(g.x), int(g.y)+LabelHeight-2, color.White)
}

// Layout returns the screen size.
func (g *Game) Layout(outsideWidth, outsideHeight int) (int, int) {
	return ScreenWidth, ScreenHeight
}

func (g *Game) contains(x, y float64) bool {
	return x >= g.x && x <= g.x+LabelWidth && y >= g.y && y <= g.y+LabelHeight
}

func (g *Game) bounce() {
	if g.x < 0 {
		g.x = 0
		g.vx = math.Abs(g.vx) * BounceLoss
	} else if g.x+LabelWidth > ScreenWidth {
		g.x = ScreenWidth - LabelWidth
		g.vx = -math.Abs(g.vx) * BounceLoss
	}
	if g.y < 0 {
		g.y = 0
		g.vy = math.Abs(g.vy) * BounceLoss
	} else if g.y+LabelHeight > ScreenHeight {
		g.y = ScreenHeight - LabelHeight
		g.vy = -math.Abs(g.vy) * BounceLoss
	}
}

func main() {
	ebiten.SetWindowSize(ScreenWidth, ScreenHeight)
	ebiten.SetWindowTitle("Ebitengine Boilerplate")
	ebiten.SetTPS(int(FrameRate))

	if err := ebiten.RunGame(NewGame()); err != nil {
		panic(err)
	}
}
