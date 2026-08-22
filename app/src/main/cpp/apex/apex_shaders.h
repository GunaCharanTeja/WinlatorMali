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
    const vec2 maxVelocity = vec2(0.15); // Max 15% screen motion

    float l00 = getPerceptualLuma(textureLod(currFrame, uv, 0.0).rgb);
    float p00 = getPerceptualLuma(textureLod(prevFrame, uv, 0.0).rgb);
    float diff = abs(l00 - p00);

    // Decoupled HUD / Static Region Mask
    float lumaN = getPerceptualLuma(textureLod(currFrame, uv + vec2(0.0, -ts.y), 0.0).rgb);
    float lumaS = getPerceptualLuma(textureLod(currFrame, uv + vec2(0.0,  ts.y), 0.0).rgb);
    float lumaE = getPerceptualLuma(textureLod(currFrame, uv + vec2( ts.x, 0.0), 0.0).rgb);
    float lumaW = getPerceptualLuma(textureLod(currFrame, uv + vec2(-ts.x, 0.0), 0.0).rgb);
    float edgeStrength = abs(lumaN + lumaS + lumaE + lumaW - 4.0 * l00);

    if (diff < 0.006 || (edgeStrength > 0.15 && diff < 0.020)) {
        imageStore(motionVectorOutput, pixelPos, vec4(0.0, 0.0, 1.0, 1.0));
        return;
    }

    vec2 bestMV = vec2(0.0);
    float bestSAD = diff;

    // 2-Tier Hierarchical Diamond Search with Clamping
    float steps[2] = float[2](12.0, 4.0);
    for (int s = 0; s < 2; s++) {
        float stepVal = steps[s];
        for (int j = 0; j < 8; j++) {
            vec2 off = clamp(bestMV + diamondOffsets8[j] * (stepVal * ts), -maxVelocity, maxVelocity);
            vec2 samplePos = clamp(uv + off, 0.0, 1.0);
            float curLuma = getPerceptualLuma(textureLod(prevFrame, samplePos, 0.0).rgb);
            float curSAD = abs(l00 - curLuma);
            if (curSAD < bestSAD) {
                bestSAD = curSAD;
                bestMV = off;
            }
        }
    }

    // 3x3 Spatial Vector Median Filter
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

    // Deadzone for subpixel micro-jitter suppression
    if (length(bestMV) < ts.x * 0.25) {
        bestMV = vec2(0.0);
    }

    // AMD FSR 3 Adaptive History Blend
    float confidence = 1.0 - clamp(bestSAD * 4.0, 0.0, 1.0);
    float mvDiff = length(bestMV - medianHistoryMV) / max(ts.x * 4.0, length(bestMV) + 0.001);
    float historyWeight = clamp(0.70 * confidence * exp(-mvDiff * 0.6), 0.05, 0.80);
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
    const vec2 maxVelocity = vec2(0.15); // Max 15% screen motion limit

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
        // PASS 4: Coarse Scale Optical Flow Estimation
        sampler2D srcLuma = (quality == 4) ? lumaTexL2 : ((quality == 3) ? lumaTexL1 : lumaTexL0);
        vec4 lumaData = textureLod(srcLuma, uv, 0.0);
        float l00 = lumaData.r;
        float bestSAD = abs(lumaData.r - lumaData.g);
        vec2 bestMV = vec2(0.0);

        float steps[3] = float[3](14.0, 7.0, 3.0);
        for (int s = 0; s < 3; s++) {
            float stepVal = steps[s];
            for (int j = 0; j < 16; j++) {
                vec2 off = clamp(bestMV + searchOffsets16[j] * (stepVal * ts), -maxVelocity, maxVelocity);
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
    else if (passIndex == 5) {
        // PASS 5: Guided Upscale & Refinement
        sampler2D srcLuma = (quality == 4) ? lumaTexL1 : lumaTexL0;
        vec2 guidedMV = clamp(textureLod(coarseMVTex, uv, 0.0).rg, -maxVelocity, maxVelocity);
        vec4 lumaData = textureLod(srcLuma, uv, 0.0);
        float l00 = lumaData.r;
        float bestSAD = abs(lumaData.r - textureLod(srcLuma, clamp(uv + guidedMV, 0.0, 1.0), 0.0).g);
        vec2 bestMV = guidedMV;

        float steps[3] = float[3](5.0, 2.5, 1.0);
        for (int s = 0; s < 3; s++) {
            float stepVal = steps[s];
            for (int j = 0; j < 16; j++) {
                vec2 off = clamp(bestMV + searchOffsets16[j] * (stepVal * ts), -maxVelocity, maxVelocity);
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
        // PASS 6: Fine 1:1 Subpixel Optical Flow Block Matching
        vec2 guidedMV = clamp(textureLod(midMVTex, uv, 0.0).rg, -maxVelocity, maxVelocity);
        vec4 lumaData = textureLod(lumaTexL0, uv, 0.0);
        float l00 = lumaData.r;
        float diff = abs(lumaData.r - lumaData.g);

        if (diff < 0.006) {
            imageStore(motionVectorOutput, pixelPos, vec4(0.0, 0.0, 0.0, 1.0));
            return;
        }

        float bestSAD = abs(lumaData.r - textureLod(lumaTexL0, clamp(uv + guidedMV, 0.0, 1.0), 0.0).g);
        vec2 bestMV = guidedMV;

        float steps[3] = float[3](2.0, 1.0, 0.5);
        for (int s = 0; s < 3; s++) {
            float stepVal = steps[s];
            for (int j = 0; j < 16; j++) {
                vec2 off = clamp(bestMV + searchOffsets16[j] * (stepVal * ts), -maxVelocity, maxVelocity);
                vec2 samplePos = clamp(uv + off, 0.0, 1.0);
                float curLuma = textureLod(lumaTexL0, samplePos, 0.0).g;
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
    else if (passIndex == 7) {
        // PASS 7: Scene Cut & Divergence Analysis
        vec4 rawData = textureLod(rawMVTex, uv, 0.0);
        float diff = rawData.b;
        float sceneCut = (diff > 0.85) ? 1.0 : 0.0;
        float confidence = 1.0 - clamp(rawData.b * 3.5, 0.0, 1.0);
        vec2 clampedMV = clamp(rawData.rg, -maxVelocity, maxVelocity);
        imageStore(motionVectorOutput, pixelPos, vec4(clampedMV, confidence, sceneCut));
        return;
    }
    else if (passIndex == 8) {
        // PASS 8: 3x3 Spatial Vector Median Filter
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

        // Subpixel micro-jitter deadzone filter
        if (length(filteredMV) < ts.x * 0.25) {
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
            float historyWeight = clamp(0.70 * conf * exp(-mvDiff * 0.6), 0.05, 0.80);
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
        float historyWeight = clamp(0.70 * confidence * exp(-mvDiff * 0.6), 0.05, 0.80);
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
    if (factor <= 0.001) {
        outColor = vec4(texture(previousCapturedTexture, vUV).rgb, 1.0);
        return;
    }
    if (factor >= 0.999) {
        outColor = vec4(texture(currentCapturedTexture, vUV).rgb, 1.0);
        return;
    }

    vec4 mvSample = texture(motionVectorTexture, vUV);
    vec2 mv = clamp(mvSample.rg, -vec2(0.15), vec2(0.15));
    float confidence = clamp(mvSample.b, 0.0, 1.0);

    // Attenuate motion vector when confidence is low to prevent tearing
    mv *= smoothstep(0.05, 0.45, confidence);

    vec2 uvPrev = clamp(vUV + mv * factor, 0.0, 1.0);
    vec2 uvCurr = clamp(vUV - mv * (1.0 - factor), 0.0, 1.0);

    float shutterGain = clamp(uBlurIntensity, 0.0, 1.0) * 0.35;
    vec2 vel = mv * shutterGain;

    // 5-Tap Gaussian Velocity Flow & Shutter Blur along Motion Vectors
    vec3 warpedPrev = (
        texture(previousCapturedTexture, clamp(uvPrev - vel, 0.0, 1.0)).rgb * 0.10 +
        texture(previousCapturedTexture, clamp(uvPrev - vel * 0.5, 0.0, 1.0)).rgb * 0.20 +
        texture(previousCapturedTexture, uvPrev).rgb * 0.40 +
        texture(previousCapturedTexture, clamp(uvPrev + vel * 0.5, 0.0, 1.0)).rgb * 0.20 +
        texture(previousCapturedTexture, clamp(uvPrev + vel, 0.0, 1.0)).rgb * 0.10
    );

    vec3 warpedCurr = (
        texture(currentCapturedTexture, clamp(uvCurr + vel, 0.0, 1.0)).rgb * 0.10 +
        texture(currentCapturedTexture, clamp(uvCurr + vel * 0.5, 0.0, 1.0)).rgb * 0.20 +
        texture(currentCapturedTexture, uvCurr).rgb * 0.40 +
        texture(currentCapturedTexture, clamp(uvCurr - vel * 0.5, 0.0, 1.0)).rgb * 0.20 +
        texture(currentCapturedTexture, clamp(uvCurr - vel, 0.0, 1.0)).rgb * 0.10
    );

    // AMD FSR 3 Normalized Color Similarity & Disocclusion Detection
    float sim = normalizedDot3(warpedPrev, warpedCurr);
    float lumaDiff = abs(dot(warpedPrev - warpedCurr, vec3(0.2126, 0.7152, 0.0722)));
    float simThreshold = (qualityMode >= 2.5) ? 0.65 : 0.70;
    float disocclusion = smoothstep(simThreshold, 0.95, 1.0 - sim) + smoothstep(0.15, 0.40, lumaDiff);
    disocclusion = clamp(disocclusion, 0.0, 1.0);

    // Continuous Adaptive Hermite S-Curve Crossfade
    float smoothT = factor * factor * (3.0 - 2.0 * factor);
    float blendFactor = max(disocclusion, (1.0 - confidence));
    float t = mix(factor, smoothT, blendFactor * 0.60);
    t = clamp(t, 0.0, 1.0);

    vec3 result = mix(warpedPrev, warpedCurr, t);
    outColor = vec4(result, 1.0);
}
)";

} // namespace apex
