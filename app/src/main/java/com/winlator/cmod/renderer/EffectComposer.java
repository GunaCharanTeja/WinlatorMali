package com.winlator.cmod.renderer;

import android.opengl.GLES20;
import android.util.Log;

import com.winlator.cmod.renderer.effects.Effect;
import com.winlator.cmod.renderer.effects.ToonEffect;
import com.winlator.cmod.renderer.lsfg.LSFGEffect;
import com.winlator.cmod.renderer.material.ShaderMaterial;

import java.util.ArrayList;
import java.util.List;

public class EffectComposer {
    private static final String TAG = "EffectComposer";
    private boolean isRendering = false;
    private final List<Effect> effects = new ArrayList<>();
    private RenderTarget readBuffer;
    private RenderTarget writeBuffer;
    private final GLRenderer renderer;
    private Boolean supportsGLES31Cache = null;

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

        LSFGEffect lsfgEffect = getEffect(LSFGEffect.class);
        boolean isGenerated = lsfgEffect != null && lsfgEffect.getManager().isGeneratedFrame();

        initBuffers();

        if (!isGenerated) {
            // Disable cursor rendering during off-screen game capture to avoid generator warping artifacts
            renderer.setRenderCursorEnabled(false);

            // Draw game to readBuffer at surface resolution
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, readBuffer.getFramebuffer());
            GLES20.glViewport(0, 0, renderer.surfaceWidth, renderer.surfaceHeight);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            renderer.drawFrame();
            
            // Restore cursor rendering
            renderer.setRenderCursorEnabled(true);
            
            // Capture for Apex
            if (lsfgEffect != null) lsfgEffect.onPreRender(readBuffer, null);
        } else {
            // Process Apex Logic (Compute pass)
            if (lsfgEffect != null) lsfgEffect.onPreRender(readBuffer, null);
        }

        if (hasEffects()) {
            for (int i = 0; i < effects.size(); i++) {
                Effect effect = effects.get(i);
                boolean renderToScreen = (i == effects.size() - 1);
                int targetFramebuffer = renderToScreen ? 0 : writeBuffer.getFramebuffer();

                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, targetFramebuffer);
                
                // If rendering to screen, respect the renderer's intended viewport (Non-fullscreen support)
                if (renderToScreen && !renderer.isFullscreen()) {
                    GLES20.glViewport(renderer.viewTransformation.viewOffsetX, renderer.viewTransformation.viewOffsetY, 
                                      renderer.viewTransformation.viewWidth, renderer.viewTransformation.viewHeight);
                } else {
                    GLES20.glViewport(0, 0, renderer.surfaceWidth, renderer.surfaceHeight);
                }
                
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                renderEffect(effect);

                if (!renderToScreen) swapBuffers();
            }
        }

        // Draw the cursor explicitly on the final screen buffer to ensure 100% responsive, lag-free mouse feel
        if (lsfgEffect != null && lsfgEffect.getManager().isActive()) {
            renderer.drawCursorExplicitly();
        }

        isRendering = false;
    }

    private int frameCount = 0;

    private void renderEffect(Effect effect) {
        ShaderMaterial material = effect.getMaterial();
        if (material == null) return;

        material.use();
        renderer.getQuadVertices().bind(material.programId);
        material.setUniformVec2("resolution", renderer.surfaceWidth, renderer.surfaceHeight);
        
        // Pass extra uniforms if the shader requires them (e.g. NTSC effect)
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

    public synchronized void toggleLSFGEffect(boolean enabled) {
        LSFGEffect lsfgEffect = getEffect(LSFGEffect.class);
        if (lsfgEffect != null) {
            if (!enabled) {
                lsfgEffect.getManager().setEnabled(false);
                removeEffect(lsfgEffect);
            }
        } else if (enabled) {
            if (supportsGLES31Cache == null) {
                try {
                    String version = GLES20.glGetString(GLES20.GL_VERSION);
                    if (version != null) {
                        supportsGLES31Cache = version.contains("OpenGL ES 3.1") || version.contains("OpenGL ES 3.2");
                    } else if (renderer.xServerView.getContext() != null) {
                        android.app.ActivityManager activityManager = (android.app.ActivityManager) renderer.xServerView.getContext().getSystemService(android.content.Context.ACTIVITY_SERVICE);
                        if (activityManager != null) {
                            android.content.pm.ConfigurationInfo configInfo = activityManager.getDeviceConfigurationInfo();
                            supportsGLES31Cache = configInfo.reqGlEsVersion >= 0x00030001;
                        }
                    }
                } catch (Exception e) {
                    supportsGLES31Cache = false;
                }
                if (supportsGLES31Cache == null) supportsGLES31Cache = false;
            }
            
            if (supportsGLES31Cache) {
                lsfgEffect = new LSFGEffect(renderer, renderer.getLSFGManager());
                addEffect(lsfgEffect);
                lsfgEffect.getManager().setEnabled(true);
            } else {
                Log.e(TAG, "GLES 3.1 not supported, cannot enable LSFG");
            }
        }
    }
}
