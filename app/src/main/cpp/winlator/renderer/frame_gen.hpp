#pragma once

#ifndef VK_USE_PLATFORM_ANDROID_KHR
#define VK_USE_PLATFORM_ANDROID_KHR
#endif
#ifndef EGL_EGLEXT_PROTOTYPES
#define EGL_EGLEXT_PROTOTYPES
#endif
#ifndef GL_GLEXT_PROTOTYPES
#define GL_GLEXT_PROTOTYPES
#endif

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <android/hardware_buffer.h>
#include <vulkan/vulkan.h>

#include <memory>

#include "zerofg/zerofg.h"

// Frame generation for the EGL renderer: the scene of each real frame is copied into a hardware buffer shared with a
// Vulkan device, the ZeroFG engine (ReallyZero, Mali form) builds the frame halfway between the last two real ones
// into a second shared buffer, and the renderer shows it as a GL texture before the newer real frame.
// Render thread only (its GL context current).
class FrameGen {
    public:
        ~FrameGen();

        // The scene of a new real frame (a GL texture of width x height). True when there is a pair (the previous real
        // frame and this one) to generate from.
        bool pushReal(EGLDisplay display, GLuint scene, int width, int height);
        // The frame at `phase` (0..1) between the pair into generatedTexture(). The first call for a pair analyses it
        // (~5 ms at 1280 x 720 on a Mali-G615); further phases of the same pair only resolve (~2 ms).
        bool generate(float phase);
        float generateMs() const { return lastGenerateMs; }
        GLuint generatedTexture() const { return output.texture; }
        // A cut, a pause or a size change: the next real frame starts over (no frame is generated from across it).
        void reset() { havePrevious = false; }
        void destroy();
        bool failed() const { return broken; }

    private:
        struct Shared {
            AHardwareBuffer *buffer = nullptr;
            VkImage image = VK_NULL_HANDLE;
            VkDeviceMemory memory = VK_NULL_HANDLE;
            VkImageView view = VK_NULL_HANDLE;
            VkImageUsageFlags usage = 0;
            EGLImageKHR eglImage = EGL_NO_IMAGE_KHR;
            GLuint texture = 0;
            GLuint framebuffer = 0;
            uint64_t sequence = 0;
        };

        bool broken = false;
        float lastGenerateMs = 0;
        bool noVulkan = false;
        int failedWidth = 0, failedHeight = 0;
        bool vulkanReady = false;
        EGLDisplay eglDisplay = EGL_NO_DISPLAY;
        int width = 0, height = 0;
        Shared slots[2];
        Shared output;
        int current = 0;
        bool havePrevious = false;
        uint64_t sequence = 0;
        GLuint readFramebuffer = 0;

        VkInstance instance = VK_NULL_HANDLE;
        VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
        VkDevice device = VK_NULL_HANDLE;
        VkQueue queue = VK_NULL_HANDLE;
        uint32_t family = 0;
        uint32_t foreignFamily = VK_QUEUE_FAMILY_EXTERNAL;
        VkCommandPool pool = VK_NULL_HANDLE;
        VkCommandBuffer cmd = VK_NULL_HANDLE;
        VkFence fence = VK_NULL_HANDLE;
        PFN_vkGetAndroidHardwareBufferPropertiesANDROID getBufferProperties = nullptr;
        PFN_vkGetPhysicalDeviceFeatures2 getFeatures2 = nullptr;
        PFN_vkGetPhysicalDeviceProperties2 getProperties2 = nullptr;
        PFN_vkGetPhysicalDeviceImageFormatProperties2 getImageFormatProperties2 = nullptr;
        // GPU-side sync with GL through Android sync files (VK_KHR_external_semaphore_fd).
        bool syncFiles = false;
        VkSemaphore glDone = VK_NULL_HANDLE;  // GL's work before a generation (imported from a native fence)
        VkSemaphore vkDone = VK_NULL_HANDLE;  // a generation done (exported to GL)
        PFN_vkImportSemaphoreFdKHR importSemaphoreFd = nullptr;
        PFN_vkGetSemaphoreFdKHR getSemaphoreFd = nullptr;
        PFNEGLDUPNATIVEFENCEFDANDROIDPROC dupNativeFenceFd = nullptr;
        VkQueryPool timestamps = VK_NULL_HANDLE;
        bool timestampsWritten = false;
        float timestampPeriod = 1.0f;
        std::unique_ptr<zerofg::Interpolator> engine;

        bool initVulkan();
        bool resize(int width, int height);
        bool createShared(Shared &shared, uint32_t ahbFormat, VkFormat format, bool output);
        void destroyShared(Shared &shared);
        void destroySized();
        bool record(float phase, VkSemaphore wait);
};
