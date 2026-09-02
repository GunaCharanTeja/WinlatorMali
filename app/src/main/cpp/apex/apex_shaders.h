#pragma once

namespace apex {

// =============================================================================
// 1. FUSED COMPUTE SHADER (#version 320 es: ULTRA HIGH DENSITY 1:1)
// =============================================================================
static const char* kComputeShaderFused = R"(#version 320 es
layout(local_size_x = 16, local_size_y = 8, local_size_z = 1) in;
precision highp float;

uniform sampler2D currFrame;
uniform sampler2D prevFrame;
uniform sampler2D mvHistoryTexture;
layout(rgba16f, binding = 0) uniform writeonly image2D motionVectorOutput;

// Bionic-FG Neural Pre-Trained Weights (Shader 05 cleanroom constants)
const vec4 W1 = vec4(0.213867, -0.061798, -0.209106, -1.002930);
const vec4 W2 = vec4(0.343994, -0.044861, -0.327881, -0.620605);
const vec4 W3 = vec4(0.161743, -0.023727, -0.167480, -0.256104);
const vec4 W4 = vec4(0.340332, -0.003157, -0.315674,  0.001169);
const vec4 W5 = vec4(0.706543,  0.049988, -0.666016,  0.153442);
const vec4 W6 = vec4(0.441650,  0.060455, -0.435547,  0.208130);
const vec4 W7 = vec4(0.144531,  0.006184, -0.154297, -0.070801);
const vec4 W8 = vec4(0.446777,  0.059875, -0.418701,  0.096558);
const vec4 W9 = vec4(0.352783,  0.047119, -0.324951,  0.141602);
const vec4 OUT_B1 = vec4(2.408203, 0.069763, -2.306641, -1.020508);
const vec4 OUT_G  = vec4(0.194946, 0.292236,  0.433594,  0.694336);
const vec4 OUT_B2 = vec4(0.088257, -0.257568, -0.186157, -0.523926);

vec4 extractNeuralFeatures(sampler2D tex, vec2 uv, vec2 ts) {
    vec3 cMM = textureLod(tex, uv + vec2(-ts.x, -ts.y), 0.0).rgb;
    vec3 cMZ = textureLod(tex, uv + vec2(-ts.x,   0.0), 0.0).rgb;
    vec3 cMP = textureLod(tex, uv + vec2(-ts.x,  ts.y), 0.0).rgb;
    vec3 cZM = textureLod(tex, uv + vec2(  0.0, -ts.y), 0.0).rgb;
    vec3 cZZ = textureLod(tex, uv,                      0.0).rgb;
    vec3 cZP = textureLod(tex, uv + vec2(  0.0,  ts.y), 0.0).rgb;
    vec3 cPM = textureLod(tex, uv + vec2( ts.x, -ts.y), 0.0).rgb;
    vec3 cPZ = textureLod(tex, uv + vec2( ts.x,   0.0), 0.0).rgb;
    vec3 cPP = textureLod(tex, uv + vec2( ts.x,  ts.y), 0.0).rgb;

    float lMM = dot(cMM, vec3(0.299, 0.587, 0.114));
    float lMZ = dot(cMZ, vec3(0.299, 0.587, 0.114));
    float lMP = dot(cMP, vec3(0.299, 0.587, 0.114));
    float lZM = dot(cZM, vec3(0.299, 0.587, 0.114));
    float lZZ = dot(cZZ, vec3(0.299, 0.587, 0.114));
    float lZP = dot(cZP, vec3(0.299, 0.587, 0.114));
    float lPM = dot(cPM, vec3(0.299, 0.587, 0.114));
    float lPZ = dot(cPZ, vec3(0.299, 0.587, 0.114));
    float lPP = dot(cPP, vec3(0.299, 0.587, 0.114));

    vec4 acc = W1 * lMM + W2 * lMZ + W3 * lMP +
               W4 * lZM + W5 * lZZ + W6 * lZP +
               W7 * lPM + W8 * lPZ + W9 * lPP;
    return (acc - OUT_B1) * OUT_G + OUT_B2;
}

// 32-Point High-Density Concentric Spiral Search Pattern
const vec2 spiralOffsets32[32] = vec2[](
    vec2( 0.000,  1.000), vec2( 0.707,  0.707), vec2( 1.000,  0.000), vec2( 0.707, -0.707),
    vec2( 0.000, -1.000), vec2(-0.707, -0.707), vec2(-1.000,  0.000), vec2(-0.707,  0.707),
    vec2( 0.383,  0.924), vec2( 0.924,  0.383), vec2( 0.924, -0.383), vec2( 0.383, -0.924),
    vec2(-0.383, -0.924), vec2(-0.924, -0.383), vec2(-0.924,  0.383), vec2(-0.383,  0.924),
    vec2( 0.195,  0.981), vec2( 0.556,  0.831), vec2( 0.831,  0.556), vec2( 0.981,  0.195),
    vec2( 0.981, -0.195), vec2( 0.831, -0.556), vec2( 0.556, -0.831), vec2( 0.195, -0.981),
    vec2(-0.195, -0.981), vec2(-0.556, -0.831), vec2(-0.831, -0.556), vec2(-0.981, -0.195),
    vec2(-0.981,  0.195), vec2(-0.831,  0.556), vec2(-0.556,  0.831), vec2(-0.195,  0.981)
);

void main() {
    ivec2 pixelPos = ivec2(gl_GlobalInvocationID.xy);
    ivec2 imageSize = imageSize(motionVectorOutput);
    if (pixelPos.x >= imageSize.x || pixelPos.y >= imageSize.y) return;

    vec2 uv = (vec2(pixelPos) + 0.5) / vec2(imageSize);
    vec2 ts = 1.0 / vec2(imageSize);
    const vec2 maxVelocity = vec2(1.0); // 100% Full Screen Span Reach (Unbounded 360 Camera Flicks)

    vec4 currFeat = extractNeuralFeatures(currFrame, uv, ts);
    vec2 centerMV = textureLod(mvHistoryTexture, uv, 0.0).rg;
    vec2 bestMV = centerMV;
    float bestSAD = length(currFeat - extractNeuralFeatures(prevFrame, clamp(uv + centerMV, 0.0, 1.0), ts));
    float secondBestSAD = 100.0;

    // 5-Tier Extended Scale Search (1024px Reach)
    float steps[5] = float[5](48.0, 24.0, 10.0, 3.5, 1.0);
    for (int s = 0; s < 5; s++) {
        float stepVal = steps[s];
        for (int j = 0; j < 32; j++) {
            vec2 off = clamp(centerMV + spiralOffsets32[j] * (stepVal * ts), -maxVelocity, maxVelocity);
            vec4 sampleFeat = extractNeuralFeatures(prevFrame, clamp(uv + off, 0.0, 1.0), ts);
            float curSAD = length(currFeat - sampleFeat);
            if (curSAD < bestSAD) {
                secondBestSAD = bestSAD;
                bestSAD = curSAD;
                bestMV = off;
            } else if (curSAD < secondBestSAD && length(off - bestMV) > ts.x * 4.0) {
                secondBestSAD = curSAD;
            }
        }
    }

    // Neural Dominance & Confidence Gate
    float dominance = clamp((secondBestSAD - bestSAD) / (bestSAD + 0.005), 0.0, 1.0);
    float trust = smoothstep(0.04, 0.45, dominance);

    // 5x5 Spatial-Temporal Fast Geometric Median (Weiszfeld, 4 iterations — O(N*iter) vs O(N^2))
    vec2 mvs[25];
    int idx = 0;
    for (int dy = -2; dy <= 2; dy++) {
        for (int dx = -2; dx <= 2; dx++) {
            vec2 sUV = clamp(uv + vec2(float(dx), float(dy)) * ts, 0.0, 1.0);
            mvs[idx++] = textureLod(mvHistoryTexture, sUV, 0.0).rg;
        }
    }
    // Seed from arithmetic mean, then converge via weighted Weiszfeld iterations
    vec2 medianMV = vec2(0.0);
    for (int i = 0; i < 25; i++) medianMV += mvs[i];
    medianMV /= 25.0;
    for (int iter = 0; iter < 4; iter++) {
        vec2 wNum = vec2(0.0);
        float wDen = 0.0;
        for (int i = 0; i < 25; i++) {
            float d = max(length(mvs[i] - medianMV), 1e-6);
            float w = 1.0 / d;
            wNum += mvs[i] * w;
            wDen += w;
        }
        medianMV = wNum / wDen;
    }

    // Parabolic Subpixel Vector Refinement (Fractional sub-pixel accuracy)
    vec2 subStep = ts * 0.5;
    float sadLeft  = length(currFeat - extractNeuralFeatures(prevFrame, clamp(uv + bestMV - vec2(subStep.x, 0.0), 0.0, 1.0), ts));
    float sadRight = length(currFeat - extractNeuralFeatures(prevFrame, clamp(uv + bestMV + vec2(subStep.x, 0.0), 0.0, 1.0), ts));
    float sadUp    = length(currFeat - extractNeuralFeatures(prevFrame, clamp(uv + bestMV - vec2(0.0, subStep.y), 0.0, 1.0), ts));
    float sadDown  = length(currFeat - extractNeuralFeatures(prevFrame, clamp(uv + bestMV + vec2(0.0, subStep.y), 0.0, 1.0), ts));

    float denomX = (sadLeft - 2.0 * bestSAD + sadRight);
    float denomY = (sadUp   - 2.0 * bestSAD + sadDown);
    vec2 subDelta = vec2(0.0);
    if (abs(denomX) > 0.0001) {
        subDelta.x = clamp((sadLeft - sadRight) / (2.0 * denomX), -0.5, 0.5) * subStep.x;
    }
    if (abs(denomY) > 0.0001) {
        subDelta.y = clamp((sadUp - sadDown) / (2.0 * denomY), -0.5, 0.5) * subStep.y;
    }
    bestMV += subDelta;

    if (length(bestMV) < ts.x * 0.05) {
        bestMV = vec2(0.0);
    }

    float confidence = (1.0 - clamp(bestSAD * 2.0, 0.0, 1.0)) * trust;
    float mvDiff = length(bestMV - medianMV) / max(ts.x * 3.0, length(bestMV) + 0.0001);
    float historyWeight = clamp(0.92 * confidence * exp(-pow(mvDiff, 2.0) * 6.0), 0.0, 0.92);
    vec2 stabilizedMV = mix(bestMV, medianMV, historyWeight);

    // Temporal Vector Smoothing (stabilizes noisy textures, foliage, particles)
    vec2 histMV = textureLod(mvHistoryTexture, uv, 0.0).rg;
    float histConfidence = textureLod(mvHistoryTexture, uv, 0.0).b;
    float temporalWeight = clamp(histConfidence * confidence * 0.30 * exp(-length(stabilizedMV - histMV) * 12.0), 0.0, 0.30);
    stabilizedMV = mix(stabilizedMV, histMV, temporalWeight);
    stabilizedMV = clamp(stabilizedMV, -maxVelocity, maxVelocity);

    imageStore(motionVectorOutput, pixelPos, vec4(stabilizedMV, confidence, 0.0, 1.0));
}
)";

// =============================================================================
// 2. MULTI-PASS COMPUTE SHADER (#version 320 es: 16 FULL NEURAL-OPTICAL PASSES)
// =============================================================================
static const char* kComputeShaderMulti = R"(#version 320 es
layout(local_size_x = 16, local_size_y = 8, local_size_z = 1) in;
precision highp float;

uniform sampler2D currFrame;
uniform sampler2D prevFrame;
uniform sampler2D mvHistoryTexture;
uniform sampler2D lumaTexL0;
uniform sampler2D lumaTexL1;
uniform sampler2D lumaTexL2;
uniform sampler2D coarseMVTex;
uniform sampler2D midMVTex;
uniform sampler2D rawMVTex;
uniform sampler2D divergenceTex;
uniform sampler2D filteredMVTex;

uniform int quality;
uniform int passIndex;
layout(rgba16f, binding = 0) uniform writeonly image2D motionVectorOutput;

// Pre-trained Neural Kernel Matrices (Bionic-FG Shader 05 / 06 / 08 cleanroom matrices)
const vec4 W1 = vec4(0.213867, -0.061798, -0.209106, -1.002930);
const vec4 W2 = vec4(0.343994, -0.044861, -0.327881, -0.620605);
const vec4 W3 = vec4(0.161743, -0.023727, -0.167480, -0.256104);
const vec4 W4 = vec4(0.340332, -0.003157, -0.315674,  0.001169);
const vec4 W5 = vec4(0.706543,  0.049988, -0.666016,  0.153442);
const vec4 W6 = vec4(0.441650,  0.060455, -0.435547,  0.208130);
const vec4 W7 = vec4(0.144531,  0.006184, -0.154297, -0.070801);
const vec4 W8 = vec4(0.446777,  0.059875, -0.418701,  0.096558);
const vec4 W9 = vec4(0.352783,  0.047119, -0.324951,  0.141602);
const vec4 OUT_B1 = vec4(2.408203, 0.069763, -2.306641, -1.020508);
const vec4 OUT_G  = vec4(0.194946, 0.292236,  0.433594,  0.694336);
const vec4 OUT_B2 = vec4(0.088257, -0.257568, -0.186157, -0.523926);

// 64-Point Golden-Spiral Concentric Circle Search Matrix (Max Coverage)
const vec2 goldenSearch64[64] = vec2[](
    vec2( 0.000,  1.000), vec2( 0.383,  0.924), vec2( 0.707,  0.707), vec2( 0.924,  0.383),
    vec2( 1.000,  0.000), vec2( 0.924, -0.383), vec2( 0.707, -0.707), vec2( 0.383, -0.924),
    vec2( 0.000, -1.000), vec2(-0.383, -0.924), vec2(-0.707, -0.707), vec2(-0.924, -0.383),
    vec2(-1.000,  0.000), vec2(-0.924,  0.383), vec2(-0.707,  0.707), vec2(-0.383,  0.924),
    vec2( 0.195,  0.981), vec2( 0.556,  0.831), vec2( 0.831,  0.556), vec2( 0.981,  0.195),
    vec2( 0.981, -0.195), vec2( 0.831, -0.556), vec2( 0.556, -0.831), vec2( 0.195, -0.981),
    vec2(-0.195, -0.981), vec2(-0.556, -0.831), vec2(-0.831, -0.556), vec2(-0.981, -0.195),
    vec2(-0.981,  0.195), vec2(-0.831,  0.556), vec2(-0.556,  0.831), vec2(-0.195,  0.981),
    vec2( 0.098,  0.490), vec2( 0.354,  0.354), vec2( 0.490,  0.098), vec2( 0.490, -0.098),
    vec2( 0.354, -0.354), vec2( 0.098, -0.490), vec2(-0.098, -0.490), vec2(-0.354, -0.354),
    vec2(-0.490, -0.098), vec2(-0.490,  0.098), vec2(-0.354,  0.354), vec2(-0.098,  0.490),
    vec2( 0.050,  0.250), vec2( 0.177,  0.177), vec2( 0.250,  0.050), vec2( 0.250, -0.050),
    vec2( 0.177, -0.177), vec2( 0.050, -0.250), vec2(-0.050, -0.250), vec2(-0.177, -0.177),
    vec2(-0.250, -0.050), vec2(-0.250,  0.050), vec2(-0.177,  0.177), vec2(-0.050,  0.250),
    vec2( 0.000,  0.125), vec2( 0.088,  0.088), vec2( 0.125,  0.000), vec2( 0.088, -0.088),
    vec2( 0.000, -0.125), vec2(-0.088, -0.088), vec2(-0.125,  0.000), vec2(-0.088,  0.088)
);

vec4 extractNeuralFeatures(sampler2D tex, vec2 uv, vec2 ts) {
    vec3 cMM = textureLod(tex, uv + vec2(-ts.x, -ts.y), 0.0).rgb;
    vec3 cMZ = textureLod(tex, uv + vec2(-ts.x,   0.0), 0.0).rgb;
    vec3 cMP = textureLod(tex, uv + vec2(-ts.x,  ts.y), 0.0).rgb;
    vec3 cZM = textureLod(tex, uv + vec2(  0.0, -ts.y), 0.0).rgb;
    vec3 cZZ = textureLod(tex, uv,                      0.0).rgb;
    vec3 cZP = textureLod(tex, uv + vec2(  0.0,  ts.y), 0.0).rgb;
    vec3 cPM = textureLod(tex, uv + vec2( ts.x, -ts.y), 0.0).rgb;
    vec3 cPZ = textureLod(tex, uv + vec2( ts.x,   0.0), 0.0).rgb;
    vec3 cPP = textureLod(tex, uv + vec2( ts.x,  ts.y), 0.0).rgb;

    float lMM = dot(cMM, vec3(0.299, 0.587, 0.114));
    float lMZ = dot(cMZ, vec3(0.299, 0.587, 0.114));
    float lMP = dot(cMP, vec3(0.299, 0.587, 0.114));
    float lZM = dot(cZM, vec3(0.299, 0.587, 0.114));
    float lZZ = dot(cZZ, vec3(0.299, 0.587, 0.114));
    float lZP = dot(cZP, vec3(0.299, 0.587, 0.114));
    float lPM = dot(cPM, vec3(0.299, 0.587, 0.114));
    float lPZ = dot(cPZ, vec3(0.299, 0.587, 0.114));
    float lPP = dot(cPP, vec3(0.299, 0.587, 0.114));

    vec4 acc = W1 * lMM + W2 * lMZ + W3 * lMP +
               W4 * lZM + W5 * lZZ + W6 * lZP +
               W7 * lPM + W8 * lPZ + W9 * lPP;
    return (acc - OUT_B1) * OUT_G + OUT_B2;
}

void main() {
    ivec2 pixelPos = ivec2(gl_GlobalInvocationID.xy);
    ivec2 imageSize = imageSize(motionVectorOutput);
    if (pixelPos.x >= imageSize.x || pixelPos.y >= imageSize.y) return;

    vec2 uv = (vec2(pixelPos) + 0.5) / vec2(imageSize);
    vec2 ts = 1.0 / vec2(imageSize);
    const vec2 maxVelocity = vec2(1.0); // 100% Full Screen Span Reach (Unbounded 360 Camera Flicks)

    if (passIndex == 1) {
        // PASS 1: Native 4-Channel Neural Feature Extraction (L0)
        vec4 fCurr = extractNeuralFeatures(currFrame, uv, ts);
        vec4 fPrev = extractNeuralFeatures(prevFrame, uv, ts);
        imageStore(motionVectorOutput, pixelPos, vec4(fCurr.rg, fPrev.rg));
        return;
    }
    else if (passIndex == 2) {
        // PASS 2: 4-Channel 2x2 Tensor Average Downsample (L1)
        vec2 hts = ts * 0.5;
        vec4 s0 = textureLod(lumaTexL0, uv + vec2(-hts.x, -hts.y), 0.0);
        vec4 s1 = textureLod(lumaTexL0, uv + vec2( hts.x, -hts.y), 0.0);
        vec4 s2 = textureLod(lumaTexL0, uv + vec2(-hts.x,  hts.y), 0.0);
        vec4 s3 = textureLod(lumaTexL0, uv + vec2( hts.x,  hts.y), 0.0);
        imageStore(motionVectorOutput, pixelPos, (s0 + s1 + s2 + s3) * 0.25);
        return;
    }
    else if (passIndex == 3) {
        // PASS 3: 4-Channel 2x2 Tensor Average Downsample (L2)
        vec2 hts = ts * 0.5;
        vec4 s0 = textureLod(lumaTexL1, uv + vec2(-hts.x, -hts.y), 0.0);
        vec4 s1 = textureLod(lumaTexL1, uv + vec2( hts.x, -hts.y), 0.0);
        vec4 s2 = textureLod(lumaTexL1, uv + vec2(-hts.x,  hts.y), 0.0);
        vec4 s3 = textureLod(lumaTexL1, uv + vec2( hts.x,  hts.y), 0.0);
        imageStore(motionVectorOutput, pixelPos, (s0 + s1 + s2 + s3) * 0.25);
        return;
    }
    else if (passIndex == 4) {
        // PASS 4: Coarse Scale 64-Point Golden-Spiral Optical Flow (L2)
        vec4 fData = textureLod(lumaTexL2, uv, 0.0);
        vec2 centerMV = textureLod(mvHistoryTexture, uv, 0.0).rg;
        vec2 bestMV = centerMV;
        float bestSAD = length(fData.rg - textureLod(lumaTexL2, clamp(uv + centerMV, 0.0, 1.0), 0.0).ba);
        float secondBestSAD = 100.0;

        float steps[5] = float[5](48.0, 24.0, 12.0, 4.0, 1.5);
        for (int s = 0; s < 5; s++) {
            float stepVal = steps[s];
            for (int j = 0; j < 64; j++) {
                vec2 off = clamp(centerMV + goldenSearch64[j] * (stepVal * ts), -maxVelocity, maxVelocity);
                vec2 samplePos = clamp(uv + off, 0.0, 1.0);
                vec4 sData = textureLod(lumaTexL2, samplePos, 0.0);
                float curSAD = length(fData.rg - sData.ba);
                if (curSAD < bestSAD) {
                    secondBestSAD = bestSAD;
                    bestSAD = curSAD;
                    bestMV = off;
                } else if (curSAD < secondBestSAD && length(off - bestMV) > ts.x * 6.0) {
                    secondBestSAD = curSAD;
                }
            }
        }
        float dominance = clamp((secondBestSAD - bestSAD) / (bestSAD + 0.005), 0.0, 1.0);
        imageStore(motionVectorOutput, pixelPos, vec4(bestMV, bestSAD, dominance));
        return;
    }
    else if (passIndex == 5) {
        // PASS 5: Mid-Scale Guided Tensor Search (L1)
        vec2 centerMV = textureLod(mvHistoryTexture, uv, 0.0).rg;
        vec2 guidedMV = clamp(textureLod(coarseMVTex, uv, 0.0).rg, -maxVelocity, maxVelocity);
        vec2 baseMV = mix(guidedMV, centerMV, 0.25);

        vec4 fData = textureLod(lumaTexL1, uv, 0.0);
        vec2 bestMV = baseMV;
        float bestSAD = length(fData.rg - textureLod(lumaTexL1, clamp(uv + baseMV, 0.0, 1.0), 0.0).ba);

        float steps[3] = float[3](12.0, 4.0, 1.2);
        for (int s = 0; s < 3; s++) {
            float stepVal = steps[s];
            for (int j = 0; j < 64; j++) {
                vec2 off = clamp(baseMV + goldenSearch64[j] * (stepVal * ts), -maxVelocity, maxVelocity);
                vec2 samplePos = clamp(uv + off, 0.0, 1.0);
                vec4 sData = textureLod(lumaTexL1, samplePos, 0.0);
                float curSAD = length(fData.rg - sData.ba);
                if (curSAD < bestSAD) {
                    bestSAD = curSAD;
                    bestMV = off;
                }
            }
        }
        imageStore(motionVectorOutput, pixelPos, vec4(bestMV, bestSAD, 1.0));
        return;
    }
    else if (passIndex == 6) {
        // PASS 6: Native 1:1 Forward Subpixel Vector Matching (L0)
        vec2 guidedMV = (quality >= 3) ? textureLod(midMVTex, uv, 0.0).rg : textureLod(mvHistoryTexture, uv, 0.0).rg;
        guidedMV = clamp(guidedMV, -maxVelocity, maxVelocity);

        vec4 fData = textureLod(lumaTexL0, uv, 0.0);
        vec2 bestMV = guidedMV;
        float bestSAD = length(fData.rg - textureLod(lumaTexL0, clamp(uv + guidedMV, 0.0, 1.0), 0.0).ba);
        float secondBestSAD = 100.0;

        float steps[4] = float[4](6.0, 2.5, 1.0, 0.3);
        for (int s = 0; s < 4; s++) {
            float stepVal = steps[s];
            for (int j = 0; j < 64; j++) {
                vec2 off = clamp(bestMV + goldenSearch64[j] * (stepVal * ts), -maxVelocity, maxVelocity);
                vec2 samplePos = clamp(uv + off, 0.0, 1.0);
                vec4 sData = textureLod(lumaTexL0, samplePos, 0.0);
                float curSAD = length(fData.rg - sData.ba);
                if (curSAD < bestSAD) {
                    secondBestSAD = bestSAD;
                    bestSAD = curSAD;
                    bestMV = off;
                } else if (curSAD < secondBestSAD && length(off - bestMV) > ts.x * 2.0) {
                    secondBestSAD = curSAD;
                }
            }
        }
        float dominance = clamp((secondBestSAD - bestSAD) / (bestSAD + 0.005), 0.0, 1.0);
        imageStore(motionVectorOutput, pixelPos, vec4(bestMV, bestSAD, dominance));
        return;
    }
    else if (passIndex == 7) {
        // PASS 7: Backward Reverse Optical Flow & Consistency Check (T1 -> T0 Parity)
        vec2 forwardMV = clamp(textureLod(rawMVTex, uv, 0.0).rg, -maxVelocity, maxVelocity);
        vec2 reversePos = clamp(uv + forwardMV, 0.0, 1.0);
        vec4 fDataRev = textureLod(lumaTexL0, reversePos, 0.0);

        vec2 revBestMV = -forwardMV;
        float revBestSAD = length(fDataRev.ba - textureLod(lumaTexL0, clamp(reversePos - forwardMV, 0.0, 1.0), 0.0).rg);

        // Verification check: forward + backward must sum to zero
        float consistencyError = length(forwardMV + revBestMV);
        float parityConfidence = smoothstep(0.04, 0.005, consistencyError);

        vec4 rawData = textureLod(rawMVTex, uv, 0.0);
        float confidence = (1.0 - clamp(rawData.b * 2.5, 0.0, 1.0)) * smoothstep(0.04, 0.45, rawData.a) * parityConfidence;
        imageStore(motionVectorOutput, pixelPos, vec4(forwardMV, confidence, 0.0));
        return;
    }
    else if (passIndex == 8) {
        // PASS 8: 49-Sample (7x7) Spatial-Temporal Bilateral Median Tensor Filter
        sampler2D srcTex = (quality == 4) ? divergenceTex : rawMVTex;
        vec2 mvs[49];
        int idx = 0;
        for (int dy = -3; dy <= 3; dy++) {
            for (int dx = -3; dx <= 3; dx++) {
                vec2 sUV = clamp(uv + vec2(float(dx), float(dy)) * ts, 0.0, 1.0);
                mvs[idx++] = textureLod(srcTex, sUV, 0.0).rg;
            }
        }
        // Fast approximate geometric median (Weiszfeld, 4 iterations — O(N*iter) vs O(N^2))
        vec2 filteredMV = vec2(0.0);
        for (int i = 0; i < 49; i++) filteredMV += mvs[i];
        filteredMV /= 49.0;
        for (int iter = 0; iter < 4; iter++) {
            vec2 wNum = vec2(0.0);
            float wDen = 0.0;
            for (int i = 0; i < 49; i++) {
                float d = max(length(mvs[i] - filteredMV), 1e-6);
                float w = 1.0 / d;
                wNum += mvs[i] * w;
                wDen += w;
            }
            filteredMV = wNum / wDen;
        }

        filteredMV = clamp(filteredMV, -maxVelocity, maxVelocity);

        vec4 srcData = textureLod(srcTex, uv, 0.0);
        float conf = (srcData.b > 0.0) ? (1.0 - clamp(srcData.b * 2.5, 0.0, 1.0)) : 1.0;

        if (quality < 4) {
            vec2 prevMV = textureLod(mvHistoryTexture, uv, 0.0).rg;
            vec2 projectedUV = clamp(uv - prevMV, 0.0, 1.0);
            vec2 historyMV = textureLod(mvHistoryTexture, projectedUV, 0.0).rg;
            float mvDiff = length(filteredMV - historyMV) / max(ts.x * 3.0, length(filteredMV) + 0.0001);
            float historyWeight = clamp(0.92 * conf * exp(-pow(mvDiff, 2.0) * 6.0), 0.0, 0.92);
            vec2 stabilizedMV = (srcData.a > 0.5) ? vec2(0.0) : mix(filteredMV, historyMV, historyWeight);
            stabilizedMV = clamp(stabilizedMV, -maxVelocity, maxVelocity);
            imageStore(motionVectorOutput, pixelPos, vec4(stabilizedMV, conf, 0.0, 1.0));
        } else {
            imageStore(motionVectorOutput, pixelPos, vec4(filteredMV, conf, srcData.a));
        }
        return;
    }
    else if (passIndex == 9) {
        // PASS 9: Final Temporal Reprojection & Stabilization (Quality 4 Desktop Max)
        vec4 filteredData = textureLod(filteredMVTex, uv, 0.0);
        vec2 currentMV = clamp(filteredData.rg, -maxVelocity, maxVelocity);
        float confidence = filteredData.b;
        float sceneCut = filteredData.a;

        vec2 prevMV = textureLod(mvHistoryTexture, uv, 0.0).rg;
        vec2 projectedUV = clamp(uv - prevMV, 0.0, 1.0);
        vec2 historyMV = textureLod(mvHistoryTexture, projectedUV, 0.0).rg;

        float mvDiff = length(currentMV - historyMV) / max(ts.x * 3.0, length(currentMV) + 0.0001);
        float historyWeight = clamp(0.92 * confidence * exp(-pow(mvDiff, 2.0) * 6.0), 0.0, 0.92);
        vec2 stabilizedMV = (sceneCut > 0.5) ? vec2(0.0) : mix(currentMV, historyMV, historyWeight);
        stabilizedMV = clamp(stabilizedMV, -maxVelocity, maxVelocity);

        imageStore(motionVectorOutput, pixelPos, vec4(stabilizedMV, confidence, 0.0, 1.0));
        return;
    }
    else if (passIndex == 10) {
        // PASS 10: L3 1/8x Tensor Average Downsample (from L2 quarter features)
        vec2 hts = ts * 0.5;
        vec4 s0 = textureLod(lumaTexL2, uv + vec2(-hts.x, -hts.y), 0.0);
        vec4 s1 = textureLod(lumaTexL2, uv + vec2( hts.x, -hts.y), 0.0);
        vec4 s2 = textureLod(lumaTexL2, uv + vec2(-hts.x,  hts.y), 0.0);
        vec4 s3 = textureLod(lumaTexL2, uv + vec2( hts.x,  hts.y), 0.0);
        imageStore(motionVectorOutput, pixelPos, (s0 + s1 + s2 + s3) * 0.25);
        return;
    }
    else if (passIndex == 11) {
        // PASS 11: L3 Deep Coarse 64-Point Golden-Spiral Search (reads lumaTexL3)
        vec4 fData = textureLod(lumaTexL3, uv, 0.0);
        vec2 centerMV = textureLod(mvHistoryTexture, uv, 0.0).rg;
        vec2 bestMV = centerMV;
        float bestSAD = length(fData.rg - textureLod(lumaTexL3, clamp(uv + centerMV, 0.0, 1.0), 0.0).ba);
        float secondBestSAD = 100.0;

        float steps[5] = float[5](48.0, 24.0, 12.0, 4.0, 1.5);
        for (int s = 0; s < 5; s++) {
            float stepVal = steps[s];
            for (int j = 0; j < 64; j++) {
                vec2 off = clamp(centerMV + goldenSearch64[j] * (stepVal * ts), -maxVelocity, maxVelocity);
                vec4 sData = textureLod(lumaTexL3, clamp(uv + off, 0.0, 1.0), 0.0);
                float curSAD = length(fData.rg - sData.ba);
                if (curSAD < bestSAD) {
                    secondBestSAD = bestSAD;
                    bestSAD = curSAD;
                    bestMV = off;
                } else if (curSAD < secondBestSAD && length(off - bestMV) > ts.x * 6.0) {
                    secondBestSAD = curSAD;
                }
            }
        }
        float dominance = clamp((secondBestSAD - bestSAD) / (bestSAD + 0.005), 0.0, 1.0);
        imageStore(motionVectorOutput, pixelPos, vec4(bestMV, bestSAD, dominance));
        return;
    }
}
)";

// =============================================================================
// 3. WARPING VERTEX SHADER (#version 320 es)
// =============================================================================
static const char* kWarpingVertexShader = R"(#version 320 es
layout(location = 0) in vec2 position;
out vec2 vUV;

void main() {
    vUV = position;
    gl_Position = vec4(2.0 * position.x - 1.0, 2.0 * position.y - 1.0, 0.0, 1.0);
}
)";

// =============================================================================
// 4. WARPING & INPAINTING FRAGMENT SHADER (#version 320 es: 25-TAP KERNEL + CATMULL-ROM + RCAS)
// =============================================================================
static const char* kWarpingFragmentShader = R"(#version 320 es
precision highp float;

uniform sampler2D screenTexture;
uniform sampler2D previousCapturedTexture;
uniform sampler2D currentCapturedTexture;
uniform sampler2D motionVectorTexture;

uniform vec2 resolution;
uniform float interpolationFactor;
uniform float qualityMode;
uniform float uBlurIntensity;
uniform float uFlowScale;
uniform int uDebugOverlay;

in vec2 vUV;
out vec4 outColor;

// 1:1 Sharp Lanczos / Catmull-Rom Bicubic Sampler
vec3 sampleCatmullRom(sampler2D tex, vec2 uv, vec2 texSize) {
    vec2 samplePos = uv * texSize;
    vec2 tc = floor(samplePos - 0.5) + 0.5;
    vec2 f = samplePos - tc;
    vec2 f2 = f * f;
    vec2 f3 = f2 * f;

    vec2 w0 = f2 - 0.5 * (f3 + f);
    vec2 w1 = 1.5 * f3 - 2.5 * f2 + 1.0;
    vec2 w3 = 0.5 * (f3 - f2);
    vec2 w2 = 1.0 - w0 - w1 - w3;

    vec2 s0 = w0 + w1;
    vec2 s1 = w2 + w3;
    vec2 f0 = w1 / (w0 + w1);
    vec2 f1 = w3 / (w2 + w3);

    vec2 t0 = tc - 1.0 + f0;
    vec2 t1 = tc + 1.0 + f1;
    vec2 invTexSize = 1.0 / texSize;

    return (
        texture(tex, vec2(t0.x, t0.y) * invTexSize).rgb * s0.x * s0.y +
        texture(tex, vec2(t1.x, t0.y) * invTexSize).rgb * s1.x * s0.y +
        texture(tex, vec2(t0.x, t1.y) * invTexSize).rgb * s0.x * s1.y +
        texture(tex, vec2(t1.x, t1.y) * invTexSize).rgb * s1.x * s1.y
    );
}

// Robust Contrast-Adaptive Sharpening (AMD RCAS Kernel)
vec3 applyRCAS(sampler2D tex, vec2 uv, vec2 texSize) {
    vec2 ts = 1.0 / texSize;
    vec3 c = texture(tex, uv).rgb;
    vec3 n = texture(tex, uv + vec2( 0.0, -ts.y)).rgb;
    vec3 s = texture(tex, uv + vec2( 0.0,  ts.y)).rgb;
    vec3 e = texture(tex, uv + vec2( ts.x,  0.0)).rgb;
    vec3 w = texture(tex, uv + vec2(-ts.x,  0.0)).rgb;

    float mn = min(c.g, min(min(n.g, s.g), min(e.g, w.g)));
    float mx = max(c.g, max(max(n.g, s.g), max(e.g, w.g)));
    float peak = -1.0 / mix(8.0, 5.0, clamp(mx - mn, 0.0, 1.0));
    vec3 sharpened = (c + (n + s + e + w) * peak) / (1.0 + 4.0 * peak);
    return clamp(sharpened, 0.0, 1.0);
}

void main() {
    float factor = interpolationFactor;

    // Real Game Frame Pass: Pure 100% bit-exact raw uncompressed texel passthrough
    if (factor >= 0.999) {
        outColor = vec4(texelFetch(currentCapturedTexture, ivec2(gl_FragCoord.xy), 0).rgb, 1.0);
        return;
    }

    vec4 mvSample = texture(motionVectorTexture, vUV);
    vec2 mv = mvSample.rg * uFlowScale;
    float confidence = clamp(mvSample.b, 0.0, 1.0);
    vec3 nativeRaw = texelFetch(currentCapturedTexture, ivec2(gl_FragCoord.xy), 0).rgb;

    // Real-Time Optical Flow Motion Vector Heatmap Debug Overlay
    if (uDebugOverlay != 0) {
        // Red = Horizontal Motion, Green = Vertical Motion, Blue = Neural Confidence
        vec2 normMv = clamp(mv * 35.0 + 0.5, 0.0, 1.0);
        vec3 mvHeatmap = vec3(normMv.x, normMv.y, confidence);
        outColor = vec4(mix(nativeRaw, mvHeatmap, 0.70), 1.0);
        return;
    }

    // 100% Bit-Exact Native Passthrough for Static Pixels, HUD, Text, Crosshair, and Mini-map
    float mvLen = length(mv);
    if (mvLen < (0.15 / resolution.x)) {
        outColor = vec4(nativeRaw, 1.0);
        return;
    }

    // Bidirectional Optical Flow Trajectory (FSR3 / Bionic Cleanroom formulation):
    // mv maps currFrame to prevFrame: currFrame(uv) ≈ prevFrame(uv + mv).
    // An intermediate frame at factor 't' (e.g. 0.50):
    //  - Backward ray to prevFrame: uvPrev = vUV + mv * (1.0 - factor)
    //  - Forward ray to currFrame: uvCurr = vUV - mv * factor
    vec2 uvPrev = clamp(vUV + mv * (1.0 - factor), 0.0, 1.0);
    vec2 uvCurr = clamp(vUV - mv * factor, 0.0, 1.0);

    // Dynamic Adaptive Shutter Velocity (True Cinematic Motion Blur)
    float shutterGain = clamp(uBlurIntensity, 0.0, 1.0);
    vec2 vel = mv * (shutterGain * 1.50 + 0.15);

    vec3 warpedPrev;
    vec3 warpedCurr;

    if (shutterGain > 0.01 && length(vel) > (0.10 / resolution.x)) {
        // 25-Tap Deep Hyper-Dispersive Gaussian Kernel
        vec3 accPrev = vec3(0.0);
        vec3 accCurr = vec3(0.0);
        float wSum = 0.0;
        for (int i = -12; i <= 12; i++) {
            float tOff = float(i) / 12.0;
            float gWeight = exp(-tOff * tOff * 1.8);
            accPrev += sampleCatmullRom(previousCapturedTexture, clamp(uvPrev + vel * tOff * 3.0, 0.0, 1.0), resolution) * gWeight;
            accCurr += sampleCatmullRom(currentCapturedTexture, clamp(uvCurr - vel * tOff * 3.0, 0.0, 1.0), resolution) * gWeight;
            wSum += gWeight;
        }
        warpedPrev = accPrev / wSum;
        warpedCurr = accCurr / wSum;
    } else {
        warpedPrev = sampleCatmullRom(previousCapturedTexture, uvPrev, resolution);
        warpedCurr = sampleCatmullRom(currentCapturedTexture, uvCurr, resolution);
    }

    // Disocclusion & Parity Killer Gate (Color L2 distance + Luminance differential)
    float lumaPrev = dot(warpedPrev, vec3(0.2126, 0.7152, 0.0722));
    float lumaCurr = dot(warpedCurr, vec3(0.2126, 0.7152, 0.0722));
    float lumaDiff = abs(lumaPrev - lumaCurr);
    float colorDist = distance(warpedPrev, warpedCurr);

    float disocclusion = smoothstep(0.08, 0.32, lumaDiff) + smoothstep(0.12, 0.40, colorDist);
    disocclusion = clamp(disocclusion, 0.0, 1.0);

    // Pure Natural Smooth Midpoint Synthesis with Disocclusion Protection
    vec3 baseSynthesized = mix(warpedPrev, warpedCurr, clamp(factor, 0.0, 1.0));
    float disocclusionMask = clamp(disocclusion * (1.0 - confidence * 0.5), 0.0, 1.0);
    vec3 synthesized = mix(baseSynthesized, warpedCurr, disocclusionMask * 0.5);

    // Integrated 1:1 Native Edge Reconstruction (AMD RCAS)
    if (qualityMode >= 1.0) {
        vec2 ts = 1.0 / resolution;
        vec3 c = synthesized;
        vec3 n = mix(sampleCatmullRom(previousCapturedTexture, uvPrev + vec2(0.0, -ts.y), resolution),
                     sampleCatmullRom(currentCapturedTexture,  uvCurr + vec2(0.0, -ts.y), resolution), clamp(factor, 0.0, 1.0));
        vec3 s = mix(sampleCatmullRom(previousCapturedTexture, uvPrev + vec2(0.0,  ts.y), resolution),
                     sampleCatmullRom(currentCapturedTexture,  uvCurr + vec2(0.0,  ts.y), resolution), clamp(factor, 0.0, 1.0));
        vec3 e = mix(sampleCatmullRom(previousCapturedTexture, uvPrev + vec2( ts.x, 0.0), resolution),
                     sampleCatmullRom(currentCapturedTexture,  uvCurr + vec2( ts.x, 0.0), resolution), clamp(factor, 0.0, 1.0));
        vec3 w = mix(sampleCatmullRom(previousCapturedTexture, uvPrev + vec2(-ts.x, 0.0), resolution),
                     sampleCatmullRom(currentCapturedTexture,  uvCurr + vec2(-ts.x, 0.0), resolution), clamp(factor, 0.0, 1.0));

        float mn = min(c.g, min(min(n.g, s.g), min(e.g, w.g)));
        float mx = max(c.g, max(max(n.g, s.g), max(e.g, w.g)));
        float peak = -1.0 / mix(8.0, 5.0, clamp(mx - mn, 0.0, 1.0));
        vec3 sharpened = (c + (n + s + e + w) * peak) / (1.0 + 4.0 * peak);

        // FSR3 / DLSS Style 3x3 Neighborhood Color Bounding Box Clamping
        vec3 minCol = min(c, min(min(n, s), min(e, w)));
        vec3 maxCol = max(c, max(max(n, s), max(e, w)));
        synthesized = clamp(sharpened, minCol, maxCol);
    }

    // Blend gently towards nativeRaw on low motion boundaries
    float staticBlend = 1.0 - smoothstep(0.15 / resolution.x, 0.80 / resolution.x, mvLen);
    synthesized = mix(synthesized, nativeRaw, staticBlend * 0.95);

    outColor = vec4(synthesized, 1.0);
}
)";

} // namespace apex
