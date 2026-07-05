package com.winlator.cmod.renderer.effects;

import com.winlator.cmod.renderer.material.ScreenMaterial;
import com.winlator.cmod.renderer.material.ShaderMaterial;

public class FXAAEffect extends Effect {
    // Constructor
    public FXAAEffect() {
        super(); // Calls the constructor of the superclass Effect
    }

    // Creates and returns the ShaderMaterial for this effect
    @Override
    protected ShaderMaterial createMaterial() {
        // Returns an instance of the inner class which extends ScreenMaterial and implements the FXAA shader
        return new FXAAMaterial();
    }

    // Inner class implementing the FXAA shader material
    private class FXAAMaterial extends ScreenMaterial {
        // Constructor for the inner class, calls the superclass constructor
        public FXAAMaterial() {
            super();
        }

        @Override
        protected String getFragmentShader() {
            // Returns the GLSL fragment shader as a string.
            // This shader applies the FXAA technique to the screen texture.
            return String.join("\n", new CharSequence[]{
                    "precision highp float;",
                    "#define FXAA_REDUCE_MIN   (1.0/128.0)",
                    "#define FXAA_REDUCE_MUL   (1.0/8.0)",
                    "#define FXAA_SPAN_MAX     8.0",
                    "uniform sampler2D screenTexture;",
                    "uniform vec2 resolution;",
                    "varying vec2 vUV;",
                    "void main() {",
                    "    vec2 res = 1.0 / resolution;",
                    "    vec3 rgbNW = texture2D(screenTexture, vUV + (vec2(-1.0, -1.0) * res)).rgb;",
                    "    vec3 rgbNE = texture2D(screenTexture, vUV + (vec2(1.0, -1.0) * res)).rgb;",
                    "    vec3 rgbSW = texture2D(screenTexture, vUV + (vec2(-1.0, 1.0) * res)).rgb;",
                    "    vec3 rgbSE = texture2D(screenTexture, vUV + (vec2(1.0, 1.0) * res)).rgb;",
                    "    vec3 rgbM  = texture2D(screenTexture, vUV).rgb;",
                    "    vec3 luma = vec3(0.299, 0.587, 0.114);",
                    "    float lumaNW = dot(rgbNW, luma);",
                    "    float lumaNE = dot(rgbNE, luma);",
                    "    float lumaSW = dot(rgbSW, luma);",
                    "    float lumaSE = dot(rgbSE, luma);",
                    "    float lumaM  = dot(rgbM,  luma);",
                    "    float lumaMin = min(lumaM, min(min(lumaNW, lumaNE), min(lumaSW, lumaSE)));",
                    "    float lumaMax = max(lumaM, max(max(lumaNW, lumaNE), max(lumaSW, lumaSE)));",
                    "    vec2 dir;",
                    "    dir.x = -((lumaNW + lumaNE) - (lumaSW + lumaSE));",
                    "    dir.y =  ((lumaNW + lumaSW) - (lumaNE + lumaSE));",
                    "    float dirReduce = max((lumaNW + lumaNE + lumaSW + lumaSE) * (0.25 * FXAA_REDUCE_MUL), FXAA_REDUCE_MIN);",
                    "    float rcpDirMin = 1.0 / (min(abs(dir.x), abs(dir.y)) + dirReduce);",
                    "    dir = min(vec2(FXAA_SPAN_MAX, FXAA_SPAN_MAX), max(vec2(-FXAA_SPAN_MAX, -FXAA_SPAN_MAX), dir * rcpDirMin)) * res;",
                    "    vec3 rgbA = 0.5 * (",
                    "        texture2D(screenTexture, vUV + dir * (1.0 / 3.0 - 0.5)).rgb +",
                    "        texture2D(screenTexture, vUV + dir * (2.0 / 3.0 - 0.5)).rgb);",
                    "    vec3 rgbB = rgbA * 0.5 + 0.25 * (",
                    "        texture2D(screenTexture, vUV + dir * -0.5).rgb +",
                    "        texture2D(screenTexture, vUV + dir * 0.5).rgb);",
                    "    float lumaB = dot(rgbB, luma);",
                    "    if ((lumaB < lumaMin) || (lumaB > lumaMax)) {",
                    "        gl_FragColor = vec4(rgbA, 1.0);",
                    "    } else {",
                    "        gl_FragColor = vec4(rgbB, 1.0);",
                    "    }",
                    "}"
            });
        }
    }
}
