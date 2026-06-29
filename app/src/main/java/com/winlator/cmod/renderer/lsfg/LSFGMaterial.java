package com.winlator.cmod.renderer.lsfg;

import android.opengl.GLES20;
import com.winlator.cmod.renderer.material.ScreenMaterial;

public class LSFGMaterial extends ScreenMaterial {
    private final LSFGEffect effect;

    public LSFGMaterial(LSFGEffect effect) {
        this.effect = effect;
        setUniformNames("resolution", "screenTexture", "previousCapturedTexture", "currentCapturedTexture", "motionVectorTexture", "interpolationFactor", "qualityMode", "uBlurIntensity");
    }

    @Override
    protected String getVertexShader() {
        return "#version 300 es\n" +
                "in vec2 position;\n" +
                "out vec2 vUV;\n" +
                "void main() {\n" +
                "    vUV = position;\n" +
                "    gl_Position = vec4(position.x * 2.0 - 1.0, position.y * 2.0 - 1.0, 0.0, 1.0);\n" +
                "}";
    }

    @Override
    protected String getFragmentShader() {
        return "#version 300 es\n" +
                "precision mediump float;\n" +
                "uniform sampler2D screenTexture;\n" +
                "uniform sampler2D previousCapturedTexture;\n" +
                "uniform sampler2D currentCapturedTexture;\n" +
                "uniform sampler2D motionVectorTexture;\n" +
                "uniform vec2 resolution;\n" +
                "uniform float interpolationFactor;\n" +
                "uniform float qualityMode;\n" +
                "uniform float uBlurIntensity;\n" +
                "in vec2 vUV;\n" +
                "out vec4 outColor;\n" +
                "\n" +
                "void main() {\n" +
                "    if (interpolationFactor < 0.01) {\n" +
                "        outColor = vec4(texture(previousCapturedTexture, vUV).rgb, 1.0);\n" +
                "        return;\n" +
                "    }\n" +
                "    vec3 curr = texture(currentCapturedTexture, vUV).rgb;\n" +
                "    if (interpolationFactor > 0.99) {\n" +
                "        outColor = vec4(curr, 1.0);\n" +
                "        return;\n" +
                "    }\n" +
                "\n" +
                "    vec3 prev = texture(previousCapturedTexture, vUV).rgb;\n" +
                "    vec4 mvData = texture(motionVectorTexture, vUV);\n" +
                "    vec2 mv = mvData.rg;\n" +
                "    float confidence = mvData.b;\n" +
                "    \n" +
                "    // --- Ultra-Fast Reflex Shading ---\n" +
                "    // Simplified warping for maximum Raw performance\n" +
                "    vec3 warpedPrev = texture(previousCapturedTexture, vUV + mv * interpolationFactor).rgb;\n" +
                "    vec3 warpedCurr = texture(currentCapturedTexture, vUV - mv * (1.0 - interpolationFactor)).rgb;\n" +
                "    \n" +
                "    vec3 result = mix(warpedPrev, warpedCurr, interpolationFactor);\n" +
                "    \n" +
                "    // Ghosting fallback\n" +
                "    float ghostFactor = clamp((0.1 - confidence) * 4.0, 0.0, 1.0);\n" +
                "    if (ghostFactor > 0.0) result = mix(result, mix(prev, curr, interpolationFactor), ghostFactor);\n" +
                "    \n" +
                "    outColor = vec4(result, 1.0);\n" +
                "}";
    }

    @Override
    public void use() {
        super.use();
        
        int prevTexId = effect.getPreviousTextureId();
        if (prevTexId > 0) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, prevTexId);
            setUniformInt("previousCapturedTexture", 1);
        }

        int currTexId = effect.getCurrentTextureId();
        if (currTexId > 0) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, currTexId);
            setUniformInt("currentCapturedTexture", 2);
        }

        int mvTexId = effect.getMotionVectorTexture();
        if (mvTexId > 0) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE3);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mvTexId);
            setUniformInt("motionVectorTexture", 3);
        }

        setUniformFloat("interpolationFactor", effect.getManager().getInterpolationFactor());
        setUniformFloat("qualityMode", (float)effect.getQuality());
        setUniformFloat("uBlurIntensity", effect.getSharpenAmount());
    }
}
