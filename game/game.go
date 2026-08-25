package game

import (
	"context"
	"sync"

	"github.com/chezgoulet/hermes-vox/voice"
	"github.com/hajimehoshi/ebiten/v2"
	"github.com/hajimehoshi/ebiten/v2/ebitenutil"
)

const (
	ScreenWidth  = 640
	ScreenHeight = 480
)

// Game is the Hermes Vox conversation shell. It drives the VoiceBackend + the
// Hermes connector (the entity). The turn loop is carried by voice.Conversation
// (Transcribe -> Hermes -> Synthesize); the Game renders the reply and (later) an
// avatar that reacts to the voice. Hermes is ALWAYS the mind.
type Game struct {
	Convo *voice.Conversation // optional — nil-safe; the entity IS Hermes
	mu    sync.Mutex
	last  string
	ok    bool
}

func NewGame() *Game { return &Game{} }

// WithConversation wires the entity. Set the Backend (Local/SelfHosted/Cloud)
// + a HermesClient (the mind) on the Conversation.
func (g *Game) WithConversation(c *voice.Conversation) *Game {
	g.Convo = c
	return g
}

// TurnText sends a text turn to Hermes (the shell's non-audio path) and stores
// the reply to render. Nil-safe (no entity -> no-op).
func (g *Game) TurnText(ctx context.Context, text string) error {
	if g.Convo == nil {
		return nil
	}
	reply, err := g.Convo.TurnText(ctx, text)
	if err != nil {
		return err
	}
	g.mu.Lock()
	g.last = reply
	g.ok = true
	g.mu.Unlock()
	return nil
}

func (g *Game) Update() error { return nil }

func (g *Game) Draw(screen *ebiten.Image) {
	ebitenutil.DebugPrint(screen, "Hermes Vox — the voice of Hermes")
	// TODO(avatar): a face that reacts to the voice (the Maya-warmth part).
	g.mu.Lock()
	if g.ok {
		ebitenutil.DebugPrintAt(screen, "Hermes: "+g.last, 8, 40)
	}
	g.mu.Unlock()
}

func (g *Game) Layout(outsideWidth, outsideHeight int) (int, int) {
	return ScreenWidth, ScreenHeight
}
