package com.winlator.cmod.renderer.lsfg;

import android.opengl.GLES20;
import android.opengl.GLES31;
import com.winlator.cmod.renderer.GLRenderer;
import com.winlator.cmod.renderer.RenderTarget;
import com.winlator.cmod.renderer.effects.Effect;
import com.winlator.cmod.renderer.material.ShaderMaterial;

public class LSFGEffect extends Effect {
    private final GLRenderer renderer;
    private final LSFGManager manager;
    private final LSFGComputeMaterial computeMaterial = new LSFGComputeMaterial();
    private int motionVectorTexture = 0;
    private int mvHistoryTexture = 0;
    private int mvWidth = 0;
    private int mvHeight = 0;
    private int quality = 1;
    private float sharpenAmount = 0.5f;

    private final RenderTarget[] frameBuffers = new RenderTarget[]{new RenderTarget(), new RenderTarget()};
    private int currentFrameIndex = 0;

    public LSFGEffect(GLRenderer renderer, LSFGManager manager) {
        this.renderer = renderer;
        this.manager = manager;
    }

    @Override
    protected ShaderMaterial createMaterial() {
        return new LSFGMaterial(this);
    }

    public LSFGManager getManager() {
        return manager;
    }

    public int getQuality() {
        return quality;
    }

    public void setQuality(int quality) {
        this.quality = quality;
    }

    public float getSharpenAmount() {
        return sharpenAmount;
    }

    public void setSharpenAmount(float sharpenAmount) {
        this.sharpenAmount = sharpenAmount;
    }

    public int getMotionVectorTexture() {
        return motionVectorTexture;
    }

    public int getCurrentTextureId() {
        return frameBuffers[currentFrameIndex].getTextureId();
    }

    public int getPreviousTextureId() {
        return frameBuffers[1 - currentFrameIndex].getTextureId();
    }

    @Override
    public void onPreRender(RenderTarget inputBuffer, RenderTarget unused) {
        if (!manager.isActive()) return;

        int width = renderer.surfaceWidth;
        int height = renderer.surfaceHeight;

        // Ensure buffers are ready
        if (frameBuffers[0].getTextureId() == 0 || frameBuffers[0].getWidth() != width || frameBuffers[0].getHeight() != height) {
            for (RenderTarget rb : frameBuffers) {
                rb.setFormat(GLES20.GL_RGBA);
                rb.setMinFilter(GLES20.GL_LINEAR);
                rb.allocateFramebuffer(width, height);
            }
        }

        // Capture new frame only when the game has produced one
        if (!manager.isGeneratedFrame()) {
            currentFrameIndex = 1 - currentFrameIndex;
            copyToBuffer(inputBuffer, frameBuffers[currentFrameIndex]);
            manager.onFrameCaptured();

            // --- Performance Optimization: Staggered Compute ---
            // On very weak GPUs, we can skip compute on some frames to save raw power
            // since motion vectors have temporal stability.
            int currTex = frameBuffers[currentFrameIndex].getTextureId();
            int prevTex = frameBuffers[1 - currentFrameIndex].getTextureId();
            if (prevTex > 0 && manager.getRealFramesCaptured() >= 2) {
                runComputePass(currTex, prevTex, width, height);
            }
        }
    }

    private void copyToBuffer(RenderTarget src, RenderTarget dst) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, dst.getTextureId());
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, src.getFramebuffer());
        GLES20.glCopyTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, 0, 0, renderer.surfaceWidth, renderer.surfaceHeight);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
    }

    public void runComputePass(int currTex, int prevTex, int width, int height) {
        // --- Anti-Raw Impact Scaling ---
        // Scale down resolution based on quality (3x downscale for High Quality, 4x for others) to fit Mali-G610 MC4 capacity
        int scale = (quality == 2) ? 3 : 4;
        int workWidth = width / scale;
        int workHeight = height / scale;
        ensureMVTextures(workWidth, workHeight);

        int tmp = mvHistoryTexture;
        mvHistoryTexture = motionVectorTexture;
        motionVectorTexture = tmp;

        computeMaterial.use(quality);
        if (computeMaterial.programId == 0) return;

        GLES20.glActiveTexture(GLES20.GL_TEXTURE4);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, currTex);
        GLES31.glUniform1i(GLES31.glGetUniformLocation(computeMaterial.programId, "currFrame"), 4);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE5);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, prevTex);
        GLES31.glUniform1i(GLES31.glGetUniformLocation(computeMaterial.programId, "prevFrame"), 5);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE6);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mvHistoryTexture);
        GLES31.glUniform1i(GLES31.glGetUniformLocation(computeMaterial.programId, "mvHistoryTexture"), 6);

        GLES31.glBindImageTexture(0, motionVectorTexture, 0, false, 0, GLES31.GL_WRITE_ONLY, GLES31.GL_RGBA16F);

        int groupsX = (workWidth + 15) / 16;
        int groupsY = (workHeight + 7) / 8;
        GLES31.glDispatchCompute(groupsX, groupsY, 1);
        GLES31.glMemoryBarrier(GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
    }

    private void ensureMVTextures(int width, int height) {
        if (motionVectorTexture != 0 && (mvWidth != width || mvHeight != height)) {
            GLES20.glDeleteTextures(2, new int[]{motionVectorTexture, mvHistoryTexture}, 0);
            motionVectorTexture = 0;
        }

        if (motionVectorTexture == 0) {
            int[] tex = new int[2];
            GLES20.glGenTextures(2, tex, 0);
            
            int[] fbo = new int[1];
            GLES20.glGenFramebuffers(1, fbo, 0);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0]);
            
            for (int i = 0; i < 2; i++) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[i]);
                GLES31.glTexStorage2D(GLES20.GL_TEXTURE_2D, 1, GLES31.GL_RGBA16F, width, height);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                
                // Clear newly allocated textures to prevent temporal NaN/garbage propagation
                GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, tex[i], 0);
                GLES20.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            }
            
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            GLES20.glDeleteFramebuffers(1, fbo, 0);
            
            motionVectorTexture = tex[0];
            mvHistoryTexture = tex[1];
            mvWidth = width;
            mvHeight = height;
        }
    }

    public void resetGLResources() {
        motionVectorTexture = 0;
        mvHistoryTexture = 0;
        mvWidth = 0;
        mvHeight = 0;
        for (RenderTarget rb : frameBuffers) {
            rb.destroy();
        }
        computeMaterial.destroy();
        if (getMaterial() != null) {
            getMaterial().destroy();
        }
    }

    @Override
    public void destroy() {
        super.destroy();
        computeMaterial.destroy();
        for (RenderTarget rb : frameBuffers) rb.destroy();
        if (motionVectorTexture != 0) {
            GLES20.glDeleteTextures(2, new int[]{motionVectorTexture, mvHistoryTexture}, 0);
        }
    }
}
