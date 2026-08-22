package com.winlator.cmod.renderer;

import android.opengl.GLES20;
import com.winlator.cmod.renderer.effects.Effect;
import com.winlator.cmod.renderer.effects.ToonEffect;
import com.winlator.cmod.renderer.effects.HDREffect;
import com.winlator.cmod.renderer.effects.FSREffect;
import com.winlator.cmod.renderer.material.ShaderMaterial;

import java.util.ArrayList;
import java.util.List;

public class EffectComposer {
    private boolean isRendering = false;
    private final List<Effect> effects = new ArrayList<>();
    private RenderTarget readBuffer;
    private RenderTarget writeBuffer;
    private final GLRenderer renderer;
    private int frameCount = 0;

    public EffectComposer(GLRenderer renderer) {
        this.renderer = renderer;
    }

    private void initBuffers() {
        int width = renderer.getSurfaceWidth();
        int height = renderer.getSurfaceHeight();

        if (readBuffer == null) readBuffer = new RenderTarget();
        if (readBuffer.getWidth() != width || readBuffer.getHeight() != height) {
            readBuffer.setFormat(GLES20.GL_RGBA);
            readBuffer.allocateFramebuffer(width, height);
        }

        if (writeBuffer == null) writeBuffer = new RenderTarget();
        if (writeBuffer.getWidth() != width || writeBuffer.getHeight() != height) {
            writeBuffer.setFormat(GLES20.GL_RGBA);
            writeBuffer.allocateFramebuffer(width, height);
        }
    }

    public synchronized void addEffect(Effect effect) {
        if (!effects.contains(effect)) {
            effects.add(effect);
        }
        renderer.xServerView.requestRender();
    }

    public synchronized <T extends Effect> T getEffect(Class<T> effectClass) {
        for (Effect effect : effects) {
            if (effect.getClass() == effectClass) {
                return effectClass.cast(effect);
            }
        }
        return null;
    }

    public synchronized boolean hasEffects() {
        return !effects.isEmpty();
    }

    public synchronized void removeEffect(Effect effect) {
        if (effects.remove(effect)) {
            effect.destroy();
        }
        renderer.xServerView.requestRender();
    }

    public synchronized RenderTarget getReadBuffer() {
        return readBuffer;
    }

    public synchronized RenderTarget getWriteBuffer() {
        return writeBuffer;
    }

    public synchronized void render() {
        if (isRendering) return;
        isRendering = true;

        initBuffers();

        // 1. Draw game scene into offscreen readBuffer
        renderer.setRenderCursorEnabled(false);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, readBuffer.getFramebuffer());
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
        GLES20.glViewport(0, 0, renderer.surfaceWidth, renderer.surfaceHeight);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        renderer.drawFrame();
        renderer.setRenderCursorEnabled(true);

        // 2. Dispatch Native Apex Frame Generation or Normal Passthrough
        if (ApexNativeBridge.nativeIsActive()) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
            GLES20.glViewport(0, 0, renderer.surfaceWidth, renderer.surfaceHeight);

            ApexNativeBridge.nativeProcessFrame(
                readBuffer.getTextureId(),
                0, // Target default screen framebuffer
                renderer.surfaceWidth,
                renderer.surfaceHeight
            );
        } else if (hasEffects()) {
            for (int i = 0; i < effects.size(); i++) {
                Effect effect = effects.get(i);
                boolean renderToScreen = (i == effects.size() - 1);
                int targetFramebuffer = renderToScreen ? 0 : writeBuffer.getFramebuffer();

                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, targetFramebuffer);
                GLES20.glViewport(0, 0, renderer.surfaceWidth, renderer.surfaceHeight);

                if (renderToScreen && !renderer.isFullscreen()) {
                    GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
                    GLES20.glScissor(renderer.viewTransformation.viewOffsetX, renderer.viewTransformation.viewOffsetY,
                                     renderer.viewTransformation.viewWidth, renderer.viewTransformation.viewHeight);
                } else {
                    GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
                }

                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                renderEffect(effect);

                if (!renderToScreen) swapBuffers();
            }
        } else {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            if (!renderer.isFullscreen()) {
                GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
                GLES20.glScissor(renderer.viewTransformation.viewOffsetX, renderer.viewTransformation.viewOffsetY,
                                 renderer.viewTransformation.viewWidth, renderer.viewTransformation.viewHeight);
                GLES20.glViewport(0, 0, renderer.surfaceWidth, renderer.surfaceHeight);
            } else {
                GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
                GLES20.glViewport(0, 0, renderer.surfaceWidth, renderer.surfaceHeight);
            }
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            renderEffect(null);
        }

        // 3. Render hardware cursor on top
        renderer.drawCursorExplicitly();
        renderer.viewportNeedsUpdate = true;
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST);

        isRendering = false;
    }

    private void renderEffect(Effect effect) {
        ShaderMaterial material = effect != null ? effect.getMaterial() : renderer.getPassthroughMaterial();
        if (material == null) return;

        material.use();
        renderer.getQuadVertices().bind(material.programId);
        material.setUniformVec2("resolution", renderer.surfaceWidth, renderer.surfaceHeight);
        material.setUniformInt("FrameCount", frameCount++);
        material.setUniformVec2("TextureSize", readBuffer.getWidth(), readBuffer.getHeight());

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, readBuffer.getTextureId());
        material.setUniformInt("screenTexture", 0);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, renderer.quadVertices.count());
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
    }

    private void swapBuffers() {
        RenderTarget tmp = writeBuffer;
        writeBuffer = readBuffer;
        readBuffer = tmp;
    }

    public synchronized void toggleToonEffect() {
        ToonEffect toonEffect = getEffect(ToonEffect.class);
        if (toonEffect != null) {
            removeEffect(toonEffect);
        } else {
            addEffect(new ToonEffect());
        }
        renderer.xServerView.requestRender();
    }

    public synchronized void toggleHDREffect(boolean enabled) {
        HDREffect hdrEffect = getEffect(HDREffect.class);
        if (hdrEffect != null) {
            if (!enabled) removeEffect(hdrEffect);
        } else if (enabled) {
            addEffect(new HDREffect());
        }
        renderer.xServerView.requestRender();
    }

    public synchronized void updateFSREffect(boolean enabled, int mode, float level) {
        FSREffect fsrEffect = getEffect(FSREffect.class);
        if (fsrEffect != null) {
            if (!enabled) {
                removeEffect(fsrEffect);
                return;
            }
        } else if (enabled) {
            fsrEffect = new FSREffect();
            addEffect(fsrEffect);
        }

        if (fsrEffect != null) {
            fsrEffect.setMode(mode);
            fsrEffect.setLevel(level);
        }
        renderer.xServerView.requestRender();
    }
}
