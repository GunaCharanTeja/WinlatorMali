#pragma once

namespace apex {

// =============================================================================
// 1. FUSED SINGLE-PASS COMPUTE SHADER (PRESET 0: ULTRA PERFORMANCE)
// =============================================================================
static const char* kComputeShaderFused = R"(#version 310 es
layout(local_size_x = 16, local_size_y = 8, local_size_z = 1) in;
precision highp float;

uniform sampler2D currFrame;
uniform sampler2D prevFrame;
uniform sampler2D mvHistoryTexture;
layout(rgba16f, binding = 0) uniform writeonly image2D motionVectorOutput;

float getPerceptualLuma(vec3 c) {
    return sqrt(clamp(dot(c, vec3(0.2126, 0.7152, 0.0722)), 0.0, 1.0));
}

const vec2 diamondOffsets8[8] = vec2[](
    vec2( 0.0,  1.0), vec2( 0.0, -1.0), vec2( 1.0,  0.0), vec2(-1.0,  0.0),
    vec2( 0.707,  0.707), vec2(-0.707,  0.707), vec2( 0.707, -0.707), vec2(-0.707, -0.707)
);

void main() {
    ivec2 pixelPos = ivec2(gl_GlobalInvocationID.xy);
    ivec2 imageSize = imageSize(motionVectorOutput);
    if (pixelPos.x >= imageSize.x || pixelPos.y >= imageSize.y) return;

    vec2 uv = (vec2(pixelPos) + 0.5) / vec2(imageSize);
    vec2 ts = 1.0 / vec2(imageSize);
    const vec2 maxVelocity = vec2(0.20); // 20% screen velocity range

    float l00 = getPerceptualLuma(textureLod(currFrame, uv, 0.0).rgb);
    float p00 = getPerceptualLuma(textureLod(prevFrame, uv, 0.0).rgb);
    float diff = abs(l00 - p00);

    // Static HUD / UI Mask
    float lumaN = getPerceptualLuma(textureLod(currFrame, uv + vec2(0.0, -ts.y), 0.0).rgb);
    float lumaS = getPerceptualLuma(textureLod(currFrame, uv + vec2(0.0,  ts.y), 0.0).rgb);
    float lumaE = getPerceptualLuma(textureLod(currFrame, uv + vec2( ts.x, 0.0), 0.0).rgb);
    float lumaW = getPerceptualLuma(textureLod(currFrame, uv + vec2(-ts.x, 0.0), 0.0).rgb);
    float edgeStrength = abs(lumaN + lumaS + lumaE + lumaW - 4.0 * l00);

    if (diff < 0.005 || (edgeStrength > 0.18 && diff < 0.015)) {
        imageStore(motionVectorOutput, pixelPos, vec4(0.0, 0.0, 1.0, 1.0));
        return;
    }

    // ---- Temporal search center (Vegas-style): start from last frame's vector ----
    vec2 centerMV = textureLod(mvHistoryTexture, uv, 0.0).rg;

    vec2 bestMV = centerMV;
    float bestSAD = abs(l00 - getPerceptualLuma(textureLod(prevFrame, clamp(uv + centerMV, 0.0, 1.0), 0.0).rgb));
    float secondBestSAD = 1.0;

    // 3-Tier Multi-Scale Diamond Search (Extended Reach centered on velocity)
    float steps[3] = float[3](18.0, 8.0, 2.0);
    for (int s = 0; s < 3; s++) {
        float stepVal = steps[s];
        for (int j = 0; j < 8; j++) {
            vec2 off = clamp(centerMV + diamondOffsets8[j] * (stepVal * ts), -maxVelocity, maxVelocity);
            vec2 samplePos = clamp(uv + off, 0.0, 1.0);
            float curLuma = getPerceptualLuma(textureLod(prevFrame, samplePos, 0.0).rgb);
            float curSAD = abs(l00 - curLuma);
            if (curSAD < bestSAD) {
                secondBestSAD = bestSAD;
                bestSAD = curSAD;
                bestMV = off;
            } else if (curSAD < secondBestSAD && length(off - bestMV) > ts.x * 4.0) {
                secondBestSAD = curSAD;
            }
        }
    }

    // Bimodal Dominance Gate (Vegas Logic): distrust blocks with two competing motions (halos)
    float dominance = clamp((secondBestSAD - bestSAD) / (bestSAD + 0.01), 0.0, 1.0);
    float vegasTrust = smoothstep(0.10, 0.55, dominance);

    // 3x3 Spatial Vector Median Filter (FSR 3 / FidelityFX)
    vec2 neighborMVs[9];
    int nIdx = 0;
    for (int dy = -1; dy <= 1; dy++) {
        for (int dx = -1; dx <= 1; dx++) {
            vec2 sUV = clamp(uv + vec2(float(dx), float(dy)) * ts, 0.0, 1.0);
            neighborMVs[nIdx] = textureLod(mvHistoryTexture, sUV, 0.0).rg;
            nIdx++;
        }
    }
    float minTotalDist = 1e10;
    vec2 medianHistoryMV = neighborMVs[4];
    for (int i = 0; i < 9; i++) {
        float distSum = 0.0;
        for (int j = 0; j < 9; j++) {
            vec2 d = neighborMVs[i] - neighborMVs[j];
            distSum += dot(d, d);
        }
        if (distSum < minTotalDist) {
            minTotalDist = distSum;
            medianHistoryMV = neighborMVs[i];
        }
    }

    // Subpixel micro-jitter deadband
    if (length(bestMV) < ts.x * 0.30) {
        bestMV = vec2(0.0);
    }

    // AMD FSR 3 Adaptive History Blend (Hyper-Liquid / High-Persistence Tuning)
    float confidence = (1.0 - clamp(bestSAD * 4.0, 0.0, 1.0)) * vegasTrust;
    float mvDiff = length(bestMV - medianHistoryMV) / max(ts.x * 4.0, length(bestMV) + 0.001);

    // 0.92 Persistence: creates a "Liquid Momentum" effect for ultra-buttery flow
    float historyWeight = clamp(0.92 * confidence * exp(-pow(mvDiff, 2.0) * 8.0), 0.0, 0.92);
    vec2 stabilizedMV = mix(bestMV, medianHistoryMV, historyWeight);
    stabilizedMV = clamp(stabilizedMV, -maxVelocity, maxVelocity);

    imageStore(motionVectorOutput, pixelPos, vec4(stabilizedMV, confidence, 0.0, 1.0));
}
)";

// =============================================================================
// 2. MULTI-PASS COMPUTE SHADER (PRESETS 1, 2, 3, 4)
// =============================================================================
static const char* kComputeShaderMulti = R"(#version 310 es
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

float getPerceptualLuma(vec3 c) {
    return sqrt(clamp(dot(c, vec3(0.2126, 0.7152, 0.0722)), 0.0, 1.0));
}

const vec2 searchOffsets16[16] = vec2[](
    vec2( 0.0,  1.0), vec2( 0.0, -1.0), vec2( 1.0,  0.0), vec2(-1.0,  0.0),
    vec2( 0.707,  0.707), vec2(-0.707,  0.707), vec2( 0.707, -0.707), vec2(-0.707, -0.707),
    vec2( 0.383,  0.924), vec2(-0.383,  0.924), vec2( 0.383, -0.924), vec2(-0.383, -0.924),
    vec2( 0.924,  0.383), vec2(-0.924,  0.383), vec2( 0.924, -0.383), vec2(-0.924, -0.383)
);

void main() {
    ivec2 pixelPos = ivec2(gl_GlobalInvocationID.xy);
    ivec2 imageSize = imageSize(motionVectorOutput);
    if (pixelPos.x >= imageSize.x || pixelPos.y >= imageSize.y) return;

    vec2 uv = (vec2(pixelPos) + 0.5) / vec2(imageSize);
    vec2 ts = 1.0 / vec2(imageSize);
    const vec2 maxVelocity = vec2(0.20); // 20% screen velocity range

    if (passIndex == 1) {
        // PASS 1: Native Perceptual Luma Extraction (L0)
        vec3 cCurr = textureLod(currFrame, uv, 0.0).rgb;
        vec3 cPrev = textureLod(prevFrame, uv, 0.0).rgb;
        imageStore(motionVectorOutput, pixelPos, vec4(getPerceptualLuma(cCurr), getPerceptualLuma(cPrev), 0.0, 1.0));
        return;
    }
    else if (passIndex == 2) {
        // PASS 2: 2x2 Box Filter Downsampling (L1)
        vec2 hts = ts * 0.5;
        vec4 s0 = textureLod(lumaTexL0, uv + vec2(-hts.x, -hts.y), 0.0);
        vec4 s1 = textureLod(lumaTexL0, uv + vec2( hts.x, -hts.y), 0.0);
        vec4 s2 = textureLod(lumaTexL0, uv + vec2(-hts.x,  hts.y), 0.0);
        vec4 s3 = textureLod(lumaTexL0, uv + vec2( hts.x,  hts.y), 0.0);
        vec2 avgLuma = (s0.rg + s1.rg + s2.rg + s3.rg) * 0.25;
        imageStore(motionVectorOutput, pixelPos, vec4(avgLuma, 0.0, 1.0));
        return;
    }
    else if (passIndex == 3) {
        // PASS 3: 2x2 Box Filter Downsampling (L2)
        vec2 hts = ts * 0.5;
        vec4 s0 = textureLod(lumaTexL1, uv + vec2(-hts.x, -hts.y), 0.0);
        vec4 s1 = textureLod(lumaTexL1, uv + vec2( hts.x, -hts.y), 0.0);
        vec4 s2 = textureLod(lumaTexL1, uv + vec2(-hts.x,  hts.y), 0.0);
        vec4 s3 = textureLod(lumaTexL1, uv + vec2( hts.x,  hts.y), 0.0);
        vec2 avgLuma = (s0.rg + s1.rg + s2.rg + s3.rg) * 0.25;
        imageStore(motionVectorOutput, pixelPos, vec4(avgLuma, 0.0, 1.0));
        return;
    }
    else if (passIndex == 4) {
        // PASS 4: Coarse Scale Optical Flow Estimation (Temporal Centering)
        sampler2D srcLuma = (quality == 4) ? lumaTexL2 : ((quality == 3) ? lumaTexL1 : lumaTexL0);
        vec4 lumaData = textureLod(srcLuma, uv, 0.0);
        float l00 = lumaData.r;

        vec2 centerMV = textureLod(mvHistoryTexture, uv, 0.0).rg;
        float bestSAD = abs(l00 - textureLod(srcLuma, clamp(uv + centerMV, 0.0, 1.0), 0.0).g);
        vec2 bestMV = centerMV;
        float secondBestSAD = 1.0;

        // Extended 4-tier coarse search for wide-angle camera sweeps
        float steps[4] = float[4](24.0, 14.0, 6.0, 2.0);
        for (int s = 0; s < 4; s++) {
            float stepVal = steps[s];
            for (int j = 0; j < 16; j++) {
                vec2 off = clamp(centerMV + searchOffsets16[j] * (stepVal * ts), -maxVelocity, maxVelocity);
                vec2 samplePos = clamp(uv + off, 0.0, 1.0);
                float curLuma = textureLod(srcLuma, samplePos, 0.0).g;
                float curSAD = abs(l00 - curLuma);
                if (curSAD < bestSAD) {
                    secondBestSAD = bestSAD;
                    bestSAD = curSAD;
                    bestMV = off;
                } else if (curSAD < secondBestSAD && length(off - bestMV) > ts.x * 12.0) {
                    secondBestSAD = curSAD;
                }
            }
        }
        float dominance = clamp((secondBestSAD - bestSAD) / (bestSAD + 0.01), 0.0, 1.0);
        imageStore(motionVectorOutput, pixelPos, vec4(bestMV, bestSAD, dominance));
        return;
    }
    else if (passIndex == 5) {
        // PASS 5: Guided Upscale & Multi-Scale Refinement (Temporal Centering)
        sampler2D srcLuma = (quality == 4) ? lumaTexL1 : lumaTexL0;
        vec2 centerMV = textureLod(mvHistoryTexture, uv, 0.0).rg;
        vec2 guidedMV = clamp(textureLod(coarseMVTex, uv, 0.0).rg, -maxVelocity, maxVelocity);

        // Blend coarse guide with temporal center for higher stability
        vec2 baseMV = mix(guidedMV, centerMV, 0.35);

        vec4 lumaData = textureLod(srcLuma, uv, 0.0);
        float l00 = lumaData.r;
        float bestSAD = abs(lumaData.r - textureLod(srcLuma, clamp(uv + baseMV, 0.0, 1.0), 0.0).g);
        vec2 bestMV = baseMV;

        float steps[3] = float[3](8.0, 4.0, 1.5);
        for (int s = 0; s < 3; s++) {
            float stepVal = steps[s];
            for (int j = 0; j < 16; j++) {
                vec2 off = clamp(baseMV + searchOffsets16[j] * (stepVal * ts), -maxVelocity, maxVelocity);
                vec2 samplePos = clamp(uv + off, 0.0, 1.0);
                float curLuma = textureLod(srcLuma, samplePos, 0.0).g;
                float curSAD = abs(l00 - curLuma);
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
        // PASS 6: Fine 1:1 Subpixel Optical Flow Matching
        vec2 guidedMV = clamp(textureLod(midMVTex, uv, 0.0).rg, -maxVelocity, maxVelocity);
        vec4 lumaData = textureLod(lumaTexL0, uv, 0.0);
        float l00 = lumaData.r;
        float diff = abs(lumaData.r - lumaData.g);

        if (diff < 0.005) {
            imageStore(motionVectorOutput, pixelPos, vec4(0.0, 0.0, 1.0, 1.0));
            return;
        }

        float bestSAD = abs(lumaData.r - textureLod(lumaTexL0, clamp(uv + guidedMV, 0.0, 1.0), 0.0).g);
        vec2 bestMV = guidedMV;
        float secondBestSAD = 1.0;

        float steps[3] = float[3](3.0, 1.5, 0.5);
        for (int s = 0; s < 3; s++) {
            float stepVal = steps[s];
            for (int j = 0; j < 16; j++) {
                vec2 off = clamp(bestMV + searchOffsets16[j] * (stepVal * ts), -maxVelocity, maxVelocity);
                vec2 samplePos = clamp(uv + off, 0.0, 1.0);
                float curLuma = textureLod(lumaTexL0, samplePos, 0.0).g;
                float curSAD = abs(l00 - curLuma);
                if (curSAD < bestSAD) {
                    secondBestSAD = bestSAD;
                    bestSAD = curSAD;
                    bestMV = off;
                } else if (curSAD < secondBestSAD && length(off - bestMV) > ts.x * 2.0) {
                    secondBestSAD = curSAD;
                }
            }
        }
        float dominance = clamp((secondBestSAD - bestSAD) / (bestSAD + 0.01), 0.0, 1.0);
        imageStore(motionVectorOutput, pixelPos, vec4(bestMV, bestSAD, dominance));
        return;
    }
    else if (passIndex == 7) {
        // PASS 7: Scene Cut & Divergence Analysis
        vec4 rawData = textureLod(rawMVTex, uv, 0.0);
        float diff = rawData.b;
        float sceneCut = (diff > 0.85) ? 1.0 : 0.0;
        float dominance = rawData.a;
        float confidence = (1.0 - clamp(rawData.b * 3.5, 0.0, 1.0)) * smoothstep(0.10, 0.55, dominance);
        vec2 clampedMV = clamp(rawData.rg, -maxVelocity, maxVelocity);
        imageStore(motionVectorOutput, pixelPos, vec4(clampedMV, confidence, sceneCut));
        return;
    }
    else if (passIndex == 8) {
        // PASS 8: 3x3 Spatial Vector Median Filter (FSR 3 / FidelityFX)
        sampler2D srcTex = (quality == 4) ? divergenceTex : rawMVTex;
        vec2 neighborMVs[9];
        int nIdx = 0;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                vec2 sUV = clamp(uv + vec2(float(dx), float(dy)) * ts, 0.0, 1.0);
                neighborMVs[nIdx] = textureLod(srcTex, sUV, 0.0).rg;
                nIdx++;
            }
        }
        float minTotalDist = 1e10;
        vec2 filteredMV = neighborMVs[4];
        for (int i = 0; i < 9; i++) {
            float distSum = 0.0;
            for (int j = 0; j < 9; j++) {
                vec2 d = neighborMVs[i] - neighborMVs[j];
                distSum += dot(d, d);
            }
            if (distSum < minTotalDist) {
                minTotalDist = distSum;
                filteredMV = neighborMVs[i];
            }
        }

        // Subpixel micro-jitter deadband
        if (length(filteredMV) < ts.x * 0.30) {
            filteredMV = vec2(0.0);
        }
        filteredMV = clamp(filteredMV, -maxVelocity, maxVelocity);

        vec4 srcData = textureLod(srcTex, uv, 0.0);
        float conf = (srcData.b > 0.0) ? (1.0 - clamp(srcData.b * 3.5, 0.0, 1.0)) : 1.0;

        if (quality < 4) {
            // Presets 1, 2, 3: apply AMD FSR 3 Adaptive History Accumulation
            vec2 prevMV = textureLod(mvHistoryTexture, uv, 0.0).rg;
            vec2 projectedUV = clamp(uv - prevMV, 0.0, 1.0);
            vec2 historyMV = textureLod(mvHistoryTexture, projectedUV, 0.0).rg;
            float mvDiff = length(filteredMV - historyMV) / max(ts.x * 4.0, length(filteredMV) + 0.001);
            float historyWeight = clamp(0.92 * conf * exp(-pow(mvDiff, 2.0) * 8.0), 0.0, 0.92);
            vec2 stabilizedMV = (srcData.a > 0.5) ? vec2(0.0) : mix(filteredMV, historyMV, historyWeight);
            stabilizedMV = clamp(stabilizedMV, -maxVelocity, maxVelocity);
            imageStore(motionVectorOutput, pixelPos, vec4(stabilizedMV, conf, 0.0, 1.0));
        } else {
            imageStore(motionVectorOutput, pixelPos, vec4(filteredMV, conf, srcData.a));
        }
        return;
    }
    else if (passIndex == 9) {
        // PASS 9: Temporal Reprojection & Final Motion Vector Field Output (Quality 4)
        vec4 filteredData = textureLod(filteredMVTex, uv, 0.0);
        vec2 currentMV = clamp(filteredData.rg, -maxVelocity, maxVelocity);
        float confidence = filteredData.b;
        float sceneCut = filteredData.a;

        vec2 prevMV = textureLod(mvHistoryTexture, uv, 0.0).rg;
        vec2 projectedUV = clamp(uv - prevMV, 0.0, 1.0);
        vec2 historyMV = textureLod(mvHistoryTexture, projectedUV, 0.0).rg;

        float mvDiff = length(currentMV - historyMV) / max(ts.x * 4.0, length(currentMV) + 0.001);
        float historyWeight = clamp(0.92 * confidence * exp(-pow(mvDiff, 2.0) * 8.0), 0.0, 0.92);
        vec2 stabilizedMV = (sceneCut > 0.5) ? vec2(0.0) : mix(currentMV, historyMV, historyWeight);
        stabilizedMV = clamp(stabilizedMV, -maxVelocity, maxVelocity);

        imageStore(motionVectorOutput, pixelPos, vec4(stabilizedMV, confidence, 0.0, 1.0));
        return;
    }
}
)";

// =============================================================================
// 3. WARPING VERTEX SHADER (#version 300 es)
// =============================================================================
static const char* kWarpingVertexShader = R"(#version 300 es
layout(location = 0) in vec2 position;
out vec2 vUV;

void main() {
    vUV = position;
    gl_Position = vec4(2.0 * position.x - 1.0, 2.0 * position.y - 1.0, 0.0, 1.0);
}
)";

// =============================================================================
// 4. WARPING & INPAINTING FRAGMENT SHADER (#version 300 es)
// =============================================================================
static const char* kWarpingFragmentShader = R"(#version 300 es
precision mediump float;

uniform sampler2D screenTexture;
uniform sampler2D previousCapturedTexture;
uniform sampler2D currentCapturedTexture;
uniform sampler2D motionVectorTexture;

uniform vec2 resolution;
uniform float interpolationFactor;
uniform float qualityMode;
uniform float uBlurIntensity;
uniform float uFlowScale;

in vec2 vUV;
out vec4 outColor;

float normalizedDot3(vec3 a, vec3 b) {
    float magA = length(a);
    float magB = length(b);
    if (magA < 0.001 || magB < 0.001) return 1.0;
    return clamp(dot(a, b) / (magA * magB), 0.0, 1.0);
}

void main() {
    float factor = interpolationFactor;

    // Stabilized Real Frame Pass: Apply a very subtle blur to real frames to match
    // the generated frames, preventing "Sharp vs Soft" flickering (Cinematic Tuning).
    if (factor >= 0.999 && uBlurIntensity < 0.05) {
        outColor = vec4(texture(currentCapturedTexture, vUV).rgb, 1.0);
        return;
    }

    vec4 mvSample = texture(motionVectorTexture, vUV);
    vec2 mv = clamp(mvSample.rg, -vec2(0.15), vec2(0.15)) * uFlowScale;
    float confidence = clamp(mvSample.b, 0.0, 1.0);

    // Subpixel micro-jitter deadband suppression
    if (length(mv) < (1.0 / resolution.x) * 0.35) {
        mv = vec2(0.0);
        confidence = 1.0;
    }

    // Attenuate motion vector when confidence is low (Extreme Sharpening for Mali)
    mv *= pow(confidence, 1.5);
    mv *= smoothstep(0.18, 0.65, confidence);

    // Bidirectional Optical Flow Warping:
    vec2 uvPrev = clamp(vUV + mv * (1.0 - factor), 0.0, 1.0);
    vec2 uvCurr = clamp(vUV - mv * factor, 0.0, 1.0);

    // Hyper-Liquid Optical Blur: Deep Shutter Reach (1.5x Multiplier)
    float shutterGain = clamp(uBlurIntensity, 0.0, 1.0) * 1.5;
    vec2 vel = mv * shutterGain;

    // 7-Tap Hyper-Flow Dispersion Kernel (Velocity-Weighted Deep Softness)
    vec3 warpedPrev = (
        texture(previousCapturedTexture, clamp(uvPrev - vel * 1.6, 0.0, 1.0)).rgb * 0.04 +
        texture(previousCapturedTexture, clamp(uvPrev - vel * 1.0, 0.0, 1.0)).rgb * 0.08 +
        texture(previousCapturedTexture, clamp(uvPrev - vel * 0.5, 0.0, 1.0)).rgb * 0.18 +
        texture(previousCapturedTexture, uvPrev).rgb * 0.40 +
        texture(previousCapturedTexture, clamp(uvPrev + vel * 0.5, 0.0, 1.0)).rgb * 0.18 +
        texture(previousCapturedTexture, clamp(uvPrev + vel * 1.0, 0.0, 1.0)).rgb * 0.08 +
        texture(previousCapturedTexture, clamp(uvPrev + vel * 1.6, 0.0, 1.0)).rgb * 0.04
    );

    vec3 warpedCurr = (
        texture(currentCapturedTexture, clamp(uvCurr + vel * 1.6, 0.0, 1.0)).rgb * 0.04 +
        texture(currentCapturedTexture, clamp(uvCurr + vel * 1.0, 0.0, 1.0)).rgb * 0.08 +
        texture(currentCapturedTexture, clamp(uvCurr + vel * 0.5, 0.0, 1.0)).rgb * 0.18 +
        texture(currentCapturedTexture, uvCurr).rgb * 0.40 +
        texture(currentCapturedTexture, clamp(uvCurr - vel * 0.5, 0.0, 1.0)).rgb * 0.18 +
        texture(currentCapturedTexture, clamp(uvCurr - vel * 1.0, 0.0, 1.0)).rgb * 0.08 +
        texture(currentCapturedTexture, clamp(uvCurr - vel * 1.6, 0.0, 1.0)).rgb * 0.04
    );

    if (factor >= 0.999) {
        outColor = vec4(warpedCurr, 1.0);
        return;
    }

    // AMD FSR 3 Normalized Color Similarity & Disocclusion Detection (Premium Clean Edge Tuning)
    float sim = normalizedDot3(warpedPrev, warpedCurr);
    float colorDist = distance(warpedPrev, warpedCurr);
    float lumaDiff = abs(dot(warpedPrev - warpedCurr, vec3(0.2126, 0.7152, 0.0722)));
    float simThreshold = (qualityMode >= 2.5) ? 0.60 : 0.65;

    // Combine cosine similarity with Euclidean distance for better halo rejection
    float disocclusion = smoothstep(simThreshold, 0.92, 1.0 - sim) +
                         smoothstep(0.10, 0.30, lumaDiff) +
                         smoothstep(0.15, 0.45, colorDist);
    disocclusion = clamp(disocclusion, 0.0, 1.0);

    // Premium Adaptive Hermite S-Curve (Optimized for Optical Continuity)
    float smoothT = factor * factor * factor * (factor * (factor * 6.0 - 15.0) + 10.0);
    float blendFactor = max(disocclusion, (1.0 - confidence));
    float t = mix(factor, smoothT, blendFactor * 0.98);
    t = clamp(t, 0.0, 1.0);

    // In disocclusion areas, bias towards current frame to eliminate ghosting trails
    if (disocclusion > 0.25) {
        t = mix(t, 1.0, smoothstep(0.25, 0.70, disocclusion));
    }

    vec3 result = mix(warpedPrev, warpedCurr, t);
    outColor = vec4(result, 1.0);
}
)";

} // namespace apex
