package com.hermesvox

/**
 * BeingShaders — the being's shapes, as GLSL (OpenGL ES 3.0).
 *
 * Every particle is STATELESS: its position is computed in the vertex shader each frame from
 * its identity (an index + four seeded randoms) and the shape it is forming. That is what makes
 * the shapes exact — a ring is a ring, a lid is a curve, a sphere has a back — and cheap enough
 * to draw thousands of points. A shape change is a per-particle staggered morph between two
 * shape functions (uA -> uB by uMix), with a swirl through the middle of the flight.
 *
 * Coordinates: body space, y up, 1.0 = the body radius. Each shape writes
 *   pos   — xy on screen, z = depth toward the viewer (-1 back .. +1 front; 0 for flat shapes)
 *   inten — brightness (0 = invisible; ~1 normal; >1 hot)
 *   tone  — 0 = base colour .. 1 = accent; above 1 runs toward white-hot
 *
 * The archetype ids match AvatarView's A_* constants.
 */
object BeingShaders {

    const val PARTICLES = 6000

    val VERTEX = """#version 300 es
precision highp float;
layout(location=0) in float aId;
layout(location=1) in vec4 aR;
layout(location=2) in vec2 aK;       // x: size character, y: brightness character

uniform float uT, uMix, uAmp, uWork, uBreath, uSpin, uSpeed, uBurst, uSeed, uStall;
uniform int uA, uB;
uniform vec2 uCenter, uRes, uPupil, uOff;
uniform float uBodyR, uPx, uEnergy, uFlicker, uBright, uBlink, uHead, uArm, uArmPh, uSize;
uniform vec3 uBase, uAcc;

out vec3 vCol;

const float PI = 3.14159265;
const float TAU = 6.28318531;
const float N = ${PARTICLES}.0;

float h1(float n) { return fract(sin(n * 12.9898 + 78.233) * 43758.5453); }
vec2 rot(vec2 v, float a) { float c = cos(a), s = sin(a); return vec2(c * v.x - s * v.y, s * v.x + c * v.y); }
vec3 rotX(vec3 p, float a) { float c = cos(a), s = sin(a); return vec3(p.x, c * p.y - s * p.z, s * p.y + c * p.z); }
vec3 rotY(vec3 p, float a) { float c = cos(a), s = sin(a); return vec3(c * p.x + s * p.z, p.y, -s * p.x + c * p.z); }
vec3 rotZ(vec3 p, float a) { vec2 q = rot(p.xy, a); return vec3(q, p.z); }
vec2 gauss2(vec2 r) { float m = sqrt(-2.0 * log(max(r.x, 1e-4))); return m * vec2(cos(TAU * r.y), sin(TAU * r.y)); }
float vnoise(float x) { float i = floor(x), f = fract(x); f = f * f * (3.0 - 2.0 * f); return mix(h1(i), h1(i + 1.0), f) * 2.0 - 1.0; }
// A point on a unit circle, and a 3D point projected with a gentle perspective.
vec2 circ(float a) { return vec2(cos(a), sin(a)); }
vec3 persp(vec3 p) { float k = 1.0 / (1.0 - p.z * 0.28); return vec3(p.xy * k, clamp(p.z, -1.0, 1.0)); }

// ---- the shapes -------------------------------------------------------------------------

// 0 AURA: a breathing nebula — a dense core, differential swirl, domain-warped wisps.
void sAura(float s, vec4 r, out vec3 pos, out float inten, out float tone) {
    float rad = pow(r.x, 0.72) * 1.02;
    float ang = TAU * r.y + uT * 0.16 * (1.3 - rad) + uSpin * 0.25;
    vec2 p = circ(ang) * rad;
    p += 0.16 * rad * vec2(sin(p.y * 3.3 + uT * 0.45 + r.z * 5.0), cos(p.x * 2.9 - uT * 0.38 + r.w * 5.0));
    p *= 1.0 + 0.05 * uBreath;
    pos = vec3(p, 0.0);
    inten = (1.5 * exp(-rad * rad * 3.0) + 0.35) * (0.55 + 0.6 * r.w);
    tone = r.z < 0.28 ? 1.0 : rad * 0.4;
}

// Iris fibres around a pupil of radius pr, total radius R. Shared by IRIS and EYE.
void irisAt(float s, vec4 r, float pr, float R, out vec2 p, out float inten, out float tone) {
    float g = fract(s * 7.31 + r.w);
    if (g < 0.60) {                                   // radial striations
        float k = floor(r.x * 140.0);
        float t = fract(r.y * 5.17 + r.z * 0.3);
        float a = k / 140.0 * TAU + 0.05 * sin(t * 13.0 + k * 1.7);
        p = circ(a) * mix(pr + 0.02 * R, R * 0.96, pow(t, 0.85));
        inten = 0.45 + 0.55 * (1.0 - t); tone = 0.35 + 0.5 * t;
    } else if (g < 0.76) {                            // collarette: the zig-zag ruff
        float a = r.x * TAU;
        p = circ(a) * (pr * 1.72 + 0.045 * R * sin(a * 21.0));
        inten = 1.0; tone = 1.0;
    } else if (g < 0.90) {                            // limbal ring: the dark-ringed edge, lit
        float a = r.x * TAU;
        p = circ(a) * R * (0.975 + 0.02 * (r.y - 0.5));
        inten = 0.85; tone = 0.0;
    } else {                                          // pupil margin, crisp
        float a = r.x * TAU;
        p = circ(a) * (pr + 0.012 * R * (r.y - 0.5));
        inten = 1.35; tone = 1.2;
    }
}

// 1 IRIS (listening): a human iris; the pupil breathes and narrows as the voice rises.
void sIris(float s, vec4 r, out vec3 pos, out float inten, out float tone) {
    vec2 p; irisAt(s, r, 0.30 + 0.04 * uBreath - 0.07 * uAmp, 1.0, p, inten, tone);
    pos = vec3(rot(p, uT * 0.03), 0.0);
}

// 2 FLAME: a teardrop that licks upward, a hot core, embers rising off the tip.
void sFlame(float s, vec4 r, out vec3 pos, out float inten, out float tone) {
    if (s > 0.9) {                                    // embers
        float life = fract(r.y + uT * (0.22 + 0.2 * r.z));
        vec2 p = vec2((r.x - 0.5) * 0.4 + 0.3 * sin(life * 5.0 + r.w * 6.0) * life, 0.2 + life * 1.3);
        pos = vec3(p, 0.0); inten = (1.0 - life) * 1.4; tone = 1.4; return;
    }
    float h = fract(r.y + uT * (0.5 + 0.3 * r.z) * (0.75 + uWork * 0.8));
    float w = 0.58 * pow(sin(PI * pow(h, 0.6)), 1.05) * (1.0 - 0.5 * h);
    float lat = r.x * 2.0 - 1.0; lat = sign(lat) * pow(abs(lat), 0.65);
    float sway = 0.20 * h * h * sin(uT * 2.9 + h * 4.5) + 0.07 * h * sin(uT * 8.3 + h * 11.0 + r.w * 3.0);
    pos = vec3(lat * w + sway, -0.95 + h * 1.95, 0.0);
    float core = 1.0 - abs(lat);
    inten = (1.25 - h * 0.85) * (0.45 + 0.85 * core);
    tone = mix(1.5, 0.0, h) * (0.4 + 0.6 * core);    // white-hot at the root, base colour at the tip
}

// 3 VORTEX: a two-armed spiral galaxy — log-spiral arms, differential rotation, a bulge.
void sVortex(float s, vec4 r, out vec3 pos, out float inten, out float tone) {
    if (s < 0.2) {
        vec2 g = gauss2(r.xy) * 0.13;
        pos = vec3(rot(g, -uT * 0.6), 0.0); inten = 1.3; tone = 1.3; return;
    }
    float arm = floor(r.x * 2.0);
    float rr = 0.14 + pow(r.y, 0.75) * 0.98;
    float th = arm * PI + log(rr) * 2.6 - uSpin * 1.2 - uT * 0.22 / (rr + 0.35);
    th += (r.z - 0.5) * 0.9 * (0.18 + 0.3 * rr);
    rr += (r.w - 0.5) * 0.07;
    vec2 p = circ(th) * rr;
    pos = vec3(p.x, p.y * 0.78, 0.0);
    inten = (1.15 - rr * 0.6) * (0.6 + 0.6 * fract(r.z * 9.1));
    tone = fract(r.w * 7.3) < 0.3 ? 1.1 : 0.2;
}

// 4 JELLYFISH (speaking by default is the soundwave; this is the "waveform" token): a pulsing
// bell with radial canals and a scalloped rim, trailing tentacles and frilled oral arms.
void sJelly(float s, vec4 r, out vec3 pos, out float inten, out float tone) {
    float pulse = 0.5 + 0.5 * sin(uT * (2.0 + uAmp * 4.5));
    float c = 1.0 - 0.16 * pulse * (0.5 + uAmp);
    vec3 top = vec3(0.0, 0.55, 0.0);
    if (s < 0.42) {                                   // the bell
        float th = pow(r.x, 0.6) * PI * 0.5;          // apex -> rim, rim-dense
        float ph = r.y * TAU;
        float canal = fract(ph / TAU * 8.0);
        if (r.z < 0.22) ph = (floor(ph / TAU * 8.0) + 0.5) / 8.0 * TAU;   // radial canals
        vec3 q = vec3(sin(th) * cos(ph) * 0.66 / c, top.y - (1.0 - cos(th)) * 0.62 * c, sin(th) * sin(ph) * 0.66 / c);
        q.y += 0.03 * sin(ph * 16.0) * smoothstep(1.2, 1.57, th);        // scalloped rim
        q = rotX(q, -0.42);
        pos = persp(q);
        inten = 0.55 + 0.7 * smoothstep(1.1, 1.57, th) + (r.z < 0.22 ? 0.5 : 0.0);
        tone = 0.3 + 0.7 * smoothstep(0.8, 1.57, th); return;
    }
    if (s < 0.84) {                                   // tentacles from the rim
        float k = floor(r.x * 18.0);
        float ph = k / 18.0 * TAU;
        float t = pow(r.y, 0.9);
        vec3 base = vec3(cos(ph) * 0.62 / c, top.y - 0.62 * c, sin(ph) * 0.62 / c);
        vec3 q = base + vec3(0.0, -t * 1.25, 0.0);
        q.x += 0.13 * t * sin(t * 5.5 - uT * 2.6 + k) * (0.6 + uAmp);
        q.z += 0.10 * t * cos(t * 4.5 - uT * 2.1 + k * 1.3);
        q.xz *= 1.0 - 0.35 * t;
        q = rotX(q, -0.42);
        pos = persp(q); inten = (1.0 - t) * 0.9; tone = 0.8; return;
    }
    float k = floor(r.x * 4.0);                       // oral arms: frilly, central
    float t = r.y;
    vec3 q = vec3(0.10 * sin(k * 1.57) + 0.07 * sin(t * 9.0 + uT * 1.8 + k), top.y - 0.45 - t * 0.85,
                  0.10 * cos(k * 1.57) + 0.06 * cos(t * 8.0 - uT * 1.5 + k));
    q.x += 0.05 * sin(t * 30.0 + r.z * 6.0);
    q = rotX(q, -0.42);
    pos = persp(q); inten = 1.0 - t * 0.7; tone = 1.1;
}

// 5 HOURGLASS (waiting): glass, frame, sand draining through the neck; flips when it empties.
void sHourglass(float s, vec4 r, out vec3 pos, out float inten, out float tone) {
    float cyc = uT / 11.0;
    float f = fract(cyc);
    float flip = smoothstep(0.93, 1.0, f);            // turn over at the end of each run
    float fill = min(f / 0.93, 1.0);
    vec2 p;
    if (s < 0.30) {                                   // the glass: two bulbs through a neck
        float y = r.x * 2.0 - 1.0;
        float ay = abs(y);
        float w = 0.06 + 0.50 * sqrt(ay) * (1.0 - 0.45 * pow(ay, 5.0));
        p = vec2((r.y < 0.5 ? -w : w), y * 0.92);
        inten = 0.55; tone = 0.0;
    } else if (s < 0.40) {                            // frame: caps and posts
        float g = r.x;
        if (g < 0.5) p = vec2((r.y * 2.0 - 1.0) * 0.72, g < 0.25 ? 0.97 : -0.97);
        else p = vec2(g < 0.75 ? -0.66 : 0.66, (r.y * 2.0 - 1.0) * 0.95);
        inten = 0.8; tone = 0.2;
    } else if (s < 0.70) {                            // sand still above
        float level = mix(0.86, 0.08, fill);
        float y = mix(0.07, level, pow(r.y, 0.8));
        float w = (0.06 + 0.50 * sqrt(y) * (1.0 - 0.45 * pow(y, 5.0))) * 0.9;
        p = vec2((r.x * 2.0 - 1.0) * w, y * 0.92);
        p.y -= 0.02 * (1.0 - abs(p.x) / max(w, 0.01)) * step(0.1, fill);   // the dimple over the neck
        inten = fill < 0.999 ? 0.9 : 0.0; tone = 1.0;
    } else if (s < 0.78) {                            // the falling stream
        float y = mix(0.06, -0.88 + 0.8 * fill, fract(r.y + uT * 1.6));
        p = vec2((r.x - 0.5) * 0.02, y * 0.92);
        inten = fill < 0.999 ? 1.2 : 0.0; tone = 1.2;
    } else {                                          // the pile below: a mound
        float x = (r.x * 2.0 - 1.0);
        float top = -0.9 + 0.8 * fill * (1.0 - 0.55 * x * x);
        float y = mix(-0.9, top, pow(r.y, 0.7));
        float w = (0.06 + 0.50 * sqrt(abs(y)) * (1.0 - 0.45 * pow(abs(y), 5.0))) * 0.9;
        p = vec2(x * w, y * 0.92);
        inten = 0.9; tone = 1.0;
    }
    pos = vec3(rot(p, flip * PI), 0.0);
}

// 6 BURST (recoil): a shock ring and radial debris, flung and fading.
void sBurst(float s, vec4 r, out vec3 pos, out float inten, out float tone) {
    float b = uBurst;
    float a = r.y * TAU;
    float rr = s < 0.55 ? b * 1.45 + (r.x - 0.5) * 0.12 * (1.0 + b) : b * (0.25 + 1.5 * r.x);
    pos = vec3(circ(a) * rr, 0.0);
    inten = pow(1.0 - b, 1.4) * (s < 0.55 ? 1.6 : 0.9) + (1.0 - smoothstep(0.0, 0.15, b)) * 1.5;
    tone = 1.5 * (1.0 - b);
}

// 7 SCAN (web search): a wireframe globe turning under a sweeping scan band; data points light up.
void sScan(float s, vec4 r, out vec3 pos, out float inten, out float tone) {
    vec3 q;
    if (s < 0.52) {                                   // meridians
        float k = floor(r.x * 10.0); float t = r.y * TAU;
        q = rotY(vec3(cos(t), sin(t), 0.0), k / 10.0 * PI);
    } else if (s < 0.80) {                            // parallels
        float k = floor(r.x * 7.0); float lat = (k + 1.0) / 8.0 * PI - PI * 0.5; float t = r.y * TAU;
        q = vec3(cos(lat) * cos(t), sin(lat), cos(lat) * sin(t));
    } else {                                          // data points on the surface
        float z = r.x * 2.0 - 1.0; float t = r.y * TAU; float rxy = sqrt(1.0 - z * z);
        q = vec3(rxy * cos(t), z, rxy * sin(t));
    }
    q = rotX(rotY(q * 0.86, uT * 0.35 + uSpin * 0.3), 0.32);
    vec3 pp = persp(q);
    float band = sin(uT * 0.95) * 0.82;
    float hit = exp(-pow((pp.y - band) / 0.07, 2.0));
    pos = pp;
    float front = 0.35 + 0.65 * smoothstep(-0.9, 0.6, q.z);
    if (s < 0.80) { inten = (0.35 + 1.4 * hit) * front; tone = hit; }
    else { float lit = exp(-abs(pp.y - band) * 9.0); inten = (0.15 + 2.2 * lit) * front; tone = 1.3 * lit; }
}

// 8 TERMINAL (shell): a window, lines typing themselves out, a prompt chevron and a blinking block.
void sTerminal(float s, vec4 r, out vec3 pos, out float inten, out float tone) {
    vec2 p;
    float cyc = mod(uT * (0.55 + uWork * 0.5), 10.0);
    if (s < 0.24) {                                   // window outline + title bar
        float t = r.x * 4.0;
        if (r.y < 0.12) { p = vec2(-0.9 + r.x * 1.8, 0.56); }
        else if (t < 1.0) p = vec2(-0.95 + t * 1.9, 0.72);
        else if (t < 2.0) p = vec2(0.95, 0.72 - (t - 1.0) * 1.42);
        else if (t < 3.0) p = vec2(0.95 - (t - 2.0) * 1.9, -0.70);
        else p = vec2(-0.95, -0.70 + (t - 3.0) * 1.42);
        inten = 0.55; tone = 0.0;
    } else if (s < 0.28) {                            // the three title-bar lights
        float k = floor(r.x * 3.0);
        p = vec2(-0.83 + k * 0.1, 0.64) + gauss2(r.yz) * 0.012;
        inten = 1.1; tone = k * 0.5;
    } else if (s < 0.90) {                            // text: 5 lines of glyph cells, typed in order
        float line = floor(r.x * 5.0);
        float cells = 18.0 + floor(h1(line + floor(cyc / 10.0)) * 8.0);
        float c = floor(r.y * cells);
        float gx = floor(r.z * 3.0), gy = floor(r.w * 4.0);           // a 3x4 dot glyph
        float lit = step(0.42, h1(line * 91.0 + c * 7.0 + gx * 3.1 + gy * 13.7));
        float word = step(0.12, h1(line * 17.0 + floor(c / 5.0)));    // gaps between words
        float typed = clamp(cyc - line * 1.6, 0.0, 1.6) / 1.6 * cells;
        float x0 = line == 0.0 ? -0.62 : -0.80;
        p = vec2(x0 + c * 0.075 + gx * 0.02, 0.36 - line * 0.23 + gy * 0.024 - 0.036);
        inten = (c < typed ? 1.0 : 0.0) * lit * word * (line == 0.0 ? 1.1 : 0.8);
        tone = line == 0.0 ? 1.0 : 0.2;
    } else if (s < 0.95) {                            // the prompt chevron on the first line
        float t = r.x;
        p = t < 0.5 ? mix(vec2(-0.82, 0.39), vec2(-0.72, 0.33), t * 2.0) : mix(vec2(-0.72, 0.33), vec2(-0.82, 0.27), t * 2.0 - 1.0);
        inten = 1.3; tone = 1.2;
    } else {                                          // the block cursor, after the text being typed
        float line = clamp(floor(cyc / 1.6), 0.0, 4.0);
        float cells = 18.0 + floor(h1(line + floor(cyc / 10.0)) * 8.0);
        float typed = clamp(cyc - line * 1.6, 0.0, 1.6) / 1.6 * cells;
        float x0 = line == 0.0 ? -0.62 : -0.80;
        p = vec2(x0 + typed * 0.075 + r.x * 0.05, 0.33 - line * 0.23 + r.y * 0.07);
        inten = step(0.5, fract(uT * 1.8)) * 1.3; tone = 1.3;
    }
    pos = vec3(p, 0.0);
}

// Constellation node k (stable per seed): a ring of eight around a heart.
vec2 node(float k) {
    if (k > 7.5) return vec2(0.05, -0.02);
    float a = k / 8.0 * TAU + uSeed + 0.35 * h1(k * 3.0 + uSeed);
    return circ(a) * (0.55 + 0.35 * h1(k + uSeed * 1.7));
}

// 9 CONSTELLATION (memory): stars joined by lines, pulses of light running along the links.
void sConstellation(float s, vec4 r, out vec3 pos, out float inten, out float tone) {
    vec2 p;
    float wob = uT * 0.07;
    if (s < 0.18) {                                   // stars: a core and a four-point flare
        float k = floor(r.x * 9.0);
        vec2 c = node(k);
        if (r.y < 0.6) { p = c + gauss2(r.zw) * 0.022; inten = 1.6; }
        else { float t = (r.z * 2.0 - 1.0) * 0.12; p = c + (r.w < 0.5 ? vec2(t, 0.0) : vec2(0.0, t)); inten = 1.1 * (1.0 - abs(t) / 0.12); }
        inten *= 0.8 + 0.4 * sin(uT * (1.5 + h1(k) * 2.0) + k);
        tone = 1.2;
    } else if (s < 0.92) {                            // the links: ring edges, chords, spokes
        float e = floor(r.x * 14.0);
        float a, b;
        if (e < 8.0) { a = e; b = mod(e + 1.0, 8.0); }
        else if (e < 11.0) { a = (e - 8.0) * 2.0; b = 8.0; }
        else { a = (e - 11.0) * 3.0 + 1.0; b = mod(a + 3.0, 8.0); }
        float t = r.y;
        p = mix(node(a), node(b), t);
        float pulse = exp(-pow(fract(t - uT * 0.45 - e * 0.31) * 7.0, 2.0));
        inten = 0.32 + 1.3 * pulse; tone = pulse;
    } else {                                          // faint field stars
        p = (vec2(r.x, r.y) * 2.0 - 1.0) * 1.2;
        inten = 0.18 + 0.2 * sin(uT * 2.0 + r.z * 40.0); tone = 0.0;
    }
    pos = vec3(rot(p, wob), 0.0);
}

// 10 RIBBON (file / streaming): a Mobius strip — a ring with a half twist — turning, edges bright,
// light running along it.
void sRibbon(float s, vec4 r, out vec3 pos, out float inten, out float tone) {
    float a = r.x * TAU;
    float v = r.y * 2.0 - 1.0; v = sign(v) * pow(abs(v), 0.35);
    vec3 radial = vec3(cos(a), sin(a), 0.0);
    vec3 across = cos(a * 0.5) * radial + sin(a * 0.5) * vec3(0.0, 0.0, 1.0);
    vec3 q = radial * 0.78 + across * v * 0.30;
    q = rotX(rotZ(q, uT * 0.22), 0.62 + 0.1 * sin(uT * 0.2));
    pos = persp(q);
    float edge = smoothstep(0.75, 1.0, abs(v));
    float flow = 0.5 + 0.5 * sin(a * 3.0 - uT * 2.2);
    inten = (0.3 + 1.0 * edge + 0.5 * flow * edge) * (0.45 + 0.55 * smoothstep(-0.8, 0.8, q.z));
    tone = edge * 0.6 + 0.5 * flow;
}

// 11 BLACK HOLE (download): a dark horizon, a thin photon ring, a tilted accretion disk
// brightened on its approaching side, the far disk lensed over the top, and matter spiralling in.
void sBlackHole(float s, vec4 r, out vec3 pos, out float inten, out float tone) {
    vec2 p;
    if (s < 0.52) {                                   // accretion disk (inclined ~76 degrees)
        float rr = mix(0.42, 1.28, pow(r.x, 2.0));
        float a = r.y * TAU - uT * 0.9 * pow(0.42 / rr, 1.5);
        vec3 q = rotX(vec3(cos(a) * rr, 0.0, sin(a) * rr), 0.2);
        p = q.xy;
        float doppler = 1.0 + 0.7 * sin(a);
        inten = (1.6 - rr * 0.9) * doppler * (q.z < 0.0 && length(p) < 0.36 ? 0.0 : 1.0);
        tone = mix(1.4, 0.0, (rr - 0.42) / 0.86);
    } else if (s < 0.78) {                            // the lensed far side, arched over the top
        float a = r.y * PI;
        float rr = mix(0.40, 0.58, pow(r.x, 1.8));
        p = vec2(cos(a) * rr, sin(a) * rr * 0.92 + 0.02);
        if (r.z < 0.35) p.y = -p.y * 0.55 - 0.02;       // a fainter lower image
        inten = (1.5 - (rr - 0.4) * 4.0) * (r.z < 0.35 ? 0.55 : 1.0); tone = 1.0;
    } else if (s < 0.90) {                            // the photon ring
        float a = r.y * TAU;
        p = circ(a) * (0.355 + 0.008 * (r.x - 0.5));
        inten = 1.5; tone = 1.4;
    } else {                                          // matter spiralling in
        float life = fract(r.x + uT * 0.22);
        float rr = mix(1.25, 0.36, pow(life, 0.8));
        float a = r.y * TAU + life * 7.0;
        vec3 q = rotX(vec3(cos(a) * rr, 0.0, sin(a) * rr), 0.3);
        p = q.xy;
        inten = sin(life * PI) * 0.8; tone = 0.8;
    }
    pos = vec3(p * 0.95, 0.0);
}

// 12 BLOOM: a flower opening and closing — two layers of petals with veins, a stamen crown.
void sBloom(float s, vec4 r, out vec3 pos, out float inten, out float tone) {
    float open = 0.78 + 0.18 * sin(uT * 0.55);
    vec2 p;
    if (s < 0.80) {
        bool inner = s > 0.62;
        float n = inner ? 6.0 : 8.0;
        float k = floor(r.x * n);
        float a0 = k / n * TAU + (inner ? PI / n : 0.0) + uT * 0.05;
        float L = (inner ? 0.62 : 0.98) * open;
        float t = r.y;
        float w = (inner ? 0.21 : 0.30) * pow(sin(PI * pow(t, 0.8)), 0.85);
        float g = r.z;
        float v = g < 0.55 ? (r.w < 0.5 ? -1.0 : 1.0) : (g < 0.7 ? 0.0 : (r.w * 2.0 - 1.0) * 0.8);
        vec2 local = vec2(t * L, v * w);
        p = rot(local, a0);
        inten = g < 0.55 ? 1.0 : (g < 0.7 ? 0.8 : 0.3);
        tone = inner ? 0.9 : 0.3 * t;
    } else if (s < 0.90) {                            // stamen crown
        float a = r.x * TAU;
        float k = floor(r.x * 14.0);
        float t = r.y;
        p = circ((k + 0.5) / 14.0 * TAU) * (0.08 + t * 0.14);
        if (t > 0.85) p += gauss2(r.zw) * 0.012;
        inten = t > 0.85 ? 1.6 : 0.7; tone = 1.3;
    } else {                                          // the heart
        p = gauss2(r.xy) * 0.05; inten = 1.4; tone = 1.5;
    }
    pos = vec3(p, 0.0);
}

// 13 SOUNDWAVE (speaking): a mirrored spectrum of bars riding the voice, an oscilloscope line.
float barLevel(float k) {
    float c = abs(k - 19.5) / 19.5;
    float env = 1.0 - 0.75 * c * c;
    float n = 0.5 + 0.5 * sin(uT * (3.0 + h1(k) * 6.0) + h1(k * 3.1) * 20.0) * sin(uT * 1.9 + k * 0.45);
    return (0.12 + (0.62 + 0.4 * uAmp) * env * n) * (0.85 + 0.15 * uBreath);
}
void sSoundwave(float s, vec4 r, out vec3 pos, out float inten, out float tone) {
    vec2 p;
    if (s < 0.80) {
        float k = floor(r.x * 40.0);
        float H = barLevel(k);
        float y = (r.y * 2.0 - 1.0) * H;
        p = vec2(-1.25 + (k + 0.5) / 40.0 * 2.5 + (r.z - 0.5) * 0.022, y);
        float cap = smoothstep(0.8, 1.0, abs(y) / max(H, 1e-3));
        inten = 0.55 + 0.9 * cap; tone = 0.25 + 0.9 * cap;
    } else if (s < 0.95) {
        float x = r.x * 2.6 - 1.3;
        float a = 0.05 + 0.32 * uAmp;
        float y = a * (sin(x * 7.0 - uT * 6.0) * 0.6 + sin(x * 13.0 + uT * 4.3) * 0.3 + sin(x * 3.0 - uT * 2.0) * 0.4) * (1.0 - x * x / 1.8);
        p = vec2(x, y); inten = 1.2; tone = 1.3;
    } else {
        p = vec2(r.x * 2.6 - 1.3, (r.y - 0.5) * 0.02 - 0.0);
        inten = 0.4; tone = 0.0;
    }
    pos = vec3(p, 0.0);
}

// Lightning bolt path: a jagged descent re-seeded each strike.
vec2 bolt(float t, float seed, vec2 a, vec2 b) {
    vec2 p = mix(a, b, t);
    vec2 n = normalize(vec2(-(b - a).y, (b - a).x));
    float d = 0.0, amp = 0.24, fr = 3.0;
    for (int o = 0; o < 4; o++) { d += amp * vnoise(t * fr + seed * 17.0 + float(o) * 5.3); amp *= 0.5; fr *= 2.3; }
    return p + n * d * sin(PI * t);
}
// 14 LIGHTNING (the "arc" token): a branching bolt that strikes, double-flashes and re-strikes.
void sLightning(float s, vec4 r, out vec3 pos, out float inten, out float tone) {
    float per = 1.5;
    float S = floor(uT / per);
    float f = fract(uT / per);
    float flash = (f < 0.06 ? 1.0 : exp(-(f - 0.06) * 6.0)) + 0.8 * exp(-pow((f - 0.16) * 30.0, 2.0));
    vec2 A = vec2((h1(S) - 0.5) * 0.6, 1.0), B = vec2((h1(S + 7.0) - 0.5) * 0.9, -1.0);
    vec2 p;
    if (s < 0.58) {
        p = bolt(r.x, S, A, B) + (r.y - 0.5) * 0.012;
        inten = (0.25 + 1.5 * flash); tone = 1.4 * flash;
    } else if (s < 0.86) {
        float b = floor(r.y * 3.0);
        float t0 = 0.22 + 0.2 * b + 0.05 * h1(S + b);
        vec2 st = bolt(t0, S, A, B);
        vec2 en = st + rot(vec2(0.0, -0.55), (h1(S * 3.0 + b) - 0.5) * 2.2);
        p = bolt(r.x, S + b * 9.0 + 1.0, st, en);
        inten = (0.2 + 1.0 * flash) * (1.0 - r.x * 0.7); tone = 1.0 * flash;
    } else {
        p = vec2((r.x * 2.0 - 1.0) * 1.1, 0.95 + gauss2(r.yz).y * 0.1);
        inten = 0.12 + 0.7 * flash * r.w; tone = 0.0;
    }
    pos = vec3(p, 0.0);
}

// 15 NUCLEUS: an atom — a packed nucleus, three tilted orbits, electrons trailing light.
vec3 orbit(float k, float a) {
    vec3 q = vec3(cos(a) * 0.92, sin(a) * 0.36, 0.0);
    q = rotZ(q, k * PI / 3.0);
    return rotX(q, 0.35 * sin(uT * 0.3 + k));
}
void sNucleus(float s, vec4 r, out vec3 pos, out float inten, out float tone) {
    vec3 q;
    if (s < 0.25) {
        float n = floor(r.x * 14.0);
        float z = 1.0 - 2.0 * (n + 0.5) / 14.0; float a = n * 2.39996;
        vec3 c = vec3(sqrt(1.0 - z * z) * cos(a), sqrt(1.0 - z * z) * sin(a), z) * 0.11;
        q = rotY(c, uT * 0.5) + vec3(gauss2(r.yz) * 0.03, 0.0);
        inten = 1.25; tone = mod(n, 2.0) < 1.0 ? 1.2 : 0.2;
    } else if (s < 0.62) {
        float k = floor(r.x * 3.0);
        q = orbit(k, r.y * TAU); inten = 0.28; tone = 0.0;
    } else {
        float k = floor(r.x * 3.0);
        float e = uT * (1.4 + 0.35 * k) + k * 2.1;
        float t = pow(r.y, 1.6);
        q = orbit(k, e - t * 1.4) + vec3(gauss2(r.zw) * 0.012 * (1.0 + t * 2.0), 0.0);
        inten = pow(1.0 - t, 2.0) * 2.0 + 0.1; tone = 1.3 * (1.0 - t);
    }
    pos = persp(q);
}

// 16 EYE: almond lids with lashes, a fibred iris that darts and returns, a specular glint; it blinks.
float lidU(float x) { return 0.44 * pow(max(1.0 - x * x / 0.9025, 0.0), 0.8); }
float lidL(float x) { return -0.30 * pow(max(1.0 - x * x / 0.9025, 0.0), 0.9); }
void sEye(float s, vec4 r, out vec3 pos, out float inten, out float tone) {
    float open = uBlink;
    float mid = 0.05;
    vec2 p;
    if (s < 0.20) {
        float x = (r.x * 2.0 - 1.0) * 0.95;
        p = vec2(x, mix(mid, lidU(x), open)) + vec2(0.0, (r.y - 0.5) * 0.02);
        inten = 1.0; tone = 0.3;
    } else if (s < 0.31) {
        float x = (r.x * 2.0 - 1.0) * 0.95;
        p = vec2(x, mix(mid, lidL(x), open)); inten = 0.7; tone = 0.1;
    } else if (s < 0.38) {                            // lashes
        float k = floor(r.x * 22.0);
        float x = ((k + 0.5) / 22.0 * 2.0 - 1.0) * 0.82;
        float t = r.y;
        vec2 b = vec2(x, mix(mid, lidU(x), open));
        p = b + vec2(x * 0.14 * t + 0.02 * t * t, (0.10 + 0.04 * h1(k)) * t * (0.35 + 0.65 * open));
        inten = 0.8 * (1.0 - t * 0.6); tone = 0.2;
    } else if (s < 0.95) {                            // iris, clipped by the lids
        vec2 c = uPupil * 0.26;
        vec2 ip; irisAt(s, r, 0.12 - 0.02 * uAmp, 0.36, ip, inten, tone);
        p = c + ip;
        float up = mix(mid, lidU(p.x), open) - 0.01, lo = mix(mid, lidL(p.x), open) + 0.01;
        inten *= step(p.y, up) * step(lo, p.y);
    } else {                                          // the glint
        vec2 c = uPupil * 0.26 + vec2(-0.08, 0.08);
        p = c + gauss2(r.xy) * 0.018;
        inten = 1.9 * step(0.35, open); tone = 2.0;
    }
    pos = vec3(p, 0.0);
}

// 17 RIPPLES (the "water" token): rings spreading across a pond in perspective; a drop falls.
void sRipples(float s, vec4 r, out vec3 pos, out float inten, out float tone) {
    vec2 p;
    float per = 4.0;
    float f = fract(uT / per);
    if (s < 0.62) {
        float k = floor(r.x * 4.0);
        float age = fract(f + k / 4.0);
        float rr = age * 1.35 - (r.z < 0.4 ? 0.07 : 0.0);
        float a = r.y * TAU;
        p = vec2(cos(a) * rr, sin(a) * rr * 0.36 - 0.1);
        inten = pow(1.0 - age, 1.4) * (r.z < 0.4 ? 0.6 : 1.2) * smoothstep(0.0, 0.05, age); tone = 0.7 * (1.0 - age);
    } else if (s < 0.76) {                            // a second, smaller source
        float age = fract(f * 1.6 + 0.3);
        float a = r.y * TAU;
        p = vec2(0.45 + cos(a) * age * 0.7, -0.18 + sin(a) * age * 0.25);
        inten = pow(1.0 - age, 1.6) * 0.8; tone = 0.4;
    } else if (s < 0.82) {                            // the drop, then its splash crown
        if (f < 0.12) { p = vec2(0.0, mix(1.0, -0.1, f / 0.12)) + gauss2(r.xy) * 0.015; inten = 1.4; }
        else { float t = (f - 0.12) / 0.2; float a = r.x * TAU; p = vec2(cos(a) * 0.12 * t, -0.1 + sin(r.x * PI) * 0.25 * sin(min(t, 1.0) * PI)); inten = max(0.0, 1.0 - t) * 1.2; }
        tone = 1.2;
    } else {                                          // the surface sheen
        p = vec2(r.x * 2.6 - 1.3, (r.y * 2.0 - 1.0) * 0.42 - 0.1);
        inten = 0.12 + 0.25 * max(0.0, sin(uT * 2.0 + r.z * 30.0)); tone = 0.0;
    }
    pos = vec3(p, 0.0);
}

// 18 RADAR: range rings, a crosshair, a sweeping beam with a fading wake, blips that answer it.
void sRadar(float s, vec4 r, out vec3 pos, out float inten, out float tone) {
    float sw = -uT * 1.3;
    vec2 p;
    if (s < 0.22) {
        float k = floor(r.x * 3.0) + 1.0;
        p = circ(r.y * TAU) * k / 3.0; inten = 0.38; tone = 0.0;
    } else if (s < 0.28) {
        float t = r.y * 2.0 - 1.0;
        p = r.x < 0.5 ? vec2(t, 0.0) : vec2(0.0, t); inten = 0.25; tone = 0.0;
    } else if (s < 0.33) {
        float k = floor(r.x * 36.0);
        p = circ(k / 36.0 * TAU) * mix(0.94, 1.0, r.y); inten = 0.5; tone = 0.0;
    } else if (s < 0.43) {                            // the beam
        p = circ(sw) * r.y; inten = 1.6; tone = 1.2;
    } else if (s < 0.78) {                            // the wake behind it
        float off = pow(r.y, 2.0) * 1.6;
        p = circ(sw + off) * sqrt(r.x); inten = exp(-off * 2.5) * 0.9; tone = 0.8 * exp(-off * 2.0);
    } else {                                          // blips
        float k = floor(r.x * 6.0);
        float ba = h1(k + uSeed) * TAU, br = 0.3 + 0.62 * h1(k * 3.3 + uSeed);
        float since = mod(ba - sw, TAU);
        float lit = exp(-since * 0.9);
        p = circ(ba) * br + gauss2(r.yz) * 0.02 * (1.0 + (1.0 - lit) * 1.5);
        inten = 0.12 + 1.9 * lit; tone = 1.3 * lit;
    }
    pos = vec3(p, 0.0);
}

// 19 OCTOPUS: a mantle with eyes, eight tapering arms that curl as it swims; it travels.
void sOctopus(float s, vec4 r, out vec3 pos, out float inten, out float tone) {
    vec2 p;
    float pulse = 0.5 + 0.5 * sin(uArmPh);
    if (s < 0.26) {                                   // the mantle
        float a = r.x * TAU;
        float edge = r.y < 0.6 ? 1.0 : sqrt(r.z);
        p = vec2(cos(a) * 0.30 * (1.0 - 0.06 * pulse), 0.40 + sin(a) * 0.40 * (1.0 + 0.05 * pulse)) * vec2(edge, 1.0) + vec2(0.0, (1.0 - edge) * 0.0);
        p.y = 0.40 + (p.y - 0.40) * edge;
        inten = r.y < 0.6 ? 0.95 : 0.35; tone = 0.4;
    } else if (s < 0.30) {                            // eyes
        float k = r.x < 0.5 ? -1.0 : 1.0;
        p = vec2(k * 0.15, 0.18) + gauss2(r.yz) * 0.025;
        inten = 1.6; tone = 1.3;
    } else {                                          // arms
        float k = floor(r.x * 8.0);
        float spread = (k / 7.0 - 0.5) * 1.9;
        float t = pow(r.y, 0.85);
        float L = 1.05 + 0.1 * h1(k);
        float curl = (0.45 + 0.35 * uArm) * sin(uArmPh - t * 4.0 + k * 1.3) * t * t;
        vec2 dir = rot(vec2(0.0, -1.0), spread * 0.55 + curl);
        vec2 base = vec2(sin(spread * 0.8) * 0.2, 0.06);
        vec2 side = vec2(-dir.y, dir.x);
        p = base + dir * t * L + side * (r.z * 2.0 - 1.0) * 0.06 * (1.0 - t);
        p += side * 0.18 * t * t * sin(uArmPh * 0.7 + k);
        float sucker = step(0.85, fract(t * 14.0)) * step(0.5, r.w);
        inten = (0.9 - t * 0.55) + sucker * 0.8; tone = 0.2 + sucker;
    }
    p = rot(p, uHead - PI * 0.5) + uOff;
    pos = vec3(p * 0.9, 0.0);
}

// 20 SPHERE: a lit ball of points — Fibonacci surface, lambert + rim light, it spins and bounces.
void sSphere(float s, vec4 r, out vec3 pos, out float inten, out float tone) {
    float id = s * N;
    float z = 1.0 - 2.0 * (id + 0.5) / N;
    float rxy = sqrt(max(0.0, 1.0 - z * z));
    float a = id * 2.39996;
    vec3 n = vec3(rxy * cos(a), rxy * sin(a), z);
    n = rotX(rotY(n, uT * 0.55), 0.45);
    float bounce = abs(sin(uT * 1.5));
    float squash = 1.0 - 0.14 * smoothstep(0.18, 0.0, bounce);
    vec3 q = n * 0.7 * vec3(1.0 / squash, squash, 1.0) + vec3(0.0, bounce * 0.28 - 0.14, 0.0);
    pos = persp(q);
    vec3 L = normalize(vec3(-0.5, 0.6, 0.65));
    float lam = max(dot(n, L), 0.0);
    float rim = pow(1.0 - abs(n.z), 3.0);
    inten = (n.z > 0.0 ? 0.10 + 1.6 * pow(lam, 1.3) + 0.9 * rim : 0.06 + 0.25 * rim);
    tone = lam * 0.8 + rim * 0.9;
}

// 21 HELIX: a DNA double helix turning, base-pair rungs between the strands.
void sHelix(float s, vec4 r, out vec3 pos, out float inten, out float tone) {
    vec3 q;
    if (s < 0.55) {
        float t = r.x * 2.0 - 1.0;
        float a = t * TAU * 1.1 + uT * 0.9 + (r.y < 0.5 ? 0.0 : PI);
        q = vec3(cos(a) * 0.5, t * 1.05, sin(a) * 0.5) + vec3(gauss2(r.zw) * 0.012, 0.0);
        inten = 1.0; tone = r.y < 0.5 ? 0.1 : 0.9;
    } else {
        float j = floor(r.x * 20.0);
        float t = (j + 0.5) / 20.0 * 2.0 - 1.0;
        float a = t * TAU * 1.1 + uT * 0.9;
        vec3 A = vec3(cos(a) * 0.5, t * 1.05, sin(a) * 0.5);
        vec3 B = vec3(cos(a + PI) * 0.5, t * 1.05, sin(a + PI) * 0.5);
        q = mix(A, B, r.y);
        inten = 1.0; tone = r.y < 0.5 ? 0.15 : 0.95;
    }
    q = rotX(q, 0.12);
    pos = persp(q);
    inten *= 0.25 + 0.75 * smoothstep(-0.5, 0.5, q.z);
}

// 22 TORUS KNOT (a 2,3 trefoil): a tube tied in a knot, turning, light running along it.
vec3 knot(float a) { float rr = 0.62 + 0.30 * cos(3.0 * a); return vec3(rr * cos(2.0 * a), rr * sin(2.0 * a), 0.34 * sin(3.0 * a)); }
void sKnot(float s, vec4 r, out vec3 pos, out float inten, out float tone) {
    float a = r.x * TAU;
    vec3 c = knot(a);
    vec3 T = normalize(knot(a + 0.003) - c);
    vec3 N1 = normalize(cross(T, vec3(0.0, 0.0, 1.0)));
    vec3 N2 = cross(T, N1);
    float b = r.y * TAU;
    vec3 q = c + 0.045 * (cos(b) * N1 + sin(b) * N2);
    q = rotX(rotY(q, uT * 0.3), 0.35 + 0.15 * sin(uT * 0.2));
    pos = persp(q);
    float flow = 0.5 + 0.5 * sin(a * 6.0 - uT * 2.5);
    inten = (0.4 + 0.9 * flow) * (0.4 + 0.6 * smoothstep(-0.6, 0.6, q.z));
    tone = flow;
}

// 23 AURORA: curtains of light with a bright lower hem and rays fading upward, folding and shimmering.
void sAurora(float s, vec4 r, out vec3 pos, out float inten, out float tone) {
    vec2 p;
    if (s < 0.94) {
        float L = floor(r.x * 2.0);
        float x = ((floor(r.y * 64.0) + 0.5) / 64.0) * 2.7 - 1.35 + (r.w - 0.5) * 0.004;
        float hem = -0.45 + 0.18 * sin(x * 2.1 + uT * 0.35 + L * 2.0) + 0.07 * sin(x * 5.3 - uT * 0.6 + L);
        float v = pow(r.z, 1.25);
        float y = hem + v * (1.15 + 0.3 * sin(x * 3.0 + L * 1.7));
        x += 0.07 * sin(y * 3.5 + uT * 0.7 + L * 2.3) + 0.12 * L - 0.12;
        p = vec2(x, y);
        float rays = 0.45 + 0.55 * pow(0.5 + 0.5 * sin(x * 21.0 - uT * 1.2 + L * 4.0), 2.0);
        inten = (1.6 * pow(1.0 - v, 1.2) + 2.2 * exp(-v * 30.0)) * rays * (0.7 + 0.3 * sin(uT * 0.8 + x * 2.0)) * (1.0 - L * 0.3);
        tone = v * 1.1;
    } else {
        p = vec2(r.x * 2.6 - 1.3, 0.3 + r.y * 0.8);
        inten = 0.25 * (0.5 + 0.5 * sin(uT * 1.7 + r.z * 50.0)); tone = 1.2;
    }
    pos = vec3(p, 0.0);
}

// 24 HARMONOGRAPH: a pendulum pen tracing a slowly evolving figure; old strokes fade.
void sHarmono(float s, vec4 r, out vec3 pos, out float inten, out float tone) {
    float age = pow(r.x, 1.3);
    float T = uT * 0.8 - age * 28.0;
    float d = 0.012;
    float f1 = 2.01 + 0.02 * sin(uT * 0.03), f2 = 3.0, f3 = 2.99;
    vec3 q = vec3(
        0.62 * sin(f1 * T + 0.3) + 0.3 * sin(f3 * T + 1.3),
        0.58 * sin(f2 * T) + 0.28 * sin(f1 * T + 2.1),
        0.4 * sin(T * 1.5 + 0.7)) * exp(-d * age * 28.0);
    q = rotY(q, uT * 0.15);
    pos = persp(q);
    inten = pow(1.0 - r.x, 1.6) * 1.3 + (r.x < 0.004 ? 2.0 : 0.0);
    tone = 1.0 - r.x;
}

// 25 TESSERACT: a hypercube turning through the fourth dimension, projected to the page.
vec4 hv(float i) { return vec4(mod(i, 2.0), mod(floor(i / 2.0), 2.0), mod(floor(i / 4.0), 2.0), mod(floor(i / 8.0), 2.0)) * 2.0 - 1.0; }
vec3 proj4(vec4 v) {
    float a = uT * 0.45, b = uT * 0.3, c = uT * 0.2;
    v.xw = rot(v.xw, a); v.yz = rot(v.yz, b); v.xy = rot(v.xy, c);
    float k = 1.0 / (2.6 - v.w);
    return v.xyz * k;
}
void sTesseract(float s, vec4 r, out vec3 pos, out float inten, out float tone) {
    vec3 q;
    if (s < 0.86) {
        float e = floor(r.x * 32.0);
        float bit = floor(e / 8.0);
        float low = mod(e, 8.0);
        float lowMask = pow(2.0, bit);
        float i = mod(low, lowMask) + floor(low / lowMask) * lowMask * 2.0;   // insert a 0 at `bit`
        vec4 A = hv(i), B = hv(i + lowMask);
        vec4 v = mix(A, B, r.y);
        q = proj4(v);
        inten = 0.7; tone = bit / 3.0;
    } else {
        float i = floor(r.x * 16.0);
        q = proj4(hv(i)) + vec3(gauss2(r.yz) * 0.015, 0.0);
        inten = 1.6; tone = 1.2;
    }
    q *= 1.35;
    pos = persp(q);
    inten *= 0.55 + 0.45 * smoothstep(-0.6, 0.6, q.z);
}

// 26 MANDALA: three counter-rotating rings of petals and beads, breathing.
void sMandala(float s, vec4 r, out vec3 pos, out float inten, out float tone) {
    float br = 1.0 + 0.04 * uBreath;
    vec2 p;
    float ring = floor(r.x * 4.0);
    float a = r.y * TAU;
    if (ring == 0.0) { p = circ(a + uT * 0.2) * (0.26 + 0.07 * cos(6.0 * a)); tone = 1.2; inten = 1.1; }
    else if (ring == 1.0) { float k = 12.0; float rr = 0.5 + 0.12 * abs(cos(k * 0.5 * a)); p = circ(a - uT * 0.12) * rr; tone = 0.6; inten = 0.9; }
    else if (ring == 2.0) { float k = 16.0; float kk = floor(r.y * k); float t = r.z; float aa = (kk + 0.5) / k * TAU + uT * 0.08;
                            vec2 local = vec2(0.62 + t * 0.3, (r.w * 2.0 - 1.0) * 0.07 * sin(PI * t)); p = rot(local, aa);
                            tone = 0.3; inten = 0.8; }
    else { float k = 24.0; float kk = floor(r.y * k); p = circ((kk + 0.5) / k * TAU - uT * 0.05) * 1.0 + gauss2(r.zw) * 0.015; tone = 1.0; inten = 1.3; }
    pos = vec3(p * br, 0.0);
}

// 27 BUTTERFLY: Fay's butterfly curve for the wings, beating in 3D; a body and antennae.
void sButterfly(float s, vec4 r, out vec3 pos, out float inten, out float tone) {
    vec3 q;
    float flap = 0.95 * pow(abs(sin(uT * 3.2)), 1.5);
    if (s < 0.82) {
        float t = r.x * 12.0 * PI;
        float e = exp(cos(t)) - 2.0 * cos(4.0 * t) - pow(sin(t / 12.0), 5.0);
        vec2 w = vec2(sin(t) * e, cos(t) * e) * 0.2;
        if (r.y > 0.7) w *= 0.35 + 0.65 * r.z;   // some fill
        float side = sign(w.x);
        q = vec3(abs(w.x) * cos(flap) * side, w.y - 0.05, -abs(w.x) * sin(flap));
        inten = r.y > 0.7 ? 0.35 : 1.0; tone = 0.25 + 0.75 * smoothstep(0.1, 0.7, length(w));
    } else if (s < 0.94) {
        float t = r.x;
        q = vec3(0.0, 0.25 - t * 0.75, 0.0) + vec3(gauss2(r.yz) * 0.015, 0.0);
        inten = 1.2; tone = 1.3;
    } else {
        float t = r.x; float side = r.y < 0.5 ? -1.0 : 1.0;
        q = vec3(side * (0.02 + t * 0.2), 0.25 + t * 0.3 + 0.05 * sin(t * 4.0), 0.0);
        if (t > 0.9) q.xy += gauss2(r.zw) * 0.012;
        inten = 1.0; tone = 1.1;
    }
    q = rotX(q, 0.35);
    pos = persp(q);
}

void shape(int a, float s, vec4 r, out vec3 pos, out float inten, out float tone) {
    switch (a) {
        case 1: sIris(s, r, pos, inten, tone); break;
        case 2: sFlame(s, r, pos, inten, tone); break;
        case 3: sVortex(s, r, pos, inten, tone); break;
        case 4: sJelly(s, r, pos, inten, tone); break;
        case 5: sHourglass(s, r, pos, inten, tone); break;
        case 6: sBurst(s, r, pos, inten, tone); break;
        case 7: sScan(s, r, pos, inten, tone); break;
        case 8: sTerminal(s, r, pos, inten, tone); break;
        case 9: sConstellation(s, r, pos, inten, tone); break;
        case 10: sRibbon(s, r, pos, inten, tone); break;
        case 11: sBlackHole(s, r, pos, inten, tone); break;
        case 12: sBloom(s, r, pos, inten, tone); break;
        case 13: sSoundwave(s, r, pos, inten, tone); break;
        case 14: sLightning(s, r, pos, inten, tone); break;
        case 15: sNucleus(s, r, pos, inten, tone); break;
        case 16: sEye(s, r, pos, inten, tone); break;
        case 17: sRipples(s, r, pos, inten, tone); break;
        case 18: sRadar(s, r, pos, inten, tone); break;
        case 19: sOctopus(s, r, pos, inten, tone); break;
        case 20: sSphere(s, r, pos, inten, tone); break;
        case 21: sHelix(s, r, pos, inten, tone); break;
        case 22: sKnot(s, r, pos, inten, tone); break;
        case 23: sAurora(s, r, pos, inten, tone); break;
        case 24: sHarmono(s, r, pos, inten, tone); break;
        case 25: sTesseract(s, r, pos, inten, tone); break;
        case 26: sMandala(s, r, pos, inten, tone); break;
        case 27: sButterfly(s, r, pos, inten, tone); break;
        default: sAura(s, r, pos, inten, tone); break;
    }
}

void main() {
    float s = aId / N;
    vec3 pA, pB; float iA, iB, tA, tB;
    shape(uA, s, aR, pA, iA, tA);
    shape(uB, s, aR, pB, iB, tB);
    // Staggered morph with a swirl through the middle of the flight.
    float m = clamp((uMix - aR.w * 0.35) / 0.65, 0.0, 1.0);
    m = m * m * (3.0 - 2.0 * m);
    vec3 p = mix(pA, pB, m);
    float mid = m * (1.0 - m) * 4.0;
    p.xy += mid * 0.35 * vec2(sin(aR.z * 30.0 + uT * 2.0), cos(aR.y * 30.0 - uT * 2.0));
    float inten = mix(iA, iB, m);
    float tone = mix(tA, tB, m);
    // Life: a small, particle-coupled shimmer so nothing is ever a frozen print.
    p.xy += uEnergy * 0.010 * vec2(sin(uT * 1.7 + aR.z * 40.0), cos(uT * 1.3 + aR.w * 40.0));

    vec2 px = uCenter + p.xy * uBodyR;
    gl_Position = vec4(px / uRes * 2.0 - 1.0, 0.0, 1.0);

    float twinkle = 1.0 + uFlicker * 0.28 * sin(uT * (2.0 + 3.0 * aR.y) + aR.z * 60.0);
    float depth = 1.0 + 0.35 * p.z;
    gl_PointSize = uPx * uSize * (0.55 + 1.1 * aK.x) * depth * (1.0 + 0.15 * uWork);
    vec3 col = mix(uBase, uAcc, clamp(tone, 0.0, 1.0));
    col = mix(col, vec3(1.0), clamp(tone - 1.0, 0.0, 1.0) * 0.5);
    col = mix(col, uAcc, step(0.7, aR.x) * 0.3);
    float b = max(inten, 0.0) * aK.y * twinkle * uBright * (0.8 + 0.2 * depth);
    vCol = col * b;
}
"""

    val FRAGMENT_POINT = """#version 300 es
precision mediump float;
in vec3 vCol;
uniform float uCore, uEdge;
out vec4 o;
void main() {
    vec2 d = gl_PointCoord * 2.0 - 1.0;
    float r2 = dot(d, d);
    if (r2 > 1.0) discard;
    float a = exp(-r2 * 3.4 * uEdge) * 0.7 + exp(-r2 * 20.0) * 0.8 * uCore;
    o = vec4(vCol * a, 1.0);
}
"""

    /** Fullscreen triangle; uv from the vertex id. */
    val VERTEX_QUAD = """#version 300 es
out vec2 vUv;
void main() {
    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    vUv = p;
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
"""

    /** Copy with a gain — the trail feedback (previous frame × decay). */
    val FRAGMENT_FADE = """#version 300 es
precision mediump float;
in vec2 vUv;
uniform sampler2D uTex;
uniform float uGain;
out vec4 o;
void main() { o = vec4(texture(uTex, vUv).rgb * uGain, 1.0); }
"""

    /** Soft bright-pass + downsample. */
    val FRAGMENT_BRIGHT = """#version 300 es
precision mediump float;
in vec2 vUv;
uniform sampler2D uTex;
uniform vec2 uTexel;
out vec4 o;
void main() {
    vec3 c = texture(uTex, vUv + uTexel * vec2(-0.5, -0.5)).rgb + texture(uTex, vUv + uTexel * vec2(0.5, -0.5)).rgb
           + texture(uTex, vUv + uTexel * vec2(-0.5, 0.5)).rgb + texture(uTex, vUv + uTexel * vec2(0.5, 0.5)).rgb;
    c *= 0.25;
    float l = max(c.r, max(c.g, c.b));
    o = vec4(c * smoothstep(0.04, 0.45, l), 1.0);
}
"""

    /** 9-tap separable gaussian. */
    val FRAGMENT_BLUR = """#version 300 es
precision mediump float;
in vec2 vUv;
uniform sampler2D uTex;
uniform vec2 uDir;
out vec4 o;
void main() {
    vec3 c = texture(uTex, vUv).rgb * 0.227;
    c += (texture(uTex, vUv + uDir * 1.385).rgb + texture(uTex, vUv - uDir * 1.385).rgb) * 0.316;
    c += (texture(uTex, vUv + uDir * 3.231).rgb + texture(uTex, vUv - uDir * 3.231).rgb) * 0.070;
    o = vec4(c, 1.0);
}
"""

    /** Scene + two bloom levels + the ambient halo, tone-mapped, vignetted. */
    val FRAGMENT_COMPOSITE = """#version 300 es
precision mediump float;
in vec2 vUv;
uniform sampler2D uScene, uBloomA, uBloomB;
uniform float uGlow, uHalo;
uniform vec3 uBase;
uniform vec2 uCenterUv, uHaloR;
out vec4 o;
void main() {
    vec3 c = texture(uScene, vUv).rgb;
    c += texture(uBloomA, vUv).rgb * 1.5 * uGlow + texture(uBloomB, vUv).rgb * 1.3 * uGlow;
    vec2 d = (vUv - uCenterUv) / uHaloR;
    c += uBase * uHalo * 0.07 * exp(-dot(d, d) * 2.2);
    // Filmic shoulder with saturation kept: tone-map luminance, not each channel, so a dense
    // blue stays blue instead of bleaching to grey-white.
    float lum = max(dot(c, vec3(0.2126, 0.7152, 0.0722)), 1e-4);
    float lt = 1.0 - exp(-lum * 1.4);
    c = c * (lt / lum);
    c = mix(c, vec3(lt), smoothstep(0.75, 1.0, lt) * 0.5);   // only the very hottest cores whiten
    vec2 v = vUv - 0.5;
    c *= 1.0 - 0.45 * smoothstep(0.35, 0.85, length(v * vec2(1.0, 0.9)));
    o = vec4(c, 1.0);
}
"""
}
