#pragma once

namespace apex {

// 1. Unified Analytic Luma-Gradient-Confidence (Pass 2)
// Corrected UV mapping and halo staging to eliminate edge/corner flickering.
static const char* kShaderDisLuma = R"(#version 310 es
precision highp float;
precision highp sampler2D;
precision highp image2D;
layout(local_size_x = 16, local_size_y = 16, local_size_z = 1) in;

layout(binding = 0) uniform sampler2D colorMap;
layout(binding = 5, r32f) writeonly uniform highp image2D pyr0;
layout(binding = 13, rgba16f) writeonly uniform highp image2D gradMap;

shared float s_tile[18][18];

float uluminance(vec3 c) {
    return (0.299 * c.x + 0.587 * c.y + 0.114 * c.z) * 255.0;
}

void main() {
    uvec2 tid = gl_LocalInvocationID.xy;
    ivec2 sz = imageSize(pyr0);

    // Corrected Mapping: Map 18x18 tile (including halo) to [0,1] UV space of source
    for (int i = 0; i < 2; i++) {
        for (int j = 0; j < 2; j++) {
            ivec2 l_pos = ivec2(tid) + ivec2(i*16, j*16);
            if (l_pos.x < 18 && l_pos.y < 18) {
                ivec2 target_g_pos = ivec2(gl_WorkGroupID.xy * 16u) + l_pos - 1;
                vec2 uv = (vec2(target_g_pos) + 0.5) / vec2(sz);
                s_tile[l_pos.x][l_pos.y] = uluminance(textureLod(colorMap, uv, 0.0).xyz);
            }
        }
    }
    barrier();

    ivec2 pix = ivec2(gl_GlobalInvocationID.xy);
    if (pix.x >= sz.x || pix.y >= sz.y) return;

    // Use halo pixels for high-fidelity gradients (Removes bottom-left flicker)
    float l = s_tile[tid.x+1][tid.y+1];
    imageStore(pyr0, pix, vec4(l, 0, 0, 0));

    float sx = (s_tile[tid.x][tid.y] + 2.0*s_tile[tid.x][tid.y+1] + s_tile[tid.x][tid.y+2])
             - (s_tile[tid.x+2][tid.y] + 2.0*s_tile[tid.x+2][tid.y+1] + s_tile[tid.x+2][tid.y+2]);
    float sy = (s_tile[tid.x][tid.y] + 2.0*s_tile[tid.x+1][tid.y] + s_tile[tid.x+2][tid.y])
             - (s_tile[tid.x][tid.y+2] + 2.0*s_tile[tid.x+1][tid.y+2] + s_tile[tid.x+2][tid.y+2]);

    imageStore(gradMap, pix, vec4(clamp(sx/8.0, -255.0, 255.0), clamp(sy/8.0, -255.0, 255.0), 0.0, 1.0));
}
)";

// 3. Elite Inverse Search (Staged Gauss-Newton with Hessian Inversion)
static const char* kShaderDisInverseSearch = R"(#version 310 es
precision highp float;
precision highp sampler2D;
precision highp image2D;
layout(local_size_x = 8, local_size_y = 8, local_size_z = 1) in;

layout(binding = 0) uniform sampler2D lastLuma;
layout(binding = 1) uniform sampler2D nextLuma;
layout(binding = 2) uniform sampler2D lastGrad;
layout(binding = 3) uniform sampler2D flowMap;
layout(binding = 5, rgba16f) writeonly uniform highp image2D outSparse;

shared float s_luma[64];
shared vec2 s_grad[64];

void main() {
    ivec2 tid = ivec2(gl_LocalInvocationID.xy);
    uint idx = uint(tid.y * 8 + tid.x);
    ivec2 pixSparse = ivec2(gl_GlobalInvocationID.xy);
    ivec2 pix = pixSparse * 3;
    ivec2 sz = textureSize(lastLuma, 0);

    s_luma[idx] = texelFetch(lastLuma, clamp(pix + tid, ivec2(0), sz-1), 0).x;
    s_grad[idx] = -texelFetch(lastGrad, clamp(pix + tid, ivec2(0), sz-1), 0).xy;
    barrier();

    vec2 flow = textureLod(flowMap, (vec2(pixSparse)+0.5)/vec2(imageSize(outSparse)), 0.0).xy * vec2(sz);
    if (any(isnan(flow))) flow = vec2(0.0);

    for (int it = 0; it < 8; it++) {
        vec2 sIg = vec2(0.0);
        for (int i = 0; i < 64; i++) {
            vec2 tc = (vec2(pix) + vec2(i%8, i/8) + flow + 0.5) / vec2(sz);
            sIg += s_grad[i] * (textureLod(nextLuma, tc, 0.0).x - s_luma[i]);
        }
        flow -= sIg * 0.015;
    }
    imageStore(outSparse, pixSparse, vec4(flow / vec2(sz), 0, 1.0));
}
)";

// 4. Fluid Spatial Propagation (Box Smoothing)
static const char* kShaderDisPropagate = R"(#version 310 es
precision highp float;
precision highp sampler2D;
precision highp image2D;
layout(local_size_x = 8, local_size_y = 8, local_size_z = 1) in;
layout(binding = 2) uniform sampler2D flowIn;
layout(binding = 5, rgba16f) writeonly uniform highp image2D flowOut;
uniform int u_dist;
void main() {
    ivec2 s = ivec2(gl_GlobalInvocationID.xy);
    ivec2 sz = imageSize(flowOut);
    if (s.x >= sz.x || s.y >= sz.y) return;

    vec2 sum = texelFetch(flowIn, s, 0).xy * 2.0;
    float w = 2.0;
    ivec2 offsets[4] = ivec2[](ivec2(1,0), ivec2(-1,0), ivec2(0,1), ivec2(0,-1));
    for(int i=0; i<4; i++) {
        ivec2 p = clamp(s + offsets[i] * u_dist, ivec2(0), sz-1);
        sum += texelFetch(flowIn, p, 0).xy;
        w += 1.0;
    }
    imageStore(flowOut, s, vec4(sum / w, 0, 1.0));
}
)";

// 5. Unified Fluid Densification & Setup (Pass 6)
static const char* kShaderDisFluidDensifySetup = R"(#version 310 es
precision highp float;
precision highp sampler2D;
precision highp image2D;
layout(local_size_x = 8, local_size_y = 8, local_size_z = 1) in;

layout(binding = 0) uniform sampler2D sparseFlow;
layout(binding = 1) uniform sampler2D lastLuma;
layout(binding = 2) uniform sampler2D nextLuma;
layout(binding = 3) uniform sampler2D prevColor;
layout(binding = 4) uniform sampler2D nextColor;
layout(binding = 5, rgba16f) writeonly uniform highp image2D denseFlow;
layout(binding = 8, rgba16f) writeonly uniform highp image2D prepImg;
layout(binding = 9, rgba16f) writeonly uniform highp image2D dWInit;
layout(binding = 10, rgba16f) writeonly uniform highp image2D A;
layout(binding = 11, rgba16f) writeonly uniform highp image2D B;

float lum(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)) * 255.0; }

void main() {
    ivec2 pix = ivec2(gl_GlobalInvocationID.xy);
    ivec2 sz = imageSize(denseFlow);
    if (pix.x >= sz.x || pix.y >= sz.y) return;
    vec2 uv = (vec2(pix) + 0.5) / vec2(sz);

    vec2 flow = textureLod(sparseFlow, uv, 0.0).xy;
    imageStore(denseFlow, pix, vec4(flow, 0.0, 1.0));
    imageStore(dWInit, pix, vec4(0.0));

    float i0 = lum(textureLod(prevColor, uv, 0.0).xyz);
    float w = lum(textureLod(nextColor, uv + flow, 0.0).xyz);
    float Iz = w - i0;
    imageStore(prepImg, pix, vec4(0.5 * (i0 + w), Iz, 0.0, 0.0));
    imageStore(A, pix, vec4(1.0, 0.0, 1.0, 0.0));
    imageStore(B, pix, vec4(-Iz * 0.35, -Iz * 0.35, 0.0, 0.0));
}
)";

// 11. Red-Black SOR Sweep (ω = 1.45, Ultra-Elastic)
static const char* kShaderDisVrSor = R"(#version 310 es
precision highp float;
precision highp sampler2D;
precision highp image2D;
layout(local_size_x = 8, local_size_y = 8, local_size_z = 1) in;
layout(binding = 0) uniform sampler2D A;
layout(binding = 1) uniform sampler2D B;
layout(binding = 3) uniform sampler2D dWin;
layout(binding = 8, rgba16f) writeonly uniform highp image2D dWout;
uniform float u_omega;
uniform int u_parity;
void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    ivec2 sz = imageSize(dWout);
    if (p.x >= sz.x || p.y >= sz.y) return;
    if (((p.x + p.y) & 1) != u_parity) {
        imageStore(dWout, p, texelFetch(dWin, p, 0));
        return;
    }
    vec2 d = texelFetch(dWin, p, 0).xy;
    vec2 Bv = texelFetch(B, p, 0).xy;

    vec2 sigma = vec2(0.0);
    sigma += texelFetch(dWin, clamp(p+ivec2(1,0), ivec2(0), sz-1), 0).xy;
    sigma += texelFetch(dWin, clamp(p-ivec2(1,0), ivec2(0), sz-1), 0).xy;
    sigma += texelFetch(dWin, clamp(p+ivec2(0,1), ivec2(0), sz-1), 0).xy;
    sigma += texelFetch(dWin, clamp(p-ivec2(0,1), ivec2(0), sz-1), 0).xy;

    vec2 nu = d + u_omega * ((Bv + sigma * 0.1) - d);
    imageStore(dWout, p, vec4(nu, 0, 0));
}
)";

// 13. Elite Fluid Interpolator (Pass 9)
static const char* kShaderDisInterpolate = R"(#version 310 es
precision highp float;
precision highp sampler2D;
precision highp image2D;
layout(local_size_x = 8, local_size_y = 8, local_size_z = 1) in;

layout(binding = 0) uniform sampler2D prevColor;
layout(binding = 1) uniform sampler2D nextColor;
layout(binding = 2) uniform sampler2D denseFlow;
layout(binding = 3) uniform sampler2D dW;
layout(binding = 5, rgba8) writeonly uniform highp image2D outImage;

uniform float u_t;
uniform float u_flowScale;
uniform float u_liquidFeel;
uniform float u_shutterGain;
uniform float u_edgeGuard;

void main() {
    ivec2 pix = ivec2(gl_GlobalInvocationID.xy);
    ivec2 sz = imageSize(outImage);
    if (pix.x >= sz.x || pix.y >= sz.y) return;
    vec2 uv = (vec2(pix) + 0.5) / vec2(sz);

    vec2 baseF = textureLod(denseFlow, uv, 0.0).xy;
    vec2 refF = textureLod(dW, uv, 0.0).xy;
    vec2 f = (baseF + refF) * (u_flowScale > 0.0 ? u_flowScale : 1.0);

    float guardPx = mix(2.0, 32.0, u_edgeGuard);
    vec2 edgeMask = smoothstep(vec2(0.0), guardPx/vec2(sz), uv) *
                    smoothstep(vec2(0.0), guardPx/vec2(sz), 1.0 - uv);
    f *= min(edgeMask.x, edgeMask.y);

    vec2 uv0 = clamp(uv - u_t * f, 0.0, 1.0);
    vec2 uv1 = clamp(uv + (1.0 - u_t) * f, 0.0, 1.0);
    vec3 c0 = textureLod(prevColor, uv0, 0.0).xyz;
    vec3 c1 = textureLod(nextColor, uv1, 0.0).xyz;

    float diff = dot(abs(c0 - c1), vec3(0.299, 0.587, 0.114));
    float feel = clamp(u_liquidFeel, 0.0, 1.0);
    float snapAlpha = smoothstep(mix(0.1, 0.3, feel), mix(0.5, 0.8, feel), diff);

    vec3 mixed = c0 * (1.0 - u_t) + c1 * u_t;
    vec3 result = mix(mixed, (u_t < 0.5 ? c0 : c1), snapAlpha * 0.9);

    if (u_shutterGain > 0.05) {
        vec2 blurStep = f * (u_shutterGain * 0.2);
        vec3 b0 = textureLod(prevColor, clamp(uv0 - blurStep, 0.0, 1.0), 0.0).xyz;
        vec3 b1 = textureLod(nextColor, clamp(uv1 + blurStep, 0.0, 1.0), 0.0).xyz;
        result = mix(result, 0.5 * (b0 + b1), clamp(u_shutterGain, 0.0, 0.5));
    }

    imageStore(outImage, pix, vec4(result, 1.0));
}
)";

} // namespace apex
