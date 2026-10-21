#pragma once

// GLES 3 post-processing shaders for the EGL renderer.
// Color presets, Color Boost and the Lanczos2 / SGSR / FSR upscalers are ported
// from Winlator Ludashi (MIT); color adjust, FXAA, Toon, NTSC and RCAS come from
// the previous Java effect composer.

namespace PostFXShaders {

// Full-screen pass between offscreen buffers. Orientation is preserved.
static constexpr const char *pass_vert = R"GLSL(#version 300 es
in vec2 position;
out vec2 vUV;
void main() {
    vUV = position;
    gl_Position = vec4(position * 2.0 - 1.0, 0.0, 1.0);
}
)GLSL";

// Final pass to the window surface. Uses the same transform as window drawing so
// fullscreen, letterboxing and the magnifier behave exactly as without effects.
// The source is an offscreen buffer, so V is flipped.
static constexpr const char *present_vert = R"GLSL(#version 300 es
uniform float xform[6];
uniform vec2 viewSize;
in vec2 position;
out vec2 vUV;
void main() {
    vUV = vec2(position.x, 1.0 - position.y);
    vec2 p = vec2(xform[0] * position.x + xform[2] * position.y + xform[4],
                  xform[1] * position.x + xform[3] * position.y + xform[5]);
    gl_Position = vec4(2.0 * p.x / viewSize.x - 1.0, 1.0 - 2.0 * p.y / viewSize.y, 0.0, 1.0);
}
)GLSL";

static constexpr const char *copy_frag = R"GLSL(#version 300 es
precision mediump float;
uniform sampler2D texSampler;
in vec2 vUV;
out vec4 outColor;
void main() {
    outColor = vec4(texture(texSampler, vUV).rgb, 1.0);
}
)GLSL";

static constexpr const char *effects_frag = R"GLSL(#version 300 es
precision highp float;

uniform sampler2D texSampler;
uniform int effectId;
uniform float strength;
uniform vec2 resolution;
uniform float brightness;
uniform float contrast;
uniform float gamma;
uniform float rcasStops;
uniform float rcasSaturation;
uniform int frameCount;

in vec2 vUV;
out vec4 outColor;

float luma(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

vec2 texelSize() {
    return 1.0 / max(resolution, vec2(1.0));
}

vec3 adjustSaturation(vec3 c, float amount) {
    float y = luma(c);
    return clamp(mix(vec3(y), c, amount), 0.0, 1.0);
}

vec3 adjustContrast(vec3 c, float amount) {
    return clamp((c - 0.5) * amount + 0.5, 0.0, 1.0);
}

vec3 acesTonemap(vec3 c) {
    c = max(c, vec3(0.0));
    const float a = 2.51;
    const float b = 0.03;
    const float d = 0.59;
    const float e = 0.14;
    const float f = 2.43;
    return clamp((c * (a * c + b)) / (c * (f * c + d) + e), 0.0, 1.0);
}

vec3 blurCross(vec2 uv, vec2 texel) {
    return (texture(texSampler, uv + vec2( 0.0,    -texel.y)).rgb
          + texture(texSampler, uv + vec2( 0.0,     texel.y)).rgb
          + texture(texSampler, uv + vec2(-texel.x,  0.0   )).rgb
          + texture(texSampler, uv + vec2( texel.x,  0.0   )).rgb) * 0.25;
}

vec3 adaptiveSharpen(vec2 uv, float amount) {
    vec2 texel = texelSize();
    vec3 c = texture(texSampler, uv).rgb;
    vec3 n = texture(texSampler, uv + vec2(0.0, -texel.y)).rgb;
    vec3 s = texture(texSampler, uv + vec2(0.0,  texel.y)).rgb;
    vec3 w = texture(texSampler, uv + vec2(-texel.x, 0.0)).rgb;
    vec3 e = texture(texSampler, uv + vec2( texel.x, 0.0)).rgb;
    vec3 localMin = min(c, min(min(n, s), min(w, e)));
    vec3 localMax = max(c, max(max(n, s), max(w, e)));
    vec3 range = localMax - localMin;
    vec3 blur = (n + s + w + e) * 0.25;
    float edge = clamp(luma(range) * 2.4, 0.0, 1.0);
    float adaptive = mix(1.0, 0.38, edge);
    return clamp(c + (c - blur) * amount * adaptive, 0.0, 1.0);
}

vec3 clarity(vec2 uv, float amount) {
    vec2 texel = texelSize();
    vec3 c = texture(texSampler, uv).rgb;
    vec3 blur = blurCross(uv, texel * 1.5);
    float y = luma(c);
    float midtoneMask = 1.0 - abs(y * 2.0 - 1.0);
    float gain = amount * mix(0.45, 1.0, midtoneMask);
    return clamp(c + (c - blur) * gain, 0.0, 1.0);
}

float cubicWeight(float x) {
    x = abs(x);
    if (x <= 1.0) return 1.5 * x * x * x - 2.5 * x * x + 1.0;
    if (x < 2.0) return -0.5 * x * x * x + 2.5 * x * x - 4.0 * x + 2.0;
    return 0.0;
}

vec3 sampleCatmullRom(vec2 uv) {
    vec2 size = max(resolution, vec2(1.0));
    vec2 p = uv * size - 0.5;
    vec2 base = floor(p);
    vec2 f = p - base;
    vec3 sum = vec3(0.0);
    float weightSum = 0.0;
    for (int y = -1; y <= 2; y++) {
        for (int x = -1; x <= 2; x++) {
            float w = cubicWeight(float(x) - f.x) * cubicWeight(float(y) - f.y);
            vec2 suv = (base + vec2(float(x), float(y)) + 0.5) / size;
            sum += texture(texSampler, clamp(suv, vec2(0.0), vec2(1.0))).rgb * w;
            weightSum += w;
        }
    }
    return clamp(sum / max(weightSum, 1e-5), 0.0, 1.0);
}

vec3 applyDLS(vec2 uv, float sharp) {
    vec2 texel  = texelSize();
    float SAT   = 1.0 + sharp * 0.20;
    float CON   = 1.0 + sharp * 0.12;
    float SHARP = sharp * 1.2;
    vec3 orig = texture(texSampler, uv).rgb;
    vec3 c    = clamp((orig - 0.5) * CON + 0.5, 0.0, 1.0);
    float gray = dot(c, vec3(0.299, 0.587, 0.114));
    c = mix(vec3(gray), c, SAT);
    vec3 blur = blurCross(uv, texel);
    return clamp(c + (orig - blur) * SHARP, 0.0, 1.0);
}

vec3 applyCRT(vec2 uv) {
    const float CA = 1.0025;
    vec4 fc = texture(texSampler, uv);
    fc.r = texture(texSampler, (uv - 0.5) * CA + 0.5).r;
    fc.b = texture(texSampler, (uv - 0.5) / CA + 0.5).b;
    float sx = abs(sin(uv.x * 1024.0) * 0.5 * 0.125);
    float sy = abs(sin(uv.y * 1024.0) * 0.5 * 0.375);
    return mix(fc.rgb, vec3(0.0), sx + sy);
}

vec3 applyHDR(vec2 uv) {
    vec2 texel = texelSize();
    vec3 c     = texture(texSampler, uv).rgb;
    const float r1 = 0.793, r2 = 0.870;
    vec3 b1 = vec3(0.0), b2 = vec3(0.0);
    vec2 offs[8] = vec2[](
        vec2( 1.5, -1.5), vec2(-1.5, -1.5), vec2( 1.5,  1.5), vec2(-1.5,  1.5),
        vec2( 0.0, -2.5), vec2( 0.0,  2.5), vec2(-2.5,  0.0), vec2( 2.5,  0.0)
    );
    for (int i = 0; i < 8; i++) {
        b1 += texture(texSampler, uv + offs[i] * r1 * texel).rgb;
        b2 += texture(texSampler, uv + offs[i] * r2 * texel).rgb;
    }
    b1 *= 0.005; b2 *= 0.010;
    vec3 hdr = (c + (b2 - b1)) * (r2 - r1);
    return clamp(pow(abs(hdr + c), vec3(1.30)) + hdr, 0.0, 1.0);
}

vec3 applyNatural(vec2 uv) {
    mat3 toYIQ = mat3( 0.299,  0.596,  0.212,
                       0.587, -0.275, -0.523,
                       0.114, -0.321,  0.311);
    mat3 toRGB = mat3( 1.0,         1.0,         1.0,
                       0.95568806, -0.27158179, -1.10817732,
                       0.61985809, -0.64687381,  1.70506455);
    vec3 c = texture(texSampler, uv).rgb;
    vec3 t = c * toYIQ;
    t = vec3(pow(max(t.r, 0.0), 1.12), t.g * 1.2, t.b * 1.2);
    return clamp(t * toRGB, 0.0, 1.0);
}

vec3 applyVibrance(vec2 uv, float strength) {
    vec3 c = texture(texSampler, uv).rgb;
    float hi = max(c.r, max(c.g, c.b));
    float lo = min(c.r, min(c.g, c.b));
    float saturation = hi - lo;
    float amount = mix(0.08, 0.42, clamp(strength, 0.0, 1.0));
    float gain = 1.0 + amount * (1.0 - saturation);
    return adjustSaturation(c, gain);
}

vec3 applyCurves(vec2 uv, float strength) {
    vec3 c = texture(texSampler, uv).rgb;
    vec3 sCurve = c * c * (3.0 - 2.0 * c);
    float amount = mix(0.18, 0.78, clamp(strength, 0.0, 1.0));
    return clamp(mix(c, sCurve, amount), 0.0, 1.0);
}

vec3 applyCASLite(vec2 uv, float strength) {
    return adaptiveSharpen(uv, mix(0.22, 0.90, clamp(strength, 0.0, 1.0)));
}

vec3 applyTechnicolor(vec2 uv, float strength) {
    vec3 c = texture(texSampler, uv).rgb;
    vec3 warm = vec3(
        c.r * 1.10 + c.g * 0.04 - c.b * 0.03,
        c.g * 1.04 + c.r * 0.02,
        c.b * 0.92 + c.g * 0.05
    );
    warm = clamp((warm - 0.5) * 1.06 + 0.5, 0.0, 1.0);
    float amount = mix(0.20, 0.72, clamp(strength, 0.0, 1.0));
    return mix(c, warm, amount);
}

vec3 applyLevels(vec2 uv, float strength) {
    vec3 c = texture(texSampler, uv).rgb;
    float s = clamp(strength, 0.0, 1.0);
    float blackPoint = mix(0.012, 0.055, s);
    float whitePoint = mix(0.992, 0.955, s);
    vec3 leveled = clamp((c - blackPoint) / max(whitePoint - blackPoint, 1e-3), 0.0, 1.0);
    float g = mix(1.0, 0.94, s);
    return pow(leveled, vec3(g));
}

vec3 applyGameClarity(vec2 uv, float strength) {
    float s = clamp(strength, 0.0, 1.0);
    vec3 base = texture(texSampler, uv).rgb;
    vec3 local = clarity(uv, mix(0.45, 1.15, s));
    vec3 sharp = adaptiveSharpen(uv, mix(0.30, 0.95, s));
    vec3 c = mix(base, local, mix(0.58, 0.86, s));
    c = mix(c, sharp, mix(0.38, 0.68, s));
    c = adjustContrast(c, mix(1.035, 1.105, s));
    c = adjustSaturation(c, mix(1.05, 1.18, s));
    return c;
}

vec3 applyCinematicProfile(vec2 uv, float strength) {
    float s = clamp(strength, 0.0, 1.0);
    vec3 base = texture(texSampler, uv).rgb;
    vec3 film = acesTonemap(base * mix(1.03, 1.22, s));
    vec3 c = mix(base, film, mix(0.40, 0.78, s));
    float y = luma(c);
    float shadows = 1.0 - smoothstep(0.12, 0.55, y);
    float highlights = smoothstep(0.45, 0.90, y);
    c += vec3(-0.018, 0.007, 0.025) * shadows * s;
    c += vec3(0.028, 0.010, -0.014) * highlights * s;
    c = adjustSaturation(c, mix(0.98, 1.10, s));
    c = adjustContrast(c, mix(1.02, 1.09, s));
    return clamp(c, 0.0, 1.0);
}

vec3 applyVividProfile(vec2 uv, float strength) {
    float s = clamp(strength, 0.0, 1.0);
    vec3 c = texture(texSampler, uv).rgb;
    float blackPoint = mix(0.008, 0.030, s);
    float whitePoint = mix(0.995, 0.970, s);
    c = clamp((c - blackPoint) / max(whitePoint - blackPoint, 1e-3), 0.0, 1.0);
    c = adjustContrast(c, mix(1.05, 1.16, s));
    c = adjustSaturation(c, mix(1.13, 1.34, s));
    c = pow(c, vec3(mix(0.98, 0.91, s)));
    return clamp(c, 0.0, 1.0);
}

vec3 applyCompetitiveProfile(vec2 uv, float strength) {
    float s = clamp(strength, 0.0, 1.0);
    vec3 base = texture(texSampler, uv).rgb;
    vec3 lifted = pow(max(base, vec3(0.0)), vec3(mix(0.94, 0.82, s)));
    lifted = mix(lifted, acesTonemap(lifted * 1.08), 0.25 + 0.20 * s);
    vec3 sharp = adaptiveSharpen(uv, mix(0.38, 1.05, s));
    vec3 c = mix(lifted, sharp, mix(0.42, 0.70, s));
    c = adjustSaturation(c, mix(1.03, 1.13, s));
    c = adjustContrast(c, mix(1.01, 1.06, s));
    return clamp(c, 0.0, 1.0);
}

vec3 applyAdaptiveSharpenProfile(vec2 uv, float strength) {
    float s = clamp(strength, 0.0, 1.0);
    return adaptiveSharpen(uv, mix(0.42, 1.40, s));
}

vec3 applyFilmicProfile(vec2 uv, float strength) {
    float s = clamp(strength, 0.0, 1.0);
    vec3 base = texture(texSampler, uv).rgb;
    vec3 film = acesTonemap(base * mix(1.02, 1.28, s));
    film = adjustContrast(film, mix(1.01, 1.08, s));
    film = adjustSaturation(film, mix(1.00, 1.08, s));
    return clamp(mix(base, film, mix(0.42, 0.86, s)), 0.0, 1.0);
}

vec3 applyArcadeProfile(vec2 uv, float strength) {
    float s = clamp(strength, 0.0, 1.0);
    vec3 c = texture(texSampler, uv).rgb;
    c = pow(max(c, vec3(0.0)), vec3(mix(0.97, 0.88, s)));
    c = adjustContrast(c, mix(1.06, 1.18, s));
    c = adjustSaturation(c, mix(1.18, 1.42, s));
    c.r *= mix(1.01, 1.055, s);
    c.b *= mix(1.00, 0.965, s);
    vec3 sharp = adaptiveSharpen(uv, mix(0.22, 0.65, s));
    c = mix(c, sharp, mix(0.18, 0.42, s));
    return clamp(c, 0.0, 1.0);
}

vec3 applyRetroCRTProfile(vec2 uv, float strength) {
    float s = clamp(strength, 0.0, 1.0);
    vec2 texel = texelSize();
    float ca = mix(0.35, 1.55, s);
    vec3 c;
    c.r = texture(texSampler, uv + vec2(texel.x * ca, 0.0)).r;
    c.g = texture(texSampler, uv).g;
    c.b = texture(texSampler, uv - vec2(texel.x * ca, 0.0)).b;
    float scan = sin(uv.y * max(resolution.y, 1.0) * 3.14159265);
    float scanStrength = mix(0.035, 0.16, s);
    c *= 1.0 - scanStrength * (0.5 + 0.5 * scan);
    float mask = 0.965 + 0.035 * sin(uv.x * max(resolution.x, 1.0) * 3.14159265 * 0.6667);
    c *= mix(1.0, mask, 0.35 + 0.45 * s);
    vec2 p = uv * 2.0 - 1.0;
    float vignette = clamp(1.0 - dot(p, p) * mix(0.035, 0.12, s), 0.72, 1.0);
    c *= vignette;
    c = adjustSaturation(c, mix(1.02, 1.12, s));
    return clamp(c, 0.0, 1.0);
}

vec3 applyUpscaleSharp(vec2 uv, float strength) {
    float s = clamp(strength, 0.0, 1.0);
    vec2 texel = texelSize();
    vec3 c = sampleCatmullRom(uv);
    vec3 blur = blurCross(uv, texel);
    return clamp(c + (c - blur) * mix(0.12, 0.55, s), 0.0, 1.0);
}

vec3 applyPixelClean(vec2 uv, float strength) {
    vec2 size = max(resolution, vec2(1.0));
    vec2 snapped = (floor(uv * size) + 0.5) / size;
    vec3 c = texture(texSampler, clamp(snapped, vec2(0.0), vec2(1.0))).rgb;
    float s = clamp(strength, 0.0, 1.0);
    c = adjustContrast(c, mix(1.0, 1.08, s));
    return adjustSaturation(c, mix(1.0, 1.08, s));
}

vec3 applyAnimeEdge(vec2 uv, float strength) {
    float s = clamp(strength, 0.0, 1.0);
    vec2 texel = texelSize();
    vec3 c = texture(texSampler, uv).rgb;
    vec3 n = texture(texSampler, uv + vec2(0.0, -texel.y)).rgb;
    vec3 ss = texture(texSampler, uv + vec2(0.0, texel.y)).rgb;
    vec3 w = texture(texSampler, uv + vec2(-texel.x, 0.0)).rgb;
    vec3 e = texture(texSampler, uv + vec2(texel.x, 0.0)).rgb;
    float edge = clamp(abs(luma(n) - luma(ss)) + abs(luma(w) - luma(e)), 0.0, 1.0);
    vec3 sharp = adaptiveSharpen(uv, mix(0.20, 0.72, s));
    c = mix(c, sharp, 0.45 + 0.35 * s);
    c *= 1.0 - edge * mix(0.04, 0.18, s);
    c = adjustSaturation(c, mix(1.02, 1.16, s));
    return clamp(c, 0.0, 1.0);
}

// Color math runs in mediump (fp16 on Mali halves register use); sampling coordinates stay highp.
mediump float lumaMP(mediump vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

mediump vec3 colorBoostSample(highp vec2 uv) {
    return texture(texSampler, uv).rgb;
}

vec3 applyColorBoost(vec2 uv, float strength) {
    mediump float s = clamp(strength, 0.0, 1.0);
    vec2 texel = texelSize();

    mediump vec3 c  = colorBoostSample(uv);
    mediump vec3 n  = colorBoostSample(uv + vec2(0.0, -texel.y));
    mediump vec3 so = colorBoostSample(uv + vec2(0.0,  texel.y));
    mediump vec3 w  = colorBoostSample(uv + vec2(-texel.x, 0.0));
    mediump vec3 e  = colorBoostSample(uv + vec2( texel.x, 0.0));
    mediump vec3 nw = colorBoostSample(uv + vec2(-texel.x, -texel.y));
    mediump vec3 ne = colorBoostSample(uv + vec2( texel.x, -texel.y));
    mediump vec3 sw = colorBoostSample(uv + vec2(-texel.x,  texel.y));
    mediump vec3 se = colorBoostSample(uv + vec2( texel.x,  texel.y));

    mediump vec3 crossAvg = (n + so + w + e) * 0.25;
    mediump vec3 diagAvg  = (nw + ne + sw + se) * 0.25;
    mediump vec3 localBase = c * 0.36 + crossAvg * 0.44 + diagAvg * 0.20;

    mediump float yc = lumaMP(c);
    mediump float yb = lumaMP(localBase);
    mediump float yn = lumaMP(n), ys = lumaMP(so), yw = lumaMP(w), ye = lumaMP(e);
    mediump float ynw = lumaMP(nw), yne = lumaMP(ne), ysw = lumaMP(sw), yse = lumaMP(se);
    mediump float minY = min(yc, min(min(yn, ys), min(min(yw, ye), min(min(ynw, yne), min(ysw, yse)))));
    mediump float maxY = max(yc, max(max(yn, ys), max(max(yw, ye), max(max(ynw, yne), max(ysw, yse)))));
    mediump float localRange = maxY - minY;
    mediump float structure = smoothstep(0.006, 0.09, localRange);
    mediump float midtone = smoothstep(0.04, 0.20, yc) * (1.0 - smoothstep(0.80, 1.00, yc));

    mediump vec3 result = c;

    mediump float localDetail = clamp(yc - yb, -0.28, 0.28);
    result += vec3(localDetail * structure * mix(0.55, 1.35, s));

    mediump float darkDelta = max(yb - yc, 0.0);
    mediump float contact = smoothstep(0.010, 0.07, darkDelta)
                          * smoothstep(0.010, 0.12, localRange);
    result *= 1.0 - contact * mix(0.10, 0.26, s);

    mediump float hi = max(c.r, max(c.g, c.b));
    mediump float lo = min(c.r, min(c.g, c.b));
    mediump float chroma = hi - lo;
    mediump float relativeChroma = chroma / max(yc, 0.08);
    mediump float neutralHighlight = 1.0 - smoothstep(0.32, 0.80, relativeChroma);
    mediump float brightDelta = max(yc - yb, 0.0);
    mediump float specular = smoothstep(0.010, 0.08, brightDelta)
                           * smoothstep(0.22, 0.80, yc)
                           * neutralHighlight * structure;
    mediump vec3 specColor = max(c - localBase, vec3(0.0));
    result += specColor * specular * mix(0.9, 2.0, s);
    result += vec3(specular * mix(0.012, 0.045, s));

    mediump float warmRG = smoothstep(-0.03, 0.10, c.r - c.g);
    mediump float warmRB = smoothstep(0.010, 0.16, c.r - c.b);
    mediump float warmMask = warmRG * warmRB * midtone * (1.0 - specular);
    mediump vec3 warmSoft = localBase * vec3(1.045, 1.010, 0.965);
    mediump vec3 scattered = warmSoft + (result - localBase) * 0.80;
    result = mix(result, scattered, warmMask * mix(0.06, 0.18, s));

    const vec2 ringOffs[8] = vec2[](
        vec2( 1.0, -1.0), vec2(-1.0, -1.0), vec2( 1.0,  1.0), vec2(-1.0,  1.0),
        vec2( 0.0, -1.4), vec2( 0.0,  1.4), vec2(-1.4,  0.0), vec2( 1.4,  0.0)
    );
    vec2 ringStep = mix(3.0, 7.0, float(s)) * texel;
    mediump vec3 bleed = vec3(0.0);
    for (int i = 0; i < 8; i++) {
        bleed += colorBoostSample(uv + ringOffs[i] * ringStep);
    }
    bleed *= 0.125;
    mediump float bleedLuma = lumaMP(bleed);
    mediump vec3 bleedChroma = bleed - vec3(bleedLuma);
    mediump float sourceStrength = smoothstep(0.30, 0.80, bleedLuma);
    mediump float receiveMask = 1.0 - smoothstep(0.60, 0.95, yc);
    mediump float bleedAmount = mix(0.10, 0.30, s) * sourceStrength * receiveMask;
    result += bleedChroma * bleedAmount * 3.0;
    result += vec3(bleedLuma * bleedAmount * 0.5);

    mediump vec3 detailRGB = c - localBase;
    mediump float fineDetailMask = structure * (1.0 - smoothstep(0.45, 0.85, localRange));
    result += detailRGB * fineDetailMask * mix(0.20, 0.55, s);

    mediump float resultY = lumaMP(result);
    mediump float resultHi = max(result.r, max(result.g, result.b));
    mediump float resultLo = min(result.r, min(result.g, result.b));
    mediump float resultChroma = resultHi - resultLo;
    mediump float vibrance = (1.0 - smoothstep(0.22, 0.65, resultChroma))
                           * midtone * mix(0.06, 0.16, s);
    result = mix(vec3(resultY), result, 1.0 + vibrance);

    // ACES tonemap, contrast and saturation, inlined in mediump.
    mediump vec3 t = max(result, vec3(0.0)) * mix(1.03, 1.14, s);
    mediump vec3 film = clamp((t * (2.51 * t + 0.03)) / (t * (2.43 * t + 0.59) + 0.14), 0.0, 1.0);
    result = mix(result, film, mix(0.10, 0.30, s));
    result = clamp((result - 0.5) * mix(1.02, 1.10, s) + 0.5, 0.0, 1.0);
    result = clamp(mix(vec3(lumaMP(result)), result, mix(1.05, 1.20, s)), 0.0, 1.0);

    mediump vec3 localMin = min(c, min(min(n, so), min(min(w, e), min(min(nw, ne), min(sw, se)))));
    mediump vec3 localMax = max(c, max(max(n, so), max(max(w, e), max(max(nw, ne), max(sw, se)))));
    mediump float allowance = mix(0.06, 0.16, s);
    result = clamp(result, localMin - vec3(allowance), localMax + vec3(allowance));

    return clamp(result, 0.0, 1.0);
}

vec3 applyColorAdjust(vec2 uv) {
    vec3 color = texture(texSampler, uv).rgb;
    color = clamp(color + brightness, 0.0, 1.0);
    color = (color - 0.5) * clamp(contrast + 1.0, 0.5, 2.0) + 0.5;
    return pow(max(color, vec3(0.0)), vec3(1.0 / gamma));
}

vec3 applyFXAA(vec2 uv) {
    const float FXAA_REDUCE_MIN = 1.0 / 128.0;
    const float FXAA_REDUCE_MUL = 1.0 / 8.0;
    const float FXAA_SPAN_MAX = 8.0;
    vec2 res = texelSize();
    vec3 rgbNW = texture(texSampler, uv + vec2(-1.0, -1.0) * res).rgb;
    vec3 rgbNE = texture(texSampler, uv + vec2( 1.0, -1.0) * res).rgb;
    vec3 rgbSW = texture(texSampler, uv + vec2(-1.0,  1.0) * res).rgb;
    vec3 rgbSE = texture(texSampler, uv + vec2( 1.0,  1.0) * res).rgb;
    vec3 rgbM  = texture(texSampler, uv).rgb;
    vec3 l = vec3(0.299, 0.587, 0.114);
    float lumaNW = dot(rgbNW, l);
    float lumaNE = dot(rgbNE, l);
    float lumaSW = dot(rgbSW, l);
    float lumaSE = dot(rgbSE, l);
    float lumaM  = dot(rgbM,  l);
    float lumaMin = min(lumaM, min(min(lumaNW, lumaNE), min(lumaSW, lumaSE)));
    float lumaMax = max(lumaM, max(max(lumaNW, lumaNE), max(lumaSW, lumaSE)));
    vec2 dir;
    dir.x = -((lumaNW + lumaNE) - (lumaSW + lumaSE));
    dir.y =  ((lumaNW + lumaSW) - (lumaNE + lumaSE));
    float dirReduce = max((lumaNW + lumaNE + lumaSW + lumaSE) * (0.25 * FXAA_REDUCE_MUL), FXAA_REDUCE_MIN);
    float rcpDirMin = 1.0 / (min(abs(dir.x), abs(dir.y)) + dirReduce);
    dir = min(vec2(FXAA_SPAN_MAX), max(vec2(-FXAA_SPAN_MAX), dir * rcpDirMin)) * res;
    vec3 rgbA = 0.5 * (
        texture(texSampler, uv + dir * (1.0 / 3.0 - 0.5)).rgb +
        texture(texSampler, uv + dir * (2.0 / 3.0 - 0.5)).rgb);
    vec3 rgbB = rgbA * 0.5 + 0.25 * (
        texture(texSampler, uv + dir * -0.5).rgb +
        texture(texSampler, uv + dir * 0.5).rgb);
    float lumaB = dot(rgbB, l);
    return (lumaB < lumaMin || lumaB > lumaMax) ? rgbA : rgbB;
}

vec3 applyToon(vec2 uv) {
    vec2 offset = texelSize();
    vec3 colorCenter = texture(texSampler, uv).rgb;
    vec3 colorLeft = texture(texSampler, uv - vec2(offset.x, 0.0)).rgb;
    vec3 colorRight = texture(texSampler, uv + vec2(offset.x, 0.0)).rgb;
    vec3 colorUp = texture(texSampler, uv - vec2(0.0, offset.y)).rgb;
    vec3 colorDown = texture(texSampler, uv + vec2(0.0, offset.y)).rgb;
    float diffHorizontal = length(colorRight - colorLeft);
    float diffVertical = length(colorUp - colorDown);
    float edgeFactor = step(0.2, diffHorizontal + diffVertical);
    return mix(colorCenter, vec3(0.0), edgeFactor);
}

vec2 ntscWarp(vec2 uv) {
    uv = uv * 2.0 - 1.0;
    float r = sqrt(uv.x * uv.x + uv.y * uv.y);
    uv += uv * (r * r) * 0.01;
    return uv * 0.5 + 0.5;
}

vec3 applyNTSC(vec2 uv) {
    const float CHROMA_OFFSET = 0.005;
    const float BLUR_RADIUS = 0.002;
    vec2 warped = ntscWarp(uv);
    vec3 ntscColor;
    ntscColor.r = texture(texSampler, warped + vec2(CHROMA_OFFSET, 0.0)).r;
    ntscColor.g = texture(texSampler, warped + vec2(0.0, BLUR_RADIUS)).g;
    ntscColor.b = texture(texSampler, warped - vec2(CHROMA_OFFSET, 0.0)).b;
    vec3 scanlineColor = texture(texSampler, warped).rgb;
    float scanline = abs(sin(warped.y * resolution.y * 2.0)) * 0.35;
    scanlineColor *= 1.0 - scanline * 0.5;
    return mix(ntscColor, scanlineColor, 0.7);
}

// AMD FidelityFX RCAS, optional saturation boost (the old "DLS" sharpen mode).
vec3 applyRCAS(vec2 uv) {
    vec2 px = texelSize();
    vec3 b = texture(texSampler, uv + vec2( 0.0, -px.y)).rgb;
    vec3 d = texture(texSampler, uv + vec2(-px.x,  0.0)).rgb;
    vec3 e = texture(texSampler, uv).rgb;
    vec3 f = texture(texSampler, uv + vec2( px.x,  0.0)).rgb;
    vec3 h = texture(texSampler, uv + vec2( 0.0,  px.y)).rgb;
    float bL = b.b * 0.5 + (b.r * 0.5 + b.g);
    float dL = d.b * 0.5 + (d.r * 0.5 + d.g);
    float eL = e.b * 0.5 + (e.r * 0.5 + e.g);
    float fL = f.b * 0.5 + (f.r * 0.5 + f.g);
    float hL = h.b * 0.5 + (h.r * 0.5 + h.g);
    float nz = 0.25 * bL + 0.25 * dL + 0.25 * fL + 0.25 * hL - eL;
    float mxL = max(max(max(bL, dL), max(fL, hL)), eL);
    float mnL = min(min(min(bL, dL), min(fL, hL)), eL);
    float rangeL = max(mxL - mnL, 0.0001);
    nz = clamp(abs(nz) / rangeL, 0.0, 1.0);
    nz = -0.5 * nz + 1.0;
    vec3 mn4 = min(min(b, d), min(f, h));
    vec3 mx4 = max(max(b, d), max(f, h));
    vec3 hitMin = mn4 / (4.0 * max(mx4, vec3(0.0001)));
    vec3 hitMax = (vec3(1.0) - mx4) / (4.0 * max(vec3(1.0) - mn4, vec3(0.0001)));
    vec3 lobe = -min(hitMin, hitMax);
    lobe = max(lobe, vec3(-0.1875));
    float sharp = exp2(-rcasStops);
    vec3 w = lobe * (sharp * nz);
    vec3 rcpW = vec3(1.0) / (vec3(1.0) + 4.0 * w);
    vec3 outC = clamp(((b + d + f + h) * w + e) * rcpW, 0.0, 1.0);
    float l = dot(outC, vec3(0.2126, 0.7152, 0.0722));
    return clamp(mix(vec3(l), outC, rcasSaturation), 0.0, 1.0);
}

void main() {
    vec2 uv = vUV;
    vec3 rgb;
    if      (effectId == 1)  rgb = applyDLS                   (uv, strength);
    else if (effectId == 2)  rgb = applyCRT                   (uv);
    else if (effectId == 3)  rgb = applyHDR                   (uv);
    else if (effectId == 4)  rgb = applyNatural               (uv);
    else if (effectId == 5)  rgb = applyVibrance              (uv, strength);
    else if (effectId == 6)  rgb = applyCurves                (uv, strength);
    else if (effectId == 7)  rgb = applyCASLite               (uv, strength);
    else if (effectId == 8)  rgb = applyTechnicolor           (uv, strength);
    else if (effectId == 9)  rgb = applyLevels                (uv, strength);
    else if (effectId == 10) rgb = applyGameClarity           (uv, strength);
    else if (effectId == 11) rgb = applyCinematicProfile      (uv, strength);
    else if (effectId == 12) rgb = applyVividProfile          (uv, strength);
    else if (effectId == 13) rgb = applyCompetitiveProfile    (uv, strength);
    else if (effectId == 14) rgb = applyAdaptiveSharpenProfile(uv, strength);
    else if (effectId == 15) rgb = applyFilmicProfile         (uv, strength);
    else if (effectId == 16) rgb = applyArcadeProfile         (uv, strength);
    else if (effectId == 17) rgb = applyRetroCRTProfile       (uv, strength);
    else if (effectId == 18) rgb = applyUpscaleSharp          (uv, strength);
    else if (effectId == 19) rgb = applyPixelClean            (uv, strength);
    else if (effectId == 20) rgb = applyAnimeEdge             (uv, strength);
    else if (effectId == 21) rgb = applyColorBoost            (uv, strength);
    else if (effectId == 22) rgb = applyColorAdjust           (uv);
    else if (effectId == 23) rgb = applyFXAA                  (uv);
    else if (effectId == 24) rgb = applyToon                  (uv);
    else if (effectId == 25) rgb = applyNTSC                  (uv);
    else if (effectId == 26) rgb = applyRCAS                  (uv);
    else                     rgb = texture(texSampler, uv).rgb;
    outColor = vec4(rgb, 1.0);
}
)GLSL";

// Lanczos2 with FSR's polynomial weight approximation (no sin()); the two positive middle taps of each axis
// are merged into one bilinear fetch, so 9 texture reads instead of 16. Needs GL_LINEAR on the source.
static constexpr const char *lanczos_frag = R"GLSL(#version 300 es
precision highp float;
uniform sampler2D texSampler;
uniform vec2 srcSize;
in vec2 vUV;
out vec4 outColor;

mediump vec4 lanczos2(mediump vec4 x) {
    mediump vec4 x2 = min(x * x, vec4(4.0));
    mediump vec4 a = 0.4 * x2 - 1.0;
    mediump vec4 b = 0.25 * x2 - 1.0;
    return (1.5625 * a * a - 0.5625) * (b * b);
}

void main() {
    vec2 size = max(srcSize, vec2(1.0));
    vec2 invSize = 1.0 / size;
    vec2 srcPos = vUV * size - vec2(0.5);
    vec2 base = floor(srcPos);
    mediump vec2 f = srcPos - base;

    // Taps at -1, 0, 1, 2 texels from base.
    mediump vec4 wx = lanczos2(vec4(1.0 + f.x, f.x, 1.0 - f.x, 2.0 - f.x));
    mediump vec4 wy = lanczos2(vec4(1.0 + f.y, f.y, 1.0 - f.y, 2.0 - f.y));
    wx /= dot(wx, vec4(1.0));
    wy /= dot(wy, vec4(1.0));

    mediump vec3 cx = vec3(wx.x, wx.y + wx.z, wx.w);
    mediump vec3 cy = vec3(wy.x, wy.y + wy.z, wy.w);
    vec3 px = (base.x + vec3(-1.0, wx.z / cx.y, 2.0) + 0.5) * invSize.x;
    vec3 py = (base.y + vec3(-1.0, wy.z / cy.y, 2.0) + 0.5) * invSize.y;

    mediump vec3 color = vec3(0.0);
    for (int j = 0; j < 3; ++j) {
        mediump vec3 row = texture(texSampler, vec2(px.x, py[j])).rgb * cx.x
                         + texture(texSampler, vec2(px.y, py[j])).rgb * cx.y
                         + texture(texSampler, vec2(px.z, py[j])).rgb * cx.z;
        color += row * cy[j];
    }
    outColor = vec4(clamp(color, 0.0, 1.0), 1.0);
}
)GLSL";

// SGSR and FSR share one edge-aware reconstruction shader (textureGather needs GLES 3.1).
// SGSR: edgeThreshold 12/255, spatial 0.40..0.65, edge sharpness 1.0..2.0, max delta 16..40/255.
// FSR:  edgeThreshold  8/255, spatial 0.52..0.86, edge sharpness 1.35..2.75, max delta 28..64/255.
static constexpr const char *sgsr_frag = R"GLSL(#version 310 es
precision mediump float;
precision highp int;

uniform mediump sampler2D texSampler;
uniform highp vec2 srcSize;
uniform float sharpness;
uniform float edgeThreshold;
uniform vec2 spatialRange;
uniform vec2 edgeSharpnessRange;
uniform vec2 maxDeltaRange;
in highp vec2 vUV;
out vec4 outColor;

float fastLanczos2(float x) {
    float wA = x - 4.0;
    float wB = x * wA - wA;
    wA *= wA;
    return wB * wA;
}

vec2 weightY(float dx, float dy, float c, float std, float spatialFactor) {
    float x = (dx * dx + dy * dy) * spatialFactor + clamp(abs(c) * std, 0.0, 1.0);
    float w = fastLanczos2(x);
    return vec2(w, w * c);
}

void main() {
    highp vec2 size = max(srcSize, vec2(1.0));
    highp vec2 step = 1.0 / size;
    float userSharp = clamp(sharpness, 0.0, 1.0);

    vec4 center = textureLod(texSampler, vUV, 0.0);

    highp vec2 imgCoord = vUV * size + vec2(-0.5, 0.5);
    highp vec2 imgCoordFloor = floor(imgCoord);
    highp vec2 baseUV = imgCoordFloor * step;
    vec2 pl = imgCoord - imgCoordFloor;

    vec4 left = textureGather(texSampler, baseUV, 1);
    float centerG = center.g;
    float edgeVote = abs(left.z - left.y) + abs(centerG - left.y) + abs(centerG - left.z);

    if (edgeVote <= edgeThreshold) {
        outColor = vec4(center.rgb, 1.0);
        return;
    }

    highp vec2 baseUV2 = baseUV + vec2(step.x, 0.0);
    vec4 right = textureGather(texSampler, baseUV2 + vec2(step.x, 0.0), 1);

    vec4 upDown;
    upDown.xy = textureGather(texSampler, baseUV + vec2(0.0, -step.y), 1).wz;
    upDown.zw = textureGather(texSampler, baseUV + vec2(0.0,  step.y), 1).yx;

    float mean = (left.y + left.z + right.x + right.w) * 0.25;
    left   -= vec4(mean);
    right  -= vec4(mean);
    upDown -= vec4(mean);

    float sum =
        abs(left.x)   + abs(left.y)   + abs(left.z)   + abs(left.w)   +
        abs(right.x)  + abs(right.y)  + abs(right.z)  + abs(right.w)  +
        abs(upDown.x) + abs(upDown.y) + abs(upDown.z) + abs(upDown.w);
    float std = 2.181818 / max(sum, 1.0e-6);

    float spatialFactor = mix(spatialRange.x, spatialRange.y, userSharp);

    vec2 aWY = weightY(pl.x,       pl.y + 1.0, upDown.x, std, spatialFactor);
    aWY += weightY(pl.x - 1.0, pl.y + 1.0, upDown.y, std, spatialFactor);
    aWY += weightY(pl.x - 1.0, pl.y - 2.0, upDown.z, std, spatialFactor);
    aWY += weightY(pl.x,       pl.y - 2.0, upDown.w, std, spatialFactor);
    aWY += weightY(pl.x + 1.0, pl.y - 1.0, left.x,   std, spatialFactor);
    aWY += weightY(pl.x,       pl.y - 1.0, left.y,   std, spatialFactor);
    aWY += weightY(pl.x,       pl.y,       left.z,   std, spatialFactor);
    aWY += weightY(pl.x + 1.0, pl.y,       left.w,   std, spatialFactor);
    aWY += weightY(pl.x - 1.0, pl.y - 1.0, right.x,  std, spatialFactor);
    aWY += weightY(pl.x - 2.0, pl.y - 1.0, right.y,  std, spatialFactor);
    aWY += weightY(pl.x - 2.0, pl.y,       right.z,  std, spatialFactor);
    aWY += weightY(pl.x - 1.0, pl.y,       right.w,  std, spatialFactor);

    float finalY = aWY.y / max(aWY.x, 1.0e-6);

    float maxY = max(max(left.y, left.z), max(right.x, right.w)) + mean;
    float minY = min(min(left.y, left.z), min(right.x, right.w)) + mean;

    float edgeSharpness = mix(edgeSharpnessRange.x, edgeSharpnessRange.y, userSharp);
    finalY = clamp(edgeSharpness * finalY + mean, minY, maxY);

    float maxDelta = mix(maxDeltaRange.x, maxDeltaRange.y, userSharp);
    float deltaY = clamp(finalY - centerG, -maxDelta, maxDelta);

    outColor = vec4(clamp(center.rgb + vec3(deltaY), 0.0, 1.0), 1.0);
}
)GLSL";

}
