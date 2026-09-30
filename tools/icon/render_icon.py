#!/usr/bin/env python3
"""Render the launcher icon from the being's own shaders.

The icon is not a drawing of the being: it IS the being. This script lifts the GLSL
out of android/app/src/main/java/com/hermesvox/BeingShaders.kt, runs the same
pipeline as BeingRenderer (additive points -> bright-pass -> two-level bloom ->
filmic composite) headless on the host GPU, and writes the Android launcher assets:

  mipmap-*/ic_launcher_foreground.png   adaptive foreground (the being, with alpha)
  mipmap-*/ic_launcher_background.png   adaptive background (deep night gradient)
  mipmap-*/ic_launcher.png              legacy square icon (both layers, rounded)
  mipmap-*/ic_launcher_round.png        legacy round icon
  drawable/ic_launcher_monochrome.png   Android 13+ themed-icon mask
  --preview PATH                        a 512 px store/preview icon

The composition is the voice: the idle orb (aura, shape 0) with the speaking
equalizer (soundwave, shape 13) running through it, in the app's own palette.

  python tools/icon/render_icon.py [--preview out.png] [--dry]
"""
from __future__ import annotations

import argparse
import re
from pathlib import Path

import moderngl
import numpy as np
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[2]
SHADERS = ROOT / "android/app/src/main/java/com/hermesvox/BeingShaders.kt"
RES = ROOT / "android/app/src/main/res"

SIZE = 1024                       # render size (the 108 dp adaptive canvas)
DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}


def rgb(hex6: str) -> tuple[float, float, float]:
    return tuple(int(hex6[i:i + 2], 16) / 255 for i in (0, 2, 4))


# AvatarView palette (cIdle/cIdleHi, cSpeak/cSpeakHi).
IDLE, IDLE_HI = rgb("5FA8C4"), rgb("A6E6F5")
SPEAK, SPEAK_HI = rgb("9B6BFF"), rgb("E2CCFF")
CYAN, CYAN_HI = rgb("2AC3DC"), rgb("A9F1FF")

# Each pass draws the swarm once, additively, into the same scene.
PASSES = [
    # the orb: a soft idle nebula behind the voice
    dict(shape=0, t=11.0, body=0.21, bright=0.8, px=5.0, base=SPEAK, acc=IDLE_HI, amp=0.0),
    # the voice: the speaking equalizer through the orb's heart
    dict(shape=13, t=4.35, body=0.25, bright=1.2, px=6.5, base=CYAN, acc=CYAN_HI, amp=0.5),
]
GLOW = 1.1
HALO = 1.0
DENSITY = 8                       # particles x this: an icon is a still, so it can afford solid forms

# THE POSE. Live, the soundwave's forty bars ride the voice at random; a still of that is
# noise, and forty bars are sub-pixel at launcher size. The icon poses the same shape
# function: fewer, bolder bars holding a symmetric speech envelope.
BARS = 15
POSE = {
    # the bar heights: a held, symmetric "voice" envelope instead of the live noise
    r"float barLevel\(float k\) \{.*?\n\}":
        """float barLevel(float k) {
    float c = (k - (BARS - 1.0) * 0.5) / (BARS * 0.5);
    return 0.10 + 0.78 * exp(-c * c * 3.2) * (0.78 + 0.22 * cos(c * 9.0));
}""",
    r"floor\(r\.x \* 40\.0\)": "floor(r.x * BARS)",
    r"\(k \+ 0\.5\) / 40\.0 \* 2\.5 \+ \(r\.z - 0\.5\) \* 0\.022":
        "(k + 0.5) / BARS * 2.5 + (r.z - 0.5) * 0.075",
    # every bar holds the same number of points, so a tall bar would read dimmer; even them out
    r"inten = 0\.55 \+ 0\.9 \* cap;": "inten = (0.55 + 0.9 * cap) * (0.35 + 1.3 * H);",
}


def kotlin_string(src: str, name: str) -> str:
    m = re.search(rf'val {name} = """(.*?)"""', src, re.S)
    if not m:
        raise SystemExit(f"{name} not found in {SHADERS}")
    return m.group(1)


def desktop(glsl: str, particles: int) -> str:
    """GLSL ES 3.00 -> desktop GLSL 3.30 (precision qualifiers are legal no-ops there)."""
    glsl = glsl.replace("${PARTICLES}", str(particles))
    if "sSoundwave" in glsl:
        for pat, rep in POSE.items():
            glsl, hits = re.subn(pat, lambda _m: rep.replace("BARS", f"{BARS:.1f}"), glsl, count=1, flags=re.S)
            if hits != 1:
                raise SystemExit(f"pose pattern no longer matches BeingShaders.kt: {pat}")
    return glsl.replace("#version 300 es", "#version 330 core", 1)


def particles(n: int) -> np.ndarray:
    """The same deterministic identities as BeingRenderer.buildParticles in spirit: an id,
    four uniforms, a long-tailed size and brightness character."""
    rng = np.random.default_rng(7)
    ids = np.arange(n, dtype=np.float32)
    r = rng.random((n, 4), dtype=np.float32)
    k = np.stack([rng.random(n) ** 2.2, 0.45 + rng.random(n) ** 1.8 * 0.85], 1).astype(np.float32)
    return np.concatenate([ids[:, None], r, k], 1).astype("f4")


def render() -> np.ndarray:
    src = SHADERS.read_text()
    live = int(re.search(r"const val PARTICLES = (\d+)", src).group(1))
    n = live * DENSITY
    ctx = moderngl.create_standalone_context(backend="egl", require=330)
    ctx.enable(moderngl.PROGRAM_POINT_SIZE)

    def prog(vs: str, fs: str) -> moderngl.Program:
        return ctx.program(vertex_shader=desktop(kotlin_string(src, vs), n),
                           fragment_shader=desktop(kotlin_string(src, fs), n))

    point = prog("VERTEX", "FRAGMENT_POINT")
    bright = prog("VERTEX_QUAD", "FRAGMENT_BRIGHT")
    blur = prog("VERTEX_QUAD", "FRAGMENT_BLUR")
    comp = prog("VERTEX_QUAD", "FRAGMENT_COMPOSITE")

    vbo = ctx.buffer(particles(n).tobytes())
    vao = ctx.vertex_array(point, [(vbo, "1f 4f 2f", "aId", "aR", "aK")])
    quad = ctx.vertex_array(comp, [])

    def target(w: int, h: int):
        t = ctx.texture((w, h), 4, dtype="f2")
        t.filter = (moderngl.LINEAR, moderngl.LINEAR)
        t.repeat_x = t.repeat_y = False
        return t, ctx.framebuffer([t])

    W = H = SIZE
    scene, sceneF = target(W, H)
    a0, a0F = target(W // 4, H // 4); a1, a1F = target(W // 4, H // 4)
    b0, b0F = target(W // 8, H // 8); b1, b1F = target(W // 8, H // 8)

    def uni(p: moderngl.Program, name: str, v) -> None:
        if name in p:
            p[name].value = v

    sceneF.use(); ctx.clear(0, 0, 0, 1)
    ctx.enable(moderngl.BLEND); ctx.blend_func = (moderngl.ONE, moderngl.ONE)
    for ps in PASSES:
        body = ps["body"] * SIZE
        for k, v in dict(uT=ps["t"], uMix=1.0, uAmp=ps["amp"], uWork=0.0, uBreath=0.0, uSpin=0.0,
                         uSpeed=1.0, uBurst=1.0, uSeed=0.0, uStall=0.0, uBlink=1.0, uHead=-1.5708,
                         uArm=0.0, uArmPh=0.0, uBodyR=body, uPx=ps["px"], uEnergy=0.0, uFlicker=0.0,
                         uBright=ps["bright"] * live / n, uSize=1.0, uCore=1.0, uEdge=1.0).items():
            uni(point, k, v)
        uni(point, "uA", ps["shape"]); uni(point, "uB", ps["shape"])
        uni(point, "uCenter", (W / 2, H / 2)); uni(point, "uRes", (float(W), float(H)))
        uni(point, "uPupil", (0.0, 0.0)); uni(point, "uOff", (0.0, 0.0))
        uni(point, "uBase", ps["base"]); uni(point, "uAcc", ps["acc"])
        vao.render(moderngl.POINTS)
    ctx.disable(moderngl.BLEND)

    def run(p: moderngl.Program, fbo, tex_units: dict, **u) -> None:
        fbo.use()
        for i, (name, tex) in enumerate(tex_units.items()):
            tex.use(i); uni(p, name, i)
        for k, v in u.items():
            uni(p, k, v)
        ctx.vertex_array(p, []).render(moderngl.TRIANGLES, vertices=3)

    run(bright, a0F, {"uTex": scene}, uTexel=(1 / W, 1 / H))
    for _ in range(2):
        run(blur, a1F, {"uTex": a0}, uDir=(4 / W, 0.0))
        run(blur, a0F, {"uTex": a1}, uDir=(0.0, 4 / H))
    run(bright, b0F, {"uTex": a0}, uTexel=(4 / W, 4 / H))
    for _ in range(2):
        run(blur, b1F, {"uTex": b0}, uDir=(8 / W, 0.0))
        run(blur, b0F, {"uTex": b1}, uDir=(0.0, 8 / H))

    out, outF = target(W, H)
    body = PASSES[0]["body"] * SIZE
    run(comp, outF, {"uScene": scene, "uBloomA": a0, "uBloomB": b0}, uGlow=GLOW, uHalo=HALO,
        uBase=PASSES[0]["base"], uCenterUv=(0.5, 0.5), uHaloR=(body * 1.7 / W, body * 1.7 / H))
    del quad
    img = np.frombuffer(out.read(), dtype=np.float16).reshape(H, W, 4)[::-1, :, :3].astype(np.float32)
    return np.clip(img, 0.0, 1.0)


def background(size: int) -> Image.Image:
    """Deep night: a faint violet-blue lift at the centre falling to near-black."""
    y, x = np.mgrid[0:size, 0:size].astype(np.float32) / (size - 1) - 0.5
    d = np.sqrt(x * x + y * y) / 0.7071
    inner, outer = np.array([0.075, 0.07, 0.16]), np.array([0.012, 0.014, 0.03])
    c = outer + (inner - outer) * np.clip(1 - d, 0, 1)[..., None] ** 1.6
    return Image.fromarray((c * 255 + 0.5).astype(np.uint8), "RGB").convert("RGBA")


def foreground(light: np.ndarray) -> Image.Image:
    """Additive light over black -> straight-alpha RGBA that composites back exactly over black."""
    a = light.max(axis=2, keepdims=True)
    col = np.where(a > 1e-4, light / np.maximum(a, 1e-4), 0.0)
    rgba = np.concatenate([col, a], 2)
    return Image.fromarray((rgba * 255 + 0.5).astype(np.uint8), "RGBA")


def monochrome(light: np.ndarray) -> Image.Image:
    lum = light @ np.array([0.2126, 0.7152, 0.0722], dtype=np.float32)
    a = np.clip(lum / max(np.percentile(lum, 99.7), 1e-3), 0, 1)
    a = np.clip((a - 0.06) / 0.94, 0, 1) ** 0.7        # drop the faint halo: a themed icon is a clean mask
    white = np.ones_like(light)
    return Image.fromarray((np.concatenate([white, a[..., None]], 2) * 255 + 0.5).astype(np.uint8), "RGBA")


def legacy(fg: Image.Image, bg: Image.Image, px: int, round_: bool) -> Image.Image:
    """Legacy (pre-O) icons: the 108 dp canvas cropped to its 72 dp visible area, masked."""
    comp = Image.alpha_composite(bg, fg)
    inset = int(SIZE * (18 / 108))
    comp = comp.crop((inset, inset, SIZE - inset, SIZE - inset)).resize((px, px), Image.LANCZOS)
    s = px * 4
    mask = Image.new("L", (s, s), 0)
    draw = ImageDraw.Draw(mask)
    if round_:
        draw.ellipse((0, 0, s - 1, s - 1), fill=255)
    else:
        draw.rounded_rectangle((0, 0, s - 1, s - 1), radius=int(s * 0.22), fill=255)
    comp.putalpha(mask.resize((px, px), Image.LANCZOS))
    return comp


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--preview", type=Path, help="write a 512 px composited preview here")
    ap.add_argument("--dry", action="store_true", help="render the preview only; do not touch res/")
    args = ap.parse_args()

    light = render()
    fg, bg, mono = foreground(light), background(SIZE), monochrome(light)
    if args.preview:
        legacy(fg, bg, 512, round_=False).save(args.preview)
        print(f"preview -> {args.preview}")
    if args.dry:
        return
    for name, k in DENSITIES.items():
        d = RES / f"mipmap-{name}"
        layer = round(108 * k)
        fg.resize((layer, layer), Image.LANCZOS).save(d / "ic_launcher_foreground.png", optimize=True)
        bg.resize((layer, layer), Image.LANCZOS).convert("RGB").save(d / "ic_launcher_background.png", optimize=True)
        legacy(fg, bg, round(48 * k), round_=False).save(d / "ic_launcher.png", optimize=True)
        legacy(fg, bg, round(48 * k), round_=True).save(d / "ic_launcher_round.png", optimize=True)
    mono.resize((432, 432), Image.LANCZOS).save(RES / "drawable-nodpi/ic_launcher_monochrome.png", optimize=True)
    print("wrote launcher icons under", RES)


if __name__ == "__main__":
    main()
