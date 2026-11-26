#include "postfx.hpp"
#include "postfx_shaders.hpp"

#include <android/log.h>
#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <cstring>

#define LOG_TAG "PostFX"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static constexpr int EFFECT_HDR = 3;
static constexpr int EFFECT_CRT = 2;
static constexpr int EFFECT_CAS_LITE = 7;
static constexpr int EFFECT_ADAPTIVE_SHARPEN = 14;
static constexpr int EFFECT_COLOR_ADJUST = 22;
static constexpr int EFFECT_FXAA = 23;
static constexpr int EFFECT_TOON = 24;
static constexpr int EFFECT_NTSC = 25;
static constexpr int EFFECT_RCAS = 26;
static constexpr int MAX_PRESET_ID = 21;

static bool parseBool(const std::string& value) {
    return value == "true" || strtof(value.c_str(), nullptr) != 0.0f;
}

// Upscale-inspired presets resample the image, so they run while scaling to the surface.
static bool isPresentPreset(int preset) {
    return preset == 18 || preset == 19 || preset == 20;
}

PostFX::Settings PostFX::Settings::parse(const std::string& config) {
    Settings s;
    size_t start = 0;

    while (start < config.size()) {
        size_t end = config.find(',', start);
        if (end == std::string::npos)
            end = config.size();

        std::string entry = config.substr(start, end - start);
        size_t eq = entry.find('=');
        if (eq != std::string::npos) {
            std::string key = entry.substr(0, eq);
            std::string value = entry.substr(eq + 1);
            float f = strtof(value.c_str(), nullptr);
            int i = (int)strtol(value.c_str(), nullptr, 10);

            if (key == "upscaler") s.upscaler = std::clamp(i, 0, 4);
            else if (key == "upscalerSharpness") s.upscalerSharpness = std::clamp(f, 0.0f, 1.0f);
            else if (key == "preset") s.preset = std::clamp(i, 0, MAX_PRESET_ID);
            else if (key == "presetStrength") s.presetStrength = std::clamp(f, 0.0f, 1.0f);
            else if (key == "brightness") s.brightness = std::clamp(f, -100.0f, 100.0f);
            else if (key == "contrast") s.contrast = std::clamp(f, -100.0f, 100.0f);
            else if (key == "gamma") s.gamma = std::clamp(f, 0.1f, 5.0f);
            else if (key == "hdr") s.hdr = parseBool(value);
            else if (key == "fxaa") s.fxaa = parseBool(value);
            else if (key == "toon") s.toon = parseBool(value);
            else if (key == "crt") s.crt = parseBool(value);
            else if (key == "ntsc") s.ntsc = parseBool(value);
            else if (key == "sharpenMode") s.sharpenMode = std::clamp(i, -1, 3);
            else if (key == "sharpenLevel") s.sharpenLevel = std::clamp(f, 1.0f, 5.0f);
            else if (key == "fitWindow") s.fitWindow = parseBool(value);
            else if (key == "frameGen") s.frameGen = parseBool(value);
            else if (key == "fgTarget") s.frameGenTarget = std::clamp(i, 0, 240);
            else if (key == "fgQuality") s.frameGenQuality = parseBool(value);
        }

        start = end + 1;
    }

    return s;
}

bool PostFX::Settings::hasColorAdjust() const {
    return brightness != 0.0f || contrast != 0.0f || gamma != 1.0f;
}

void PostFX::setConfig(const std::string& config) {
    std::lock_guard<std::mutex> lock(configLock);
    pendingConfig = config;
    configChanged = true;
}

void PostFX::applyPendingConfig() {
    std::string config;
    {
        std::lock_guard<std::mutex> lock(configLock);
        if (!configChanged)
            return;

        config = pendingConfig;
        configChanged = false;
    }

    settings = Settings::parse(config);
    rebuildPasses();
    LOGI("Applied config '%s' (%zu passes, upscaler %d)", config.c_str(), passes.size(), settings.upscaler);
}

void PostFX::rebuildPasses() {
    passes.clear();

    if (settings.hasColorAdjust())
        passes.push_back({EFFECT_COLOR_ADJUST, 0.0f});

    if (settings.preset > 0 && !isPresentPreset(settings.preset))
        passes.push_back({settings.preset, settings.presetStrength});

    if (settings.hdr) passes.push_back({EFFECT_HDR, 0.0f});
    if (settings.fxaa) passes.push_back({EFFECT_FXAA, 0.0f});
    if (settings.toon) passes.push_back({EFFECT_TOON, 0.0f});
    if (settings.crt) passes.push_back({EFFECT_CRT, 0.0f});
    if (settings.ntsc) passes.push_back({EFFECT_NTSC, 0.0f});

    float level01 = (settings.sharpenLevel - 1.0f) / 4.0f;
    switch (settings.sharpenMode) {
        case SHARPEN_RCAS:
        case SHARPEN_RCAS_VIBRANT:
            passes.push_back({EFFECT_RCAS, settings.sharpenLevel});
            break;
        case SHARPEN_CAS_LITE:
            passes.push_back({EFFECT_CAS_LITE, level01});
            break;
        case SHARPEN_ADAPTIVE:
            passes.push_back({EFFECT_ADAPTIVE_SHARPEN, level01});
            break;
        default:
            break;
    }
}

bool PostFX::isActive() {
    applyPendingConfig();
    return !passes.empty() || settings.upscaler != UPSCALER_LINEAR || isPresentPreset(settings.preset);
}

bool PostFX::fitGameWindow() {
    applyPendingConfig();
    return settings.fitWindow;
}

bool PostFX::frameGenerationQuality() {
    applyPendingConfig();
    return settings.frameGenQuality;
}

int PostFX::frameGenerationTarget() {
    applyPendingConfig();
    return settings.frameGenTarget;
}

bool PostFX::frameGeneration() {
    applyPendingConfig();
    return settings.frameGen;
}

void PostFX::init() {
    copyShader = std::make_unique<Shader>(PostFXShaders::present_vert, PostFXShaders::copy_frag);
    lanczosShader = std::make_unique<Shader>(PostFXShaders::present_vert, PostFXShaders::lanczos_frag);

    const char *version = reinterpret_cast<const char *>(glGetString(GL_VERSION));
    int major = 0, minor = 0;
    if (version && sscanf(version, "OpenGL ES %d.%d", &major, &minor) == 2)
        gather = major > 3 || (major == 3 && minor >= 1);

    if (gather) {
        // All stages of a program must share the same GLSL version.
        std::string vert = PostFXShaders::present_vert;
        vert.replace(vert.find("300 es"), 6, "310 es");
        sgsrShader = std::make_unique<Shader>(vert.c_str(), PostFXShaders::sgsr_frag);
        if (!sgsrShader->isValid())
            sgsrShader.reset();
    }

    LOGI("Initialized on %s (copy %d, lanczos %d, sgsr %d)", version ? version : "?",
         copyShader->isValid(), lanczosShader->isValid(), sgsrShader != nullptr);
    initialized = true;
}

// Compiled on first use and cached; returns nullptr if the variant failed to build.
Shader *PostFX::getEffectShader(int effectId, bool present) {
    auto& shaders = present ? presentEffectShaders : effectShaders;
    auto it = shaders.find(effectId);
    if (it == shaders.end()) {
        std::string frag = PostFXShaders::effects_frag;
        const std::string uniformDecl = "uniform int effectId;";
        frag.replace(frag.find(uniformDecl), uniformDecl.size(), "const int effectId = " + std::to_string(effectId) + ";");

        auto shader = std::make_unique<Shader>(present ? PostFXShaders::present_vert : PostFXShaders::pass_vert, frag.c_str());
        if (!shader->isValid())
            LOGE("Effect %d failed to compile", effectId);
        it = shaders.emplace(effectId, std::move(shader)).first;
    }
    return it->second->isValid() ? it->second.get() : nullptr;
}

bool PostFX::ensureTargets(int w, int h) {
    if (w == width && h == height && framebuffers[0] != 0)
        return true;

    destroyTargets();
    width = w;
    height = h;

    glGenTextures(2, textures);
    glGenFramebuffers(2, framebuffers);

    for (int i = 0; i < 2; i++) {
        glBindTexture(GL_TEXTURE_2D, textures[i]);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);

        glBindFramebuffer(GL_FRAMEBUFFER, framebuffers[i]);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, textures[i], 0);

        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
            LOGE("Offscreen framebuffer %dx%d is incomplete", w, h);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glBindTexture(GL_TEXTURE_2D, 0);
            destroyTargets();
            return false;
        }
    }

    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glBindTexture(GL_TEXTURE_2D, 0);
    return true;
}

void PostFX::destroyTargets() {
    if (framebuffers[0] != 0) {
        glDeleteFramebuffers(2, framebuffers);
        glDeleteTextures(2, textures);
    }

    framebuffers[0] = framebuffers[1] = 0;
    textures[0] = textures[1] = 0;
    width = height = 0;
}

bool PostFX::beginScene(int w, int h) {
    if (!initialized)
        init();

    if (!copyShader->isValid() || !ensureTargets(w, h))
        return false;

    current = 0;
    glBindFramebuffer(GL_FRAMEBUFFER, framebuffers[current]);
    glViewport(0, 0, width, height);
    glClear(GL_COLOR_BUFFER_BIT);
    return true;
}

void PostFX::runPass(const Pass& pass) {
    Shader *shader = getEffectShader(pass.effectId, false);
    if (!shader)
        return;

    int target = 1 - current;
    glBindFramebuffer(GL_FRAMEBUFFER, framebuffers[target]);
    glViewport(0, 0, width, height);

    shader->use();
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, textures[current]);
    setFilter(GL_LINEAR);
    setEffectUniforms(shader, pass);

    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    shader->disable();

    current = target;
}

void PostFX::setEffectUniforms(Shader *shader, const Pass& pass) {
    float stops;
    if (pass.strength <= 1.1f) stops = 1.6f;
    else if (pass.strength <= 2.1f) stops = 1.0f;
    else if (pass.strength <= 3.1f) stops = 0.5f;
    else if (pass.strength <= 4.1f) stops = 0.2f;
    else stops = 0.0f;

    glUniform1i(shader->getUniformLoc("texSampler"), 0);
    glUniform1i(shader->getUniformLoc("effectId"), pass.effectId);
    glUniform1f(shader->getUniformLoc("strength"), pass.strength);
    glUniform2f(shader->getUniformLoc("resolution"), (float)width, (float)height);
    glUniform1f(shader->getUniformLoc("brightness"), settings.brightness / 100.0f);
    glUniform1f(shader->getUniformLoc("contrast"), std::clamp(settings.contrast / 100.0f, 0.0f, 2.0f));
    glUniform1f(shader->getUniformLoc("gamma"), settings.gamma);
    glUniform1f(shader->getUniformLoc("rcasStops"), stops);
    glUniform1f(shader->getUniformLoc("rcasSaturation"), settings.sharpenMode == SHARPEN_RCAS_VIBRANT ? 1.2f : 1.0f);
    glUniform1i(shader->getUniformLoc("frameCount"), frameCount);
}

void PostFX::applyEffects() {
    glDisable(GL_BLEND);
    for (const auto& pass : passes)
        runPass(pass);

    glBindTexture(GL_TEXTURE_2D, 0);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glEnable(GL_BLEND);
    frameCount++;
}

void PostFX::presentTexture(GLuint texture, float xform[], float viewWidth, float viewHeight) {
    GLuint scene = textures[current];
    textures[current] = texture;
    present(xform, viewWidth, viewHeight);
    textures[current] = scene;
}

void PostFX::present(float xform[], float viewWidth, float viewHeight) {
    Shader *shader = copyShader.get();
    int upscaler = settings.upscaler;
    Shader *presetShader = isPresentPreset(settings.preset) ? getEffectShader(settings.preset, true) : nullptr;
    bool presetPass = presetShader != nullptr;

    if (presetPass)
        shader = presetShader;
    else if (upscaler == UPSCALER_LANCZOS && lanczosShader->isValid())
        shader = lanczosShader.get();
    else if ((upscaler == UPSCALER_SGSR || upscaler == UPSCALER_FSR) && sgsrShader)
        shader = sgsrShader.get();

    glDisable(GL_BLEND);
    shader->use();
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, textures[current]);
    setFilter(upscaler == UPSCALER_NEAREST ? GL_NEAREST : GL_LINEAR);

    glUniform1i(shader->getUniformLoc("texSampler"), 0);
    glUniform1fv(shader->getUniformLoc("xform"), 6, xform);
    glUniform2f(shader->getUniformLoc("viewSize"), viewWidth, viewHeight);
    glUniform2f(shader->getUniformLoc("srcSize"), (float)width, (float)height);

    if (presetPass) {
        setFilter(GL_LINEAR);
        setEffectUniforms(shader, {settings.preset, settings.presetStrength});
    }
    else if (shader == sgsrShader.get()) {
        bool fsr = upscaler == UPSCALER_FSR;
        glUniform1f(shader->getUniformLoc("sharpness"), settings.upscalerSharpness);
        glUniform1f(shader->getUniformLoc("edgeThreshold"), (fsr ? 8.0f : 12.0f) / 255.0f);
        glUniform2f(shader->getUniformLoc("spatialRange"), fsr ? 0.52f : 0.40f, fsr ? 0.86f : 0.65f);
        glUniform2f(shader->getUniformLoc("edgeSharpnessRange"), fsr ? 1.35f : 1.0f, fsr ? 2.75f : 2.0f);
        glUniform2f(shader->getUniformLoc("maxDeltaRange"), (fsr ? 28.0f : 16.0f) / 255.0f, (fsr ? 64.0f : 40.0f) / 255.0f);
    }

    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    shader->disable();
    glBindTexture(GL_TEXTURE_2D, 0);
    glEnable(GL_BLEND);
}

void PostFX::setFilter(GLint filter) {
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter);
}

void PostFX::destroy() {
    destroyTargets();
    effectShaders.clear();
    presentEffectShaders.clear();
    copyShader.reset();
    lanczosShader.reset();
    sgsrShader.reset();
    initialized = false;
    gather = false;
}
