#pragma once

#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

#include "shader.hpp"

// Post-processing chain for the EGL renderer:
// scene -> color adjust -> color preset -> HDR -> FXAA -> Toon -> CRT -> NTSC -> sharpen -> upscaler -> surface.
// Everything runs at scene resolution except the final upscaling pass. The scene is the X screen,
// or the game window alone when it is smaller than the screen and fitWindow is set.
class PostFX {
    public:
        enum Upscaler {
            UPSCALER_LINEAR = 0,
            UPSCALER_NEAREST = 1,
            UPSCALER_LANCZOS = 2,
            UPSCALER_SGSR = 3,
            UPSCALER_FSR = 4
        };

        enum SharpenMode {
            SHARPEN_OFF = -1,
            SHARPEN_RCAS = 0,
            SHARPEN_RCAS_VIBRANT = 1,
            SHARPEN_CAS_LITE = 2,
            SHARPEN_ADAPTIVE = 3
        };

        struct Settings {
            int upscaler = UPSCALER_LINEAR;
            float upscalerSharpness = 0.5f;
            int preset = 0;
            float presetStrength = 0.65f;
            float brightness = 0.0f;
            float contrast = 0.0f;
            float gamma = 1.0f;
            bool hdr = false;
            bool fxaa = false;
            bool toon = false;
            bool crt = false;
            bool ntsc = false;
            int sharpenMode = SHARPEN_OFF;
            float sharpenLevel = 3.0f;
            bool fitWindow = false;
            bool frameGen = false;
            int frameGenTarget = 0;  // the output frame rate the multiplier stays under (0: always 2x)

            static Settings parse(const std::string& config);
            bool hasColorAdjust() const;
        };

        // Called from any thread; applied on the render thread at the next frame.
        void setConfig(const std::string& config);

        // Render thread only. Call order per frame: beginScene() -> draw windows ->
        // applyEffects() -> bind/viewport the window surface -> present().
        bool isActive();
        bool fitGameWindow();
        bool frameGeneration();
        int frameGenerationTarget();
        bool beginScene(int width, int height);
        void applyEffects();
        void present(float xform[], float viewWidth, float viewHeight);
        // Frame generation: the finished scene (after applyEffects), and present() of another picture of the same size.
        GLuint sceneTexture() const { return textures[current]; }
        int sceneWidth() const { return width; }
        int sceneHeight() const { return height; }
        void presentTexture(GLuint texture, float xform[], float viewWidth, float viewHeight);
        void destroy();

    private:
        struct Pass {
            int effectId;
            float strength;
        };

        std::mutex configLock;
        std::string pendingConfig;
        bool configChanged = false;
        Settings settings;
        std::vector<Pass> passes;

        bool initialized = false;
        bool gather = false;
        int width = 0;
        int height = 0;
        GLuint framebuffers[2] = {0, 0};
        GLuint textures[2] = {0, 0};
        int current = 0;
        int frameCount = 0;

        // One program per effect, with effectId compiled in as a constant: the shared uber-shader would
        // hold the register budget of its heaviest effect and halve GPU occupancy for every pass.
        std::unordered_map<int, std::unique_ptr<Shader>> effectShaders;
        std::unordered_map<int, std::unique_ptr<Shader>> presentEffectShaders;
        std::unique_ptr<Shader> copyShader;
        std::unique_ptr<Shader> lanczosShader;
        std::unique_ptr<Shader> sgsrShader;

        void applyPendingConfig();
        void rebuildPasses();
        void init();
        bool ensureTargets(int width, int height);
        void destroyTargets();
        Shader *getEffectShader(int effectId, bool present);
        void runPass(const Pass& pass);
        void setEffectUniforms(Shader *shader, const Pass& pass);
        void setFilter(GLint filter);
};
