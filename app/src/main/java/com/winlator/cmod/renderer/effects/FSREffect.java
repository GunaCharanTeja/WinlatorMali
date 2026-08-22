package com.winlator.cmod.renderer.effects;

import com.winlator.cmod.renderer.material.ScreenMaterial;
import com.winlator.cmod.renderer.material.ShaderMaterial;
import java.util.Locale;

public class FSREffect extends Effect {

    // --- MODOS ---
    public static final int MODE_SUPER_RESOLUTION = 0; // CAS Puro
    public static final int MODE_DLS = 1;              // CAS + Saturação

    private int currentMode = MODE_SUPER_RESOLUTION; 
    private float sharpnessLevel = 1.0f; // Padrão Nivel 1 (Imagem Limpa)

    public void setMode(int mode) {
        if (this.currentMode != mode) {
            this.currentMode = mode;
            destroy();
        }
    }

    public int getMode() { return currentMode; }

    public void setLevel(float level) {
        if (this.sharpnessLevel != level) {
            this.sharpnessLevel = level;
            destroy();
        }
    }
    
    public float getLevel() { return sharpnessLevel; }

    @Override
    protected ShaderMaterial createMaterial() {
        return new FSRMaterial();
    }

    private class FSRMaterial extends ScreenMaterial {
        @Override
        protected String getFragmentShader() {
            // AMD FidelityFX RCAS (Robust Contrast Adaptive Sharpening)
            // Stop-based sharpness scale: 0.0 = Maximum Sharpness, 1.0 = Moderate, 2.0 = Subtle
            float stops = 0.0f;
            if (sharpnessLevel <= 1.1f) {
                stops = 1.6f; // Smooth / Subtle
            } else if (sharpnessLevel <= 2.1f) {
                stops = 1.0f; // Light
            } else if (sharpnessLevel <= 3.1f) {
                stops = 0.5f; // Balanced FSR 3 Standard
            } else if (sharpnessLevel <= 4.1f) {
                stops = 0.2f; // High Precision
            } else {
                stops = 0.0f; // Maximum RCAS Sharpness
            }

            boolean useDLS = (currentMode == MODE_DLS);
            float saturation = useDLS ? 1.20f : 1.0f;

            String sStops = String.format(Locale.US, "%.4f", stops);
            String sSat = String.format(Locale.US, "%.4f", saturation);
            
            StringBuilder shader = new StringBuilder();
            shader.append("precision mediump float;\n");
            shader.append("uniform sampler2D screenTexture;\n");
            shader.append("uniform vec2 resolution;\n");
            shader.append("varying vec2 vUV;\n");
            
            shader.append("const float SHARPNESS_STOPS = ").append(sStops).append(";\n");
            if (useDLS) {
                shader.append("const float SAT = ").append(sSat).append(";\n");
            }

            shader.append("void main() {\n");
            shader.append("    vec2 uv = vUV;\n");
            shader.append("    vec2 px = 1.0 / resolution;\n");
            shader.append("    \n");
            shader.append("    // --- AMD FidelityFX RCAS 5-Tap Cross Neighborhood ---\n");
            shader.append("    vec3 b = texture2D(screenTexture, uv + vec2( 0.0, -px.y)).rgb;\n");
            shader.append("    vec3 d = texture2D(screenTexture, uv + vec2(-px.x,  0.0)).rgb;\n");
            shader.append("    vec3 e = texture2D(screenTexture, uv).rgb;\n");
            shader.append("    vec3 f = texture2D(screenTexture, uv + vec2( px.x,  0.0)).rgb;\n");
            shader.append("    vec3 h = texture2D(screenTexture, uv + vec2( 0.0,  px.y)).rgb;\n");
            shader.append("    \n");
            shader.append("    // Fast 2x Perceptual Luminance (Rec.709 approximation)\n");
            shader.append("    float bL = b.b * 0.5 + (b.r * 0.5 + b.g);\n");
            shader.append("    float dL = d.b * 0.5 + (d.r * 0.5 + d.g);\n");
            shader.append("    float eL = e.b * 0.5 + (e.r * 0.5 + e.g);\n");
            shader.append("    float fL = f.b * 0.5 + (f.r * 0.5 + f.g);\n");
            shader.append("    float hL = h.b * 0.5 + (h.r * 0.5 + h.g);\n");
            shader.append("    \n");
            shader.append("    // Highpass Noise Detector (Suppresses film grain / noise sharpening)\n");
            shader.append("    float nz = 0.25 * bL + 0.25 * dL + 0.25 * fL + 0.25 * hL - eL;\n");
            shader.append("    float mxL = max(max(max(bL, dL), max(fL, hL)), eL);\n");
            shader.append("    float mnL = min(min(min(bL, dL), min(fL, hL)), eL);\n");
            shader.append("    float rangeL = max(mxL - mnL, 0.0001);\n");
            shader.append("    nz = clamp(abs(nz) / rangeL, 0.0, 1.0);\n");
            shader.append("    nz = -0.5 * nz + 1.0;\n");
            shader.append("    \n");
            shader.append("    // Non-clipping negative lobe limit calculation (FSR RCAS)\n");
            shader.append("    vec3 mn4 = min(min(b, d), min(f, h));\n");
            shader.append("    vec3 mx4 = max(max(b, d), max(f, h));\n");
            shader.append("    vec3 hitMin = mn4 / (4.0 * max(mx4, vec3(0.0001)));\n");
            shader.append("    vec3 hitMax = (vec3(1.0) - mx4) / (4.0 * max(vec3(1.0) - mn4, vec3(0.0001)));\n");
            shader.append("    vec3 lobe = -min(hitMin, hitMax);\n");
            shader.append("    lobe = max(lobe, vec3(-0.1875)); // FSR_RCAS_LIMIT = 0.25 - 1/16\n");
            shader.append("    \n");
            shader.append("    // Apply exponential sharpness stops and noise attenuation\n");
            shader.append("    float sharp = exp2(-SHARPNESS_STOPS);\n");
            shader.append("    vec3 w = lobe * (sharp * nz);\n");
            shader.append("    vec3 rcpW = vec3(1.0) / (vec3(1.0) + 4.0 * w);\n");
            shader.append("    vec3 outColor = clamp(((b + d + f + h) * w + e) * rcpW, 0.0, 1.0);\n");
            shader.append("    \n");
            if (useDLS) {
                shader.append("    float luma = dot(outColor, vec3(0.2126, 0.7152, 0.0722));\n");
                shader.append("    outColor = mix(vec3(luma), outColor, SAT);\n");
            }
            shader.append("    gl_FragColor = vec4(clamp(outColor, 0.0, 1.0), 1.0);\n");
            shader.append("}\n");

            return shader.toString();
        }
    }
}
