#pragma once

namespace apex {

// ---------------------------------------------------------------------------------
// Pass 1-4: Combined Luma & Sobel Gradient Pyramid
// ---------------------------------------------------------------------------------
static const char* kShaderDisLumaGrad = R"(#version 310 es
precision highp float;
precision highp sampler2D;
precision highp image2D;
layout(local_size_x = 16, local_size_y = 16) in;

layout(binding = 0) uniform sampler2D u_colorMap;
layout(binding = 1, r32f) writeonly uniform highp image2D u_pyrOut;
layout(binding = 2, rgba16f) writeonly uniform highp image2D u_gradOut;

// Unified GPU Mathematical Divergence & Pipeline Telemetry Buffer
layout(std430, binding = 5) buffer UnifiedTelemetryBuffer {
    uint u_lumaTotalPixels;
    uint u_lumaNanInfCount;
    uint u_searchTotalPatches;
    uint u_searchNanInfCount;
    uint u_searchRevertedCount;
    uint u_searchZeroCollapseCount;
    uint u_searchActiveMovingCount;
    uint u_propTotalPatches;
    uint u_propImprovedCount;
    uint u_propNanInfCount;
    uint u_denseTotalPixels;
    uint u_denseActiveMovingCount;
    uint u_denseZeroWeightCount;
    uint u_denseNanInfCount;
    uint u_interpTotalPixels;
    uint u_interpOccludedCount;
    uint u_interpOutOfBoundsCount;
    uint u_interpNanInfCount;
};

uniform int u_isColor;
uniform int u_collectTelemetry;

float getLum(vec4 c) {
    if (u_isColor != 0) {
        return (0.299 * c.r + 0.587 * c.g + 0.114 * c.b) * 255.0;
    } else {
        return c.r;
    }
}

void main() {
    ivec2 pix = ivec2(gl_GlobalInvocationID.xy);
    ivec2 sz = imageSize(u_pyrOut);
    if (pix.x >= sz.x || pix.y >= sz.y) return;
    ivec2 mx = sz - 1;

    ivec2 inSz = textureSize(u_colorMap, 0);
    vec2 scale = vec2(inSz) / vec2(sz);
    ivec2 srcP = ivec2(vec2(pix) * scale);
    ivec2 srcMx = inSz - 1;

    float a00 = getLum(texelFetch(u_colorMap, clamp(srcP + ivec2(-1, -1), ivec2(0), srcMx), 0));
    float a10 = getLum(texelFetch(u_colorMap, clamp(srcP + ivec2( 0, -1), ivec2(0), srcMx), 0));
    float a20 = getLum(texelFetch(u_colorMap, clamp(srcP + ivec2( 1, -1), ivec2(0), srcMx), 0));
    float a01 = getLum(texelFetch(u_colorMap, clamp(srcP + ivec2(-1,  0), ivec2(0), srcMx), 0));
    float a11 = getLum(texelFetch(u_colorMap, clamp(srcP,                 ivec2(0), srcMx), 0));
    float a21 = getLum(texelFetch(u_colorMap, clamp(srcP + ivec2( 1,  0), ivec2(0), srcMx), 0));
    float a02 = getLum(texelFetch(u_colorMap, clamp(srcP + ivec2(-1,  1), ivec2(0), srcMx), 0));
    float a12 = getLum(texelFetch(u_colorMap, clamp(srcP + ivec2( 0,  1), ivec2(0), srcMx), 0));
    float a22 = getLum(texelFetch(u_colorMap, clamp(srcP + ivec2( 1,  1), ivec2(0), srcMx), 0));

    float sx = (3.0 * a00 + 10.0 * a01 + 3.0 * a02) - (3.0 * a20 + 10.0 * a21 + 3.0 * a22);
    float sy = (3.0 * a00 + 10.0 * a10 + 3.0 * a20) - (3.0 * a02 + 10.0 * a12 + 3.0 * a22);
    const float normVal = 1.0 / 32.0;

    imageStore(u_pyrOut, pix, vec4(a11, 0.0, 0.0, 1.0));
    imageStore(u_gradOut, pix, vec4(sx * normVal, sy * normVal, 0.0, 1.0));

    if (u_collectTelemetry != 0 && u_isColor != 0) {
        atomicAdd(u_lumaTotalPixels, 1u);
        if (isnan(a11) || isinf(a11) || isnan(sx) || isinf(sx) || isnan(sy) || isinf(sy)) {
            atomicAdd(u_lumaNanInfCount, 1u);
        }
    }
}
)";

// ---------------------------------------------------------------------------------
// Pass 5-8: Gauss-Newton Inverse Search with Telemetry & Zero-Motion Shield
// ---------------------------------------------------------------------------------
static const char* kShaderDisInverseSearch = R"(#version 310 es
precision highp float;
precision highp sampler2D;
precision highp image2D;
layout(local_size_x = 8, local_size_y = 8) in;

layout(binding = 0) uniform sampler2D lastLumaMap;
layout(binding = 1) uniform sampler2D nextLumaMap;
layout(binding = 2) uniform sampler2D lastGradientMap;
layout(binding = 3) uniform sampler2D flowMap;
layout(binding = 4, rgba16f) writeonly uniform highp image2D outSparseFlow;

// Unified GPU Mathematical Divergence & Pipeline Telemetry Buffer
layout(std430, binding = 5) buffer UnifiedTelemetryBuffer {
    uint u_lumaTotalPixels;
    uint u_lumaNanInfCount;
    uint u_searchTotalPatches;
    uint u_searchNanInfCount;
    uint u_searchRevertedCount;
    uint u_searchZeroCollapseCount;
    uint u_searchActiveMovingCount;
    uint u_propTotalPatches;
    uint u_propImprovedCount;
    uint u_propNanInfCount;
    uint u_denseTotalPixels;
    uint u_denseActiveMovingCount;
    uint u_denseZeroWeightCount;
    uint u_denseNanInfCount;
    uint u_interpTotalPixels;
    uint u_interpOccludedCount;
    uint u_interpOutOfBoundsCount;
    uint u_interpNanInfCount;
};

uniform int u_level;
uniform int u_coarseLevel;
uniform int u_collectTelemetry;

mat2 invertMat2(mat2 m) {
    float det = m[0][0] * m[1][1] - m[0][1] * m[1][0];
    if (abs(det) < 1e-6) return mat2(0.0);
    return mat2(m[1][1], -m[0][1], -m[1][0], m[0][0]) / det;
}

void main() {
    const float patchSize = 8.0;
    ivec2 pixSparse = ivec2(gl_GlobalInvocationID.xy);
    ivec2 sparseSize = imageSize(outSparseFlow);
    if (pixSparse.x >= sparseSize.x || pixSparse.y >= sparseSize.y) return;

    ivec2 pix = pixSparse * 3;
    ivec2 denseSize = textureSize(lastLumaMap, 0);
    ivec2 denseMax = denseSize - 1;

    float lastImageData[64];
    vec2 gradData[64];
    vec2 gradSum = vec2(0.0);
    mat2 H = mat2(0.0);

    for (int i = 0; i < 8; i++) {
        for (int j = 0; j < 8; j++) {
            int idx = i * 8 + j;
            ivec2 q = clamp(pix + ivec2(i, j), ivec2(0), denseMax);
            gradData[idx] = -texelFetch(lastGradientMap, q, 0).xy;
            H[0][0] += gradData[idx].x * gradData[idx].x;
            H[1][1] += gradData[idx].y * gradData[idx].y;
            H[0][1] += gradData[idx].x * gradData[idx].y;
            lastImageData[idx] = texelFetch(lastLumaMap, q, 0).x;
            gradSum += gradData[idx];
        }
    }
    H[1][0] = H[0][1];
    H[0][0] += 1e-4;
    H[1][1] += 1e-4;
    mat2 H_inv = invertMat2(H);

    vec2 flow = vec2(0.0);
    vec2 invImageSize = 1.0 / vec2(denseSize);
    if (u_level != u_coarseLevel) {
        vec2 uv = (vec2(pix) + 4.0) * invImageSize;
        vec4 cf = textureLod(flowMap, uv, 0.0);
        flow = cf.xy * vec2(denseSize);
        if (any(isnan(flow)) || any(isinf(flow))) flow = vec2(0.0);
    }
    vec2 initialFlow = flow;

    vec2 bestFlow = flow;
    float prevSSD = 1e10;
    int maxIter = 8;
    for (int iter = 0; iter < maxIter; iter++) {
        vec2 warpOrigin = clamp(vec2(pix) + flow, vec2(0.0), vec2(denseSize) - patchSize);
        float sd = 0.0;
        float sd2 = 0.0;
        vec2 sIg = vec2(0.0);

        for (int i = 0; i < 8; i++) {
            for (int j = 0; j < 8; j++) {
                int idx = i * 8 + j;
                vec2 tc = (warpOrigin + vec2(i, j) + 0.5) * invImageSize;
                float warped = textureLod(nextLumaMap, tc, 0.0).x;
                float diff = warped - lastImageData[idx];
                sd += diff;
                sd2 += diff * diff;
                sIg += gradData[idx] * diff;
            }
        }

        vec2 dU = sIg - sd * gradSum / 64.0;
        float SSD = sd2 - sd * sd / 64.0;

        if (SSD >= prevSSD) {
            flow = bestFlow;
            break;
        }
        prevSSD = SSD;
        bestFlow = flow;
        flow -= H_inv * dU;
    }

    vec2 wantOrigin = vec2(pix) + flow;
    vec2 maxOrigin = vec2(denseSize) - patchSize;
    bool clamped = any(lessThan(wantOrigin, vec2(-0.5))) || any(greaterThan(wantOrigin, maxOrigin + 0.5));
    bool unmatched = (prevSSD / 64.0) > (36.0 * 36.0);

    bool reverted = any(isnan(flow)) || any(isinf(flow)) || clamped || unmatched || length(flow - initialFlow) > patchSize;
    if (reverted) flow = initialFlow;

    // Zero-Motion HUD Shield (DIS_ZERO_MATCH_RATIO = 0.5, squared = 0.25)
    float zeroSsd = -1.0;
    if (!reverted && dot(flow, flow) > 0.25) {
        float zsd = 0.0;
        float zsd2 = 0.0;
        for (int i = 0; i < 8; i++) {
            for (int j = 0; j < 8; j++) {
                int idx = i * 8 + j;
                vec2 tc = (vec2(pix) + vec2(i, j) + 0.5) * invImageSize;
                float warped = textureLod(nextLumaMap, tc, 0.0).x;
                float diff = warped - lastImageData[idx];
                zsd += diff;
                zsd2 += diff * diff;
            }
        }
        float zssd = zsd2 - zsd * zsd / 64.0;
        if (zssd <= prevSSD * 0.25) {
            flow = vec2(0.0);
            zeroSsd = zssd;
        }
    }

    // Telemetry capture on Level 0 (finest level which dictates final interpolation)
    if (u_collectTelemetry != 0 && u_level == 0) {
        atomicAdd(u_searchTotalPatches, 1u);
        if (any(isnan(flow)) || any(isinf(flow))) {
            atomicAdd(u_searchNanInfCount, 1u);
        }
        if (reverted) {
            atomicAdd(u_searchRevertedCount, 1u);
        }
        if (dot(flow, flow) < 1e-4) {
            atomicAdd(u_searchZeroCollapseCount, 1u);
        } else {
            atomicAdd(u_searchActiveMovingCount, 1u);
        }
    }

    imageStore(outSparseFlow, pixSparse, vec4(flow * invImageSize, zeroSsd, 1.0));
}
)";

// ---------------------------------------------------------------------------------
// Pass 9-12: 4-Direction Spatial Candidate Propagation
// ---------------------------------------------------------------------------------
static const char* kShaderDisPropagate = R"(#version 310 es
precision highp float;
precision highp sampler2D;
precision highp image2D;
layout(local_size_x = 8, local_size_y = 8) in;

layout(binding = 0) uniform sampler2D lastLumaMap;
layout(binding = 1) uniform sampler2D nextLumaMap;
layout(binding = 2) uniform sampler2D flowIn;
layout(binding = 3, rgba16f) writeonly uniform highp image2D flowOut;

// Unified GPU Mathematical Divergence & Pipeline Telemetry Buffer
layout(std430, binding = 5) buffer UnifiedTelemetryBuffer {
    uint u_lumaTotalPixels;
    uint u_lumaNanInfCount;
    uint u_searchTotalPatches;
    uint u_searchNanInfCount;
    uint u_searchRevertedCount;
    uint u_searchZeroCollapseCount;
    uint u_searchActiveMovingCount;
    uint u_propTotalPatches;
    uint u_propImprovedCount;
    uint u_propNanInfCount;
    uint u_denseTotalPixels;
    uint u_denseActiveMovingCount;
    uint u_denseZeroWeightCount;
    uint u_denseNanInfCount;
    uint u_interpTotalPixels;
    uint u_interpOccludedCount;
    uint u_interpOutOfBoundsCount;
    uint u_interpNanInfCount;
};

uniform int u_dist;
uniform int u_level;
uniform int u_collectTelemetry;

void main() {
    ivec2 s = ivec2(gl_GlobalInvocationID.xy);
    ivec2 mx = textureSize(flowIn, 0) - 1;
    if (s.x > mx.x || s.y > mx.y) return;

    vec4 ownIn = texelFetch(flowIn, s, 0);
    vec2 own = ownIn.xy;
    float ownSsd = ownIn.z;
    bool needOwn = !(ownSsd >= 0.0);

    const ivec2 dirs[4] = ivec2[4](ivec2(-1, 0), ivec2(1, 0), ivec2(0, -1), ivec2(0, 1));
    vec2 cand[4];
    int candCount = 0;
    for (int i = 0; i < 4; i++) {
        ivec2 q = clamp(s + dirs[i] * u_dist, ivec2(0), mx);
        if (q != s) cand[candCount++] = texelFetch(flowIn, q, 0).xy;
    }

    if (candCount == 0) {
        imageStore(flowOut, s, vec4(own, ownSsd, 1.0));
        return;
    }

    ivec2 org = s * 3;
    ivec2 denseSize = textureSize(lastLumaMap, 0);
    ivec2 denseMax = denseSize - 1;
    vec2 invImageSize = 1.0 / vec2(denseSize);

    float sd[5];
    float sd2[5];
    for (int i = 0; i < 5; i++) { sd[i] = 0.0; sd2[i] = 0.0; }

    for (int dy = 0; dy < 8; dy++) {
        for (int dx = 0; dx < 8; dx++) {
            ivec2 p = clamp(org + ivec2(dx, dy), ivec2(0), denseMax);
            float r = texelFetch(lastLumaMap, p, 0).x;
            vec2 base = (vec2(org) + vec2(dx, dy) + 0.5) * invImageSize;

            if (needOwn) {
                float d0 = textureLod(nextLumaMap, base + own, 0.0).x - r;
                sd[0] += d0; sd2[0] += d0 * d0;
            }
            for (int c = 0; c < candCount; c++) {
                float dc = textureLod(nextLumaMap, base + cand[c], 0.0).x - r;
                sd[c + 1] += dc; sd2[c + 1] += dc * dc;
            }
        }
    }

    vec2 best = own;
    float bestSsd = needOwn ? (sd2[0] - sd[0] * sd[0] / 64.0) : ownSsd;
    for (int c = 0; c < candCount; c++) {
        float ssd = sd2[c + 1] - sd[c + 1] * sd[c + 1] / 64.0;
        if (ssd < bestSsd) {
            bestSsd = ssd;
            best = cand[c];
        }
    }

    if (u_collectTelemetry != 0 && u_level == 0 && u_dist == 2) {
        atomicAdd(u_propTotalPatches, 1u);
        if (any(isnan(best)) || any(isinf(best)) || isnan(bestSsd) || isinf(bestSsd)) {
            atomicAdd(u_propNanInfCount, 1u);
        }
        if (bestSsd < ownSsd) {
            atomicAdd(u_propImprovedCount, 1u);
        }
    }

    imageStore(flowOut, s, vec4(best, bestSsd, 1.0));
}
)";

// ---------------------------------------------------------------------------------
// Pass 13: 9-Tap Bilateral Guided Densification
// ---------------------------------------------------------------------------------
static const char* kShaderDisDensify = R"(#version 310 es
precision highp float;
precision highp sampler2D;
precision highp image2D;
layout(local_size_x = 8, local_size_y = 8) in;

layout(binding = 0) uniform sampler2D sparseFlowMap;
layout(binding = 1) uniform sampler2D lastImage;
layout(binding = 2) uniform sampler2D nextImage;
layout(binding = 3, rgba16f) writeonly uniform highp image2D denseFlowMap;

// Unified GPU Mathematical Divergence & Pipeline Telemetry Buffer
layout(std430, binding = 5) buffer UnifiedTelemetryBuffer {
    uint u_lumaTotalPixels;
    uint u_lumaNanInfCount;
    uint u_searchTotalPatches;
    uint u_searchNanInfCount;
    uint u_searchRevertedCount;
    uint u_searchZeroCollapseCount;
    uint u_searchActiveMovingCount;
    uint u_propTotalPatches;
    uint u_propImprovedCount;
    uint u_propNanInfCount;
    uint u_denseTotalPixels;
    uint u_denseActiveMovingCount;
    uint u_denseZeroWeightCount;
    uint u_denseNanInfCount;
    uint u_interpTotalPixels;
    uint u_interpOccludedCount;
    uint u_interpOutOfBoundsCount;
    uint u_interpNanInfCount;
};

uniform int u_level;
uniform int u_collectTelemetry;

void main() {
    ivec2 pix = ivec2(gl_GlobalInvocationID.xy);
    ivec2 denseSize = imageSize(denseFlowMap);
    if (pix.x >= denseSize.x || pix.y >= denseSize.y) return;

    ivec2 sparseSize = textureSize(sparseFlowMap, 0);
    vec2 invDenseSize = 1.0 / vec2(denseSize);
    vec2 uv = (vec2(pix) + 0.5) * invDenseSize;
    float lastLum = textureLod(lastImage, uv, 0.0).x;

    vec4 cand[9];
    int candCount = 0;
    float bestSsd = -1.0;

    ivec2 s0 = ivec2(pix.x / 3, pix.y / 3) - 1;
    for (int dy = 0; dy <= 2; dy++) {
        for (int dx = 0; dx <= 2; dx++) {
            ivec2 s = s0 + ivec2(dx, dy);
            if (s.x < 0 || s.y < 0 || s.x >= sparseSize.x || s.y >= sparseSize.y) continue;
            vec4 f = texelFetch(sparseFlowMap, s, 0);
            f.w = textureLod(lastImage, (vec2(s * 3) + 4.0) * invDenseSize, 0.0).x;
            cand[candCount++] = f;
            if (f.z >= 0.0 && (bestSsd < 0.0 || f.z < bestSsd)) bestSsd = f.z;
        }
    }

    const float floorSsd = 64.0 * 2.25;
    const float invGuide2 = 1.0 / (18.0 * 18.0);
    float refSsd = max(bestSsd, floorSsd);

    vec2 acc = vec2(0.0);
    float accW = 0.0;
    for (int i = 0; i < candCount; i++) {
        vec4 f = cand[i];
        float diff = textureLod(nextImage, uv + f.xy, 0.0).x - lastLum;
        float w = 1.0 / (1.0 + abs(diff) * 0.15);
        if (bestSsd >= 0.0 && f.z >= 0.0) {
            float r = f.z / refSsd;
            w /= (1.0 + r * r);
        }
        float dl = f.w - lastLum;
        w /= (1.0 + dl * dl * invGuide2);

        acc += f.xy * w;
        accW += w;
    }

    vec2 fallbackFlow = texelFetch(sparseFlowMap, clamp(ivec2(pix.x / 3, pix.y / 3), ivec2(0), sparseSize - 1), 0).xy;
    vec2 denseFlow = accW > 0.0 ? (acc / accW) : fallbackFlow;

    if (u_collectTelemetry != 0 && u_level == 0) {
        atomicAdd(u_denseTotalPixels, 1u);
        if (accW <= 0.0) {
            atomicAdd(u_denseZeroWeightCount, 1u);
        }
        if (any(isnan(denseFlow)) || any(isinf(denseFlow))) {
            atomicAdd(u_denseNanInfCount, 1u);
        }
        if (dot(denseFlow, denseFlow) > 1e-6) {
            atomicAdd(u_denseActiveMovingCount, 1u);
        }
    }

    imageStore(denseFlowMap, pix, vec4(denseFlow, 0.0, 1.0));
}
)";

// ---------------------------------------------------------------------------------
// Pass 14: Variational Refinement Setup
// ---------------------------------------------------------------------------------
static const char* kShaderDisVrSetup = R"(#version 310 es
precision highp float;
precision highp sampler2D;
precision highp image2D;
layout(local_size_x = 8, local_size_y = 8) in;

layout(binding = 0) uniform sampler2D denseFlow;
layout(binding = 1) uniform sampler2D prevColor;
layout(binding = 2) uniform sampler2D nextColor;
layout(binding = 3, rgba16f) writeonly uniform highp image2D outA;
layout(binding = 4, rgba16f) writeonly uniform highp image2D outB;
layout(binding = 5, rgba16f) writeonly uniform highp image2D outDW;

float getLum(vec3 c) { return (0.299 * c.r + 0.587 * c.g + 0.114 * c.b) * 255.0; }

void main() {
    ivec2 pix = ivec2(gl_GlobalInvocationID.xy);
    ivec2 sz = imageSize(outDW);
    if (pix.x >= sz.x || pix.y >= sz.y) return;
    vec2 uv = (vec2(pix) + 0.5) / vec2(sz);
    vec2 invSz = 1.0 / vec2(sz);

    vec2 f = textureLod(denseFlow, uv, 0.0).xy;
    float i0 = getLum(textureLod(prevColor, uv, 0.0).xyz);
    float w  = getLum(textureLod(nextColor, uv + f, 0.0).xyz);
    float it = w - i0;

    // Spatial gradients on warped nextColor
    float w_r = getLum(textureLod(nextColor, uv + f + vec2(invSz.x, 0.0), 0.0).xyz);
    float w_l = getLum(textureLod(nextColor, uv + f - vec2(invSz.x, 0.0), 0.0).xyz);
    float w_d = getLum(textureLod(nextColor, uv + f + vec2(0.0, invSz.y), 0.0).xyz);
    float w_u = getLum(textureLod(nextColor, uv + f - vec2(0.0, invSz.y), 0.0).xyz);
    float ix = (w_r - w_l) * 0.5;
    float iy = (w_d - w_u) * 0.5;

    // Diagonal components of tensor system
    const float alpha = 20.0;
    imageStore(outA, pix, vec4(ix * ix + alpha, iy * iy + alpha, ix * iy, 1.0));
    imageStore(outB, pix, vec4(-ix * it, -iy * it, 0.0, 1.0));
    imageStore(outDW, pix, vec4(0.0));
}
)";

// ---------------------------------------------------------------------------------
// Pass 15-18: Red-Black Successive Over-Relaxation (SOR) with Spatial Neighbor Laplacian
// ---------------------------------------------------------------------------------
static const char* kShaderDisVrSor = R"(#version 310 es
precision highp float;
precision highp image2D;
layout(local_size_x = 8, local_size_y = 8) in;

layout(binding = 0, rgba16f) readonly uniform highp image2D u_A;
layout(binding = 1, rgba16f) readonly uniform highp image2D u_B;
layout(binding = 2, rgba16f) readonly uniform highp image2D u_dWin;
layout(binding = 3, rgba16f) writeonly uniform highp image2D u_dWout;

uniform float u_omega;
uniform int u_parity;

void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    ivec2 sz = imageSize(u_dWout);
    if (p.x >= sz.x || p.y >= sz.y) return;

    if (((p.x + p.y) & 1) != u_parity) {
        imageStore(u_dWout, p, imageLoad(u_dWin, p));
        return;
    }

    vec4 A = imageLoad(u_A, p);
    vec2 B = imageLoad(u_B, p).xy;
    vec2 cur = imageLoad(u_dWin, p).xy;

    vec2 d_left  = imageLoad(u_dWin, clamp(p + ivec2(-1, 0), ivec2(0), sz - 1)).xy;
    vec2 d_right = imageLoad(u_dWin, clamp(p + ivec2( 1, 0), ivec2(0), sz - 1)).xy;
    vec2 d_up    = imageLoad(u_dWin, clamp(p + ivec2( 0,-1), ivec2(0), sz - 1)).xy;
    vec2 d_down  = imageLoad(u_dWin, clamp(p + ivec2( 0, 1), ivec2(0), sz - 1)).xy;
    vec2 lap = d_left + d_right + d_up + d_down;

    // Gauss-Seidel step with spatial smoothing
    vec2 sigma = lap * 20.0 + B;
    vec2 gs = vec2(sigma.x / (A.x + 80.0), sigma.y / (A.y + 80.0));
    vec2 nextDW = clamp(cur + u_omega * (gs - cur), vec2(-2.0), vec2(2.0));

    imageStore(u_dWout, p, vec4(nextDW, 0.0, 1.0));
}
)";

// ---------------------------------------------------------------------------------
// Pass 19: Full-Res 16-Tap Catmull-Rom Bicubic Interpolator
// ---------------------------------------------------------------------------------
static const char* kShaderDisInterpolate = R"(#version 310 es
precision highp float;
precision highp int;
layout(local_size_x = 16, local_size_y = 8) in;

layout(binding = 0) uniform sampler2D prevColor;
layout(binding = 1) uniform sampler2D nextColor;
layout(binding = 2) uniform sampler2D denseFlow;
layout(binding = 3) uniform sampler2D dW;
layout(binding = 4, rgba8) writeonly uniform highp image2D outImage;

// Unified GPU Mathematical Divergence & Pipeline Telemetry Buffer
layout(std430, binding = 5) buffer UnifiedTelemetryBuffer {
    uint u_lumaTotalPixels;
    uint u_lumaNanInfCount;
    uint u_searchTotalPatches;
    uint u_searchNanInfCount;
    uint u_searchRevertedCount;
    uint u_searchZeroCollapseCount;
    uint u_searchActiveMovingCount;
    uint u_propTotalPatches;
    uint u_propImprovedCount;
    uint u_propNanInfCount;
    uint u_denseTotalPixels;
    uint u_denseActiveMovingCount;
    uint u_denseZeroWeightCount;
    uint u_denseNanInfCount;
    uint u_interpTotalPixels;
    uint u_interpOccludedCount;
    uint u_interpOutOfBoundsCount;
    uint u_interpNanInfCount;
};

uniform float u_t;
uniform float u_flowScale;
uniform float u_liquidFeel;
uniform float u_shutterGain;
uniform float u_edgeGuard;
uniform int u_collectTelemetry;

// Hardware-Independent Subpixel Bilinear Flow (Bypasses missing Mali FP16 linear filter)
vec2 sampleFlow(sampler2D flowMap, vec2 uv) {
    vec2 sz = vec2(textureSize(flowMap, 0));
    vec2 p = uv * sz - 0.5;
    vec2 frac = fract(p);
    ivec2 i0 = ivec2(floor(p));
    ivec2 mx = ivec2(sz) - 1;

    vec2 a = texelFetch(flowMap, clamp(i0,                ivec2(0), mx), 0).xy;
    vec2 b = texelFetch(flowMap, clamp(i0 + ivec2(1, 0), ivec2(0), mx), 0).xy;
    vec2 c = texelFetch(flowMap, clamp(i0 + ivec2(0, 1), ivec2(0), mx), 0).xy;
    vec2 e = texelFetch(flowMap, clamp(i0 + ivec2(1, 1), ivec2(0), mx), 0).xy;
    return mix(mix(a, b, frac.x), mix(c, e, frac.x), frac.y);
}

void main() {
    ivec2 pix = ivec2(gl_GlobalInvocationID.xy);
    ivec2 sz = imageSize(outImage);
    if (pix.x >= sz.x || pix.y >= sz.y) return;
    vec2 uv = (vec2(pix) + 0.5) / vec2(sz);
    vec2 texel = 1.0 / vec2(sz);

    // Screen Boundary Anchor: Smoothly decays optical flow to zero at physical display boundaries
    float guardPx = mix(8.0, 24.0, clamp(u_edgeGuard, 0.0, 1.0));
    vec2 guard = guardPx * texel;
    vec2 dEdge = min(uv, 1.0 - uv);
    vec2 ramp = clamp(dEdge / max(guard, texel), 0.0, 1.0);
    vec2 easedRamp = ramp * ramp * (3.0 - 2.0 * ramp);
    float edgeMix = min(easedRamp.x, easedRamp.y);

    // Uniform optical flow with boundary anchoring
    vec2 f = sampleFlow(denseFlow, uv) * (u_flowScale > 0.0 ? u_flowScale : 1.0);
    f *= edgeMix; // Fades flow smoothly to 0 at borders -> zero edge bleed at screen borders!

    // Liquid Smooth Motion Pacing: Enhanced Hermite S-curve for ultra-fluid liquid feel
    float smoothT = mix(u_t, u_t * u_t * (3.0 - 2.0 * u_t), clamp(u_liquidFeel, 0.0, 1.0) * 0.40);

    // Bilateral Forward-Backward Warp
    vec2 uv0 = uv - smoothT * f;
    vec2 uv1 = uv + (1.0 - smoothT) * f;

    // Stable, non-ringing hardware bilinear texture sampling
    vec3 c0 = textureLod(prevColor, clamp(uv0, 0.0, 1.0), 0.0).rgb;
    vec3 c1 = textureLod(nextColor, clamp(uv1, 0.0, 1.0), 0.0).rgb;

    // Boundary Feathering: prevents screen-edge stretching
    const float FEATHER_PX = 6.0;
    vec2 feather = FEATHER_PX * texel;
    vec2 e0 = max(max(-uv0, uv0 - 1.0), vec2(0.0)) / feather;
    vec2 e1 = max(max(-uv1, uv1 - 1.0), vec2(0.0)) / feather;
    float out0 = clamp(max(e0.x, e0.y), 0.0, 1.0);
    float out1 = clamp(max(e1.x, e1.y), 0.0, 1.0);

    float w0 = (1.0 - smoothT) * (1.0 - out0);
    float w1 = smoothT * (1.0 - out1);

    // Continuous Photometric Confidence (bionic-fg / FSR 3 smooth synthesis):
    // Compare warped samples with direct scene color to suppress trailing ghosting smoothly
    // without any binary threshold cuts (eliminates edge flickering completely)
    vec3 dir0 = textureLod(prevColor, uv, 0.0).rgb;
    vec3 dir1 = textureLod(nextColor, uv, 0.0).rgb;
    float sim0 = 1.0 / (1.0 + dot(abs(c0 - dir1), vec3(0.299, 0.587, 0.114)) * 3.0);
    float sim1 = 1.0 / (1.0 + dot(abs(c1 - dir0), vec3(0.299, 0.587, 0.114)) * 3.0);

    w0 *= sim0;
    w1 *= sim1;
    float wsum = w0 + w1;

    vec3 result = wsum > 1e-4 ? (c0 * w0 + c1 * w1) / wsum : mix(c0, c1, smoothT);

    if (u_collectTelemetry != 0) {
        float diff = dot(abs(c0 - c1), vec3(0.299, 0.587, 0.114));
        atomicAdd(u_interpTotalPixels, 1u);
        if (diff > 0.20) atomicAdd(u_interpOccludedCount, 1u);
        if (out0 > 0.0 || out1 > 0.0) atomicAdd(u_interpOutOfBoundsCount, 1u);
        if (any(isnan(result)) || any(isinf(result))) atomicAdd(u_interpNanInfCount, 1u);
    }

    imageStore(outImage, pix, vec4(clamp(result, 0.0, 1.0), 1.0));
}
)";

// ---------------------------------------------------------------------------------
// Pass 20: AMD FidelityFX RCAS (Robust Contrast Adaptive Sharpening)
// ---------------------------------------------------------------------------------
static const char* kShaderDisRcas = R"(#version 310 es
precision highp float;
precision highp sampler2D;
precision highp image2D;
layout(local_size_x = 16, local_size_y = 16) in;

layout(binding = 0) uniform sampler2D u_inputTex;
layout(binding = 1, rgba8) writeonly uniform highp image2D u_outImage;

uniform float u_sharpness;

void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    ivec2 sz = imageSize(u_outImage);
    if (p.x >= sz.x || p.y >= sz.y) return;
    ivec2 mx = sz - 1;

    vec3 e = texelFetch(u_inputTex, p, 0).rgb;
    vec3 b = texelFetch(u_inputTex, clamp(p + ivec2( 0,-1), ivec2(0), mx), 0).rgb;
    vec3 d = texelFetch(u_inputTex, clamp(p + ivec2(-1, 0), ivec2(0), mx), 0).rgb;
    vec3 f = texelFetch(u_inputTex, clamp(p + ivec2( 1, 0), ivec2(0), mx), 0).rgb;
    vec3 h = texelFetch(u_inputTex, clamp(p + ivec2( 0, 1), ivec2(0), mx), 0).rgb;

    // Local min / max RGB
    vec3 mn = max(vec3(0.0), min(min(min(d, e), min(f, b)), h));
    vec3 mx_c = min(vec3(1.0), max(max(max(d, e), max(f, b)), h));

    // Contrast Adaptive Weight calculation (AMD FidelityFX RCAS)
    vec3 hit = max(vec3(0.0), min(mn, 1.0 - mx_c));
    vec3 lobe = clamp(-sqrt(hit / (8.0 * mx_c + 1e-4)) * u_sharpness, -0.20, 0.0);

    vec3 sharpened = (b * lobe + d * lobe + f * lobe + h * lobe + e) / (4.0 * lobe + 1.0);
    imageStore(u_outImage, p, vec4(clamp(sharpened, 0.0, 1.0), 1.0));
}
)";

} // namespace apex
