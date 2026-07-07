package com.winlator.cmod.renderer.effects;

import com.winlator.cmod.renderer.material.ScreenMaterial;
import com.winlator.cmod.renderer.material.ShaderMaterial;
import java.util.Locale;

public class HDREffect extends Effect {

    private boolean enabled = true;

    public void setStrength(float s) {
        this.enabled = s > 0.5f;
    }

    @Override
    protected ShaderMaterial createMaterial() {
        return new HDRMaterial();
    }

    private class HDRMaterial extends ScreenMaterial {
        @Override
        protected String getFragmentShader() {
            
            String sPower = "1.30"; 
            String radius1 = "0.793";
            String radius2 = "0.870";

            return String.join("\n", new CharSequence[]{
                "precision mediump float;",
                "uniform sampler2D screenTexture;",
                "uniform vec2 resolution;",

                "const float HDRPower = " + sPower + ";",
                "const float radius1 = " + radius1 + ";",
                "const float radius2 = " + radius2 + ";",

                "void main() {",
                "    vec2 texcoord = gl_FragCoord.xy / resolution;",
                "    vec2 px = 1.0 / resolution;",
                
                "    vec3 color = texture2D(screenTexture, texcoord).rgb;",

                "    // --- BLOOM PASS 1 (Radius 1) ---",
                "    vec3 bloom_sum1 = texture2D(screenTexture, texcoord + vec2(1.5, -1.5) * radius1 * px).rgb;",
                "    bloom_sum1 += texture2D(screenTexture, texcoord + vec2(-1.5, -1.5) * radius1 * px).rgb;",
                "    bloom_sum1 += texture2D(screenTexture, texcoord + vec2( 1.5,  1.5) * radius1 * px).rgb;",
                "    bloom_sum1 += texture2D(screenTexture, texcoord + vec2(-1.5,  1.5) * radius1 * px).rgb;",
                "    bloom_sum1 += texture2D(screenTexture, texcoord + vec2( 0.0, -2.5) * radius1 * px).rgb;",
                "    bloom_sum1 += texture2D(screenTexture, texcoord + vec2( 0.0,  2.5) * radius1 * px).rgb;",
                "    bloom_sum1 += texture2D(screenTexture, texcoord + vec2(-2.5,  0.0) * radius1 * px).rgb;",
                "    bloom_sum1 += texture2D(screenTexture, texcoord + vec2( 2.5,  0.0) * radius1 * px).rgb;",
                "    bloom_sum1 *= 0.005;",

                "    // --- BLOOM PASS 2 (Radius 2) ---",
                "    vec3 bloom_sum2 = texture2D(screenTexture, texcoord + vec2(1.5, -1.5) * radius2 * px).rgb;",
                "    bloom_sum2 += texture2D(screenTexture, texcoord + vec2(-1.5, -1.5) * radius2 * px).rgb;",
                "    bloom_sum2 += texture2D(screenTexture, texcoord + vec2( 1.5,  1.5) * radius2 * px).rgb;",
                "    bloom_sum2 += texture2D(screenTexture, texcoord + vec2(-1.5,  1.5) * radius2 * px).rgb;",
                "    bloom_sum2 += texture2D(screenTexture, texcoord + vec2( 0.0, -2.5) * radius2 * px).rgb;",
                "    bloom_sum2 += texture2D(screenTexture, texcoord + vec2( 0.0,  2.5) * radius2 * px).rgb;",
                "    bloom_sum2 += texture2D(screenTexture, texcoord + vec2(-2.5,  0.0) * radius2 * px).rgb;",
                "    bloom_sum2 += texture2D(screenTexture, texcoord + vec2( 2.5,  0.0) * radius2 * px).rgb;",
                "    bloom_sum2 *= 0.010;",

                "    // --- FAKE HDR CALCULATION ---",
                "    float dist = radius2 - radius1;",
                "    vec3 HDR = (color + (bloom_sum2 - bloom_sum1)) * dist;",
                "    vec3 blend = HDR + color;",
                "    ",
                "    // Pow() aplica o contraste forte característico desse shader",
                "    // Abs() protege contra valores negativos que causam glitch visual",
                "    color = pow(abs(blend), vec3(abs(HDRPower))) + HDR;",
                "    ",
                "    gl_FragColor = vec4(clamp(color, 0.0, 1.0), 1.0);",
                "}"
            });
        }
    }
}
