#include "frame_gen.hpp"

#include <android/log.h>
#include <GLES2/gl2ext.h>

#include <chrono>
#include <cstring>
#include <unistd.h>
#include <vector>

#include "zerofg/zerofg.h"

#define FG_LOG(...) __android_log_print(ANDROID_LOG_INFO, "FrameGen", __VA_ARGS__)

namespace {

bool HasExtension(const std::vector<VkExtensionProperties> &extensions, const char *name) {
    for (const auto &e : extensions)
        if (!strcmp(e.extensionName, name)) return true;
    return false;
}

}  // namespace

FrameGen::~FrameGen() {
    destroy();
}

bool FrameGen::initVulkan() {
    VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
    app.pApplicationName = "Winlator FrameGen";
    app.apiVersion = VK_API_VERSION_1_3;
    VkInstanceCreateInfo ici{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    ici.pApplicationInfo = &app;
    if (vkCreateInstance(&ici, nullptr, &instance) != VK_SUCCESS) return false;
    // Vulkan 1.1 entry points: the libvulkan of the minimum API level does not export them.
    getFeatures2 = (PFN_vkGetPhysicalDeviceFeatures2)vkGetInstanceProcAddr(instance, "vkGetPhysicalDeviceFeatures2");
    getProperties2 = (PFN_vkGetPhysicalDeviceProperties2)vkGetInstanceProcAddr(instance, "vkGetPhysicalDeviceProperties2");
    getImageFormatProperties2 = (PFN_vkGetPhysicalDeviceImageFormatProperties2)vkGetInstanceProcAddr(instance, "vkGetPhysicalDeviceImageFormatProperties2");
    if (!getFeatures2 || !getProperties2 || !getImageFormatProperties2) return false;

    uint32_t count = 1;
    if (vkEnumeratePhysicalDevices(instance, &count, &physicalDevice) < 0 || count == 0) return false;
    VkPhysicalDeviceProperties props;
    vkGetPhysicalDeviceProperties(physicalDevice, &props);
    if (props.apiVersion < VK_API_VERSION_1_3) {
        FG_LOG("Vulkan 1.3 is required (device has %u.%u)", VK_VERSION_MAJOR(props.apiVersion), VK_VERSION_MINOR(props.apiVersion));
        return false;
    }

    uint32_t families = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, &families, nullptr);
    std::vector<VkQueueFamilyProperties> familyProps(families);
    vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, &families, familyProps.data());
    family = UINT32_MAX;
    for (uint32_t i = 0; i < families && family == UINT32_MAX; i++)
        if (familyProps[i].queueFlags & VK_QUEUE_COMPUTE_BIT) family = i;
    if (family == UINT32_MAX) return false;

    uint32_t extCount = 0;
    vkEnumerateDeviceExtensionProperties(physicalDevice, nullptr, &extCount, nullptr);
    std::vector<VkExtensionProperties> extensions(extCount);
    vkEnumerateDeviceExtensionProperties(physicalDevice, nullptr, &extCount, extensions.data());
    std::vector<const char *> enabled;
    if (!HasExtension(extensions, VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME)) return false;
    enabled.push_back(VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME);
    if (HasExtension(extensions, VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME)) {
        enabled.push_back(VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME);
        foreignFamily = VK_QUEUE_FAMILY_FOREIGN_EXT;
    }
    // The generated frame must be on time: it runs before the game's work in the GPU's queue (measured on a
    // Mali-G615: at the default priority it waited 23-40 ms behind a GPU-bound game).
    bool priority = HasExtension(extensions, VK_KHR_GLOBAL_PRIORITY_EXTENSION_NAME);
    if (priority) enabled.push_back(VK_KHR_GLOBAL_PRIORITY_EXTENSION_NAME);
    // GPU-side sync with GL (Android sync files): neither side waits on the CPU.
    syncFiles = HasExtension(extensions, VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME) &&
                eglGetProcAddress("eglDupNativeFenceFDANDROID") != nullptr;
    if (syncFiles) {
        enabled.push_back(VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME);
    }

    VkPhysicalDeviceVulkan12Features f12{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES};
    VkPhysicalDeviceVulkan13Features f13{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES};
    f12.pNext = &f13;
    VkPhysicalDeviceFeatures2 f2{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2};
    f2.pNext = &f12;
    getFeatures2(physicalDevice, &f2);
    VkPhysicalDeviceVulkan12Features e12{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES};
    VkPhysicalDeviceVulkan13Features e13{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES};
    e12.pNext = &e13;
    e12.shaderFloat16 = f12.shaderFloat16;
    e13.synchronization2 = f13.synchronization2;
    VkPhysicalDeviceFeatures2 e2{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2};
    e2.pNext = &e12;
    e2.features.shaderStorageImageExtendedFormats = f2.features.shaderStorageImageExtendedFormats;

    float queuePriority = 1.0f;
    VkDeviceQueueGlobalPriorityCreateInfoKHR global{VK_STRUCTURE_TYPE_DEVICE_QUEUE_GLOBAL_PRIORITY_CREATE_INFO_KHR};
    global.globalPriority = VK_QUEUE_GLOBAL_PRIORITY_HIGH_KHR;
    VkDeviceQueueCreateInfo qci{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
    qci.pNext = priority ? &global : nullptr;
    qci.queueFamilyIndex = family;
    qci.queueCount = 1;
    qci.pQueuePriorities = &queuePriority;
    VkDeviceCreateInfo dci{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
    dci.pNext = &e2;
    dci.queueCreateInfoCount = 1;
    dci.pQueueCreateInfos = &qci;
    dci.enabledExtensionCount = uint32_t(enabled.size());
    dci.ppEnabledExtensionNames = enabled.data();
    VkResult result = vkCreateDevice(physicalDevice, &dci, nullptr, &device);
    if (result == VK_ERROR_NOT_PERMITTED_KHR && priority) {
        FG_LOG("high GPU priority refused, using the default");
        qci.pNext = nullptr;
        result = vkCreateDevice(physicalDevice, &dci, nullptr, &device);
    }
    if (result != VK_SUCCESS) return false;
    vkGetDeviceQueue(device, family, 0, &queue);
    getBufferProperties = (PFN_vkGetAndroidHardwareBufferPropertiesANDROID)vkGetDeviceProcAddr(device, "vkGetAndroidHardwareBufferPropertiesANDROID");
    if (!getBufferProperties) return false;

    VkCommandPoolCreateInfo pci{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
    pci.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    pci.queueFamilyIndex = family;
    if (vkCreateCommandPool(device, &pci, nullptr, &pool) != VK_SUCCESS) return false;
    VkCommandBufferAllocateInfo cai{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    cai.commandPool = pool;
    cai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    cai.commandBufferCount = 1;
    if (vkAllocateCommandBuffers(device, &cai, &cmd) != VK_SUCCESS) return false;
    VkFenceCreateInfo fci{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    fci.flags = VK_FENCE_CREATE_SIGNALED_BIT;
    if (vkCreateFence(device, &fci, nullptr, &fence) != VK_SUCCESS) return false;
    if (syncFiles) {
        VkSemaphoreCreateInfo sci{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
        VkExportSemaphoreCreateInfo exportable{VK_STRUCTURE_TYPE_EXPORT_SEMAPHORE_CREATE_INFO};
        exportable.handleTypes = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT;
        VkSemaphoreCreateInfo vci{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
        vci.pNext = &exportable;
        importSemaphoreFd = (PFN_vkImportSemaphoreFdKHR)vkGetDeviceProcAddr(device, "vkImportSemaphoreFdKHR");
        getSemaphoreFd = (PFN_vkGetSemaphoreFdKHR)vkGetDeviceProcAddr(device, "vkGetSemaphoreFdKHR");
        dupNativeFenceFd = (PFNEGLDUPNATIVEFENCEFDANDROIDPROC)eglGetProcAddress("eglDupNativeFenceFDANDROID");
        syncFiles = importSemaphoreFd && getSemaphoreFd && dupNativeFenceFd &&
                    vkCreateSemaphore(device, &sci, nullptr, &glDone) == VK_SUCCESS &&
                    vkCreateSemaphore(device, &vci, nullptr, &vkDone) == VK_SUCCESS;
    }
    // The GPU time of each generated frame (read back one generation later, when it is done anyway).
    VkQueryPoolCreateInfo qpci{VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO};
    qpci.queryType = VK_QUERY_TYPE_TIMESTAMP;
    qpci.queryCount = 2;
    if (vkCreateQueryPool(device, &qpci, nullptr, &timestamps) != VK_SUCCESS) timestamps = VK_NULL_HANDLE;
    timestampPeriod = props.limits.timestampPeriod;

    VkPhysicalDeviceSubgroupProperties subgroup{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SUBGROUP_PROPERTIES};
    VkPhysicalDeviceProperties2 p2{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2};
    p2.pNext = &subgroup;
    getProperties2(physicalDevice, &p2);
    zerofg::CreateInfo info;
    info.vulkan.instance = instance;
    info.vulkan.physical_device = physicalDevice;
    info.vulkan.device = device;
    info.vulkan.get_instance_proc_addr = vkGetInstanceProcAddr;
    info.mode = zerofg::Mode::kReallyZero;
    info.backend = zerofg::Backend::kModern;
    info.capabilities.effective_api_version = VK_API_VERSION_1_3;
    info.capabilities.synchronization2_enabled = e13.synchronization2;
    info.capabilities.shader_storage_image_extended_formats_enabled = e2.features.shaderStorageImageExtendedFormats;
    info.capabilities.shader_float16_supported = f12.shaderFloat16;
    info.capabilities.shader_float16_enabled = e12.shaderFloat16;
    info.capabilities.subgroup_size = subgroup.subgroupSize;
    info.capabilities.subgroup_min_size = subgroup.subgroupSize;
    info.capabilities.subgroup_max_size = subgroup.subgroupSize;
    info.capabilities.subgroup_supported_operations = subgroup.supportedOperations;
    info.capabilities.subgroup_supported_stages = subgroup.supportedStages;
    info.frame_context_count = 1;
    engineInfo = info;
    if (!createEngine()) return false;
    FG_LOG("ready on %s (subgroup %u, high priority %s, GPU sync %s)", props.deviceName, subgroup.subgroupSize, priority ? "yes" : "no", syncFiles ? "yes" : "no");
    return true;
}

// ReallyZero (Balanced) runs two D4 refinement passes, Zero (Quality) four: +0.7 dB at 1280 x 720 and +2.1 dB at 960 x 540
// against the true in-between frame, for ~2.4 ms more at 1280 x 720 on a Mali-G615.
bool FrameGen::createEngine() {
    engineInfo.mode = quality ? zerofg::Mode::kZero : zerofg::Mode::kReallyZero;
    zerofg::Status status;
    engine = zerofg::Interpolator::Create(engineInfo, &status);
    if (!engine) {
        FG_LOG("engine create failed: %d", (int)status);
        return false;
    }
    engineQuality = quality;
    return true;
}

bool FrameGen::createShared(Shared &shared, uint32_t ahbFormat, VkFormat format, bool isOutput) {
    AHardwareBuffer_Desc desc{};
    desc.width = uint32_t(width);
    desc.height = uint32_t(height);
    desc.layers = 1;
    desc.format = ahbFormat;
    desc.usage = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE | AHARDWAREBUFFER_USAGE_GPU_FRAMEBUFFER;
    if (AHardwareBuffer_allocate(&desc, &shared.buffer) != 0) return false;

    VkAndroidHardwareBufferPropertiesANDROID props{VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID};
    if (getBufferProperties(device, shared.buffer, &props) != VK_SUCCESS) return false;

    // The generated frame is written in place when the driver allows storage on a shared buffer; otherwise the engine
    // resolves into its own image and copies.
    shared.usage = VK_IMAGE_USAGE_SAMPLED_BIT;
    if (isOutput) {
        shared.usage |= VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        VkPhysicalDeviceExternalImageFormatInfo external{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTERNAL_IMAGE_FORMAT_INFO};
        external.handleType = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;
        VkPhysicalDeviceImageFormatInfo2 query{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_IMAGE_FORMAT_INFO_2};
        query.pNext = &external;
        query.format = format;
        query.type = VK_IMAGE_TYPE_2D;
        query.tiling = VK_IMAGE_TILING_OPTIMAL;
        query.usage = shared.usage | VK_IMAGE_USAGE_STORAGE_BIT;
        VkImageFormatProperties2 answer{VK_STRUCTURE_TYPE_IMAGE_FORMAT_PROPERTIES_2};
        if (getImageFormatProperties2(physicalDevice, &query, &answer) == VK_SUCCESS)
            shared.usage |= VK_IMAGE_USAGE_STORAGE_BIT;
    }

    VkExternalMemoryImageCreateInfo externalImage{VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO};
    externalImage.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;
    VkImageCreateInfo ici{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
    ici.pNext = &externalImage;
    ici.imageType = VK_IMAGE_TYPE_2D;
    ici.format = format;
    ici.extent = {uint32_t(width), uint32_t(height), 1};
    ici.mipLevels = 1;
    ici.arrayLayers = 1;
    ici.samples = VK_SAMPLE_COUNT_1_BIT;
    ici.tiling = VK_IMAGE_TILING_OPTIMAL;
    ici.usage = shared.usage;
    ici.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    if (vkCreateImage(device, &ici, nullptr, &shared.image) != VK_SUCCESS) return false;

    VkMemoryDedicatedAllocateInfo dedicated{VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO};
    dedicated.image = shared.image;
    VkImportAndroidHardwareBufferInfoANDROID import{VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID};
    import.pNext = &dedicated;
    import.buffer = shared.buffer;
    VkMemoryAllocateInfo mai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    mai.pNext = &import;
    mai.allocationSize = props.allocationSize;
    mai.memoryTypeIndex = uint32_t(__builtin_ctz(props.memoryTypeBits));
    if (vkAllocateMemory(device, &mai, nullptr, &shared.memory) != VK_SUCCESS) return false;
    if (vkBindImageMemory(device, shared.image, shared.memory, 0) != VK_SUCCESS) return false;
    VkImageViewCreateInfo vci{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
    vci.image = shared.image;
    vci.viewType = VK_IMAGE_VIEW_TYPE_2D;
    vci.format = format;
    vci.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    if (vkCreateImageView(device, &vci, nullptr, &shared.view) != VK_SUCCESS) return false;

    // The GL side: a texture over the same buffer, and for a real frame a framebuffer the scene is copied into.
    const EGLint attribs[] = {EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE};
    shared.eglImage = eglCreateImageKHR(eglDisplay, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID,
                                        eglGetNativeClientBufferANDROID(shared.buffer), attribs);
    if (shared.eglImage == EGL_NO_IMAGE_KHR) return false;
    glGenTextures(1, &shared.texture);
    glBindTexture(GL_TEXTURE_2D, shared.texture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glEGLImageTargetTexture2DOES(GL_TEXTURE_2D, shared.eglImage);
    glBindTexture(GL_TEXTURE_2D, 0);
    if (glGetError() != GL_NO_ERROR) return false;
    if (!isOutput) {
        glGenFramebuffers(1, &shared.framebuffer);
        glBindFramebuffer(GL_FRAMEBUFFER, shared.framebuffer);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, shared.texture, 0);
        bool complete = glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        if (!complete) return false;
    }
    return true;
}

void FrameGen::destroyShared(Shared &shared) {
    if (shared.framebuffer) glDeleteFramebuffers(1, &shared.framebuffer);
    if (shared.texture) glDeleteTextures(1, &shared.texture);
    if (shared.eglImage != EGL_NO_IMAGE_KHR) eglDestroyImageKHR(eglDisplay, shared.eglImage);
    if (device != VK_NULL_HANDLE) {
        if (shared.view) vkDestroyImageView(device, shared.view, nullptr);
        if (shared.image) vkDestroyImage(device, shared.image, nullptr);
        if (shared.memory) vkFreeMemory(device, shared.memory, nullptr);
    }
    if (shared.buffer) AHardwareBuffer_release(shared.buffer);
    shared = Shared{};
}

void FrameGen::destroySized() {
    if (device != VK_NULL_HANDLE) vkDeviceWaitIdle(device);
    for (auto &slot : slots) destroyShared(slot);
    destroyShared(output);
    width = height = 0;
    havePrevious = false;
}

bool FrameGen::resize(int w, int h) {
    destroySized();
    width = w;
    height = h;
    for (auto &slot : slots)
        if (!createShared(slot, AHARDWAREBUFFER_FORMAT_R10G10B10A2_UNORM, VK_FORMAT_A2B10G10R10_UNORM_PACK32, false)) return false;
    if (!createShared(output, AHARDWAREBUFFER_FORMAT_R10G10B10A2_UNORM, VK_FORMAT_A2B10G10R10_UNORM_PACK32, true))
        return false;
    zerofg::Status status = engine->Resize(uint32_t(w), uint32_t(h), VK_FORMAT_A2B10G10R10_UNORM_PACK32, VK_FORMAT_A2B10G10R10_UNORM_PACK32);
    if (status != zerofg::Status::kSuccess) {
        FG_LOG("engine resize %dx%d failed: %d", w, h, (int)status);
        return false;
    }
    FG_LOG("generating at %dx%d", w, h);
    return true;
}

bool FrameGen::pushReal(EGLDisplay display, GLuint scene, int w, int h) {
    // A size the engine could not take is not retried, another size is (an in-game resolution change).
    if (broken) {
        if (noVulkan || (w == failedWidth && h == failedHeight)) return false;
        broken = false;
    }
    eglDisplay = display;
    if (!vulkanReady) {
        vulkanReady = true;
        if (!initVulkan()) {
            FG_LOG("unavailable on this device");
            broken = true;
            noVulkan = true;
            return false;
        }
    }
    if (quality != engineQuality) {
        // Another quality: a new engine (its working resources are made by the resize below).
        destroySized();
        engine.reset();
        if (!createEngine()) {
            broken = true;
            noVulkan = true;
            return false;
        }
        FG_LOG("quality: %s", quality ? "Quality (Zero)" : "Balanced (ReallyZero)");
    }
    if (w != width || h != height) {
        if (!resize(w, h)) {
            broken = true;
            failedWidth = w;
            failedHeight = h;
            destroySized();
            return false;
        }
    }

    // The scene into the next slot (the older of the two: the frame before the previous one is no longer needed).
    current = havePrevious ? 1 - current : current;
    if (!readFramebuffer) glGenFramebuffers(1, &readFramebuffer);
    glBindFramebuffer(GL_READ_FRAMEBUFFER, readFramebuffer);
    glFramebufferTexture2D(GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, scene, 0);
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, slots[current].framebuffer);
    glBlitFramebuffer(0, 0, w, h, 0, 0, w, h, GL_COLOR_BUFFER_BIT, GL_NEAREST);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    slots[current].sequence = ++sequence;
    // Without sync files the copy must be done before Vulkan reads it; with them, generate() makes Vulkan wait on the GPU.
    if (!syncFiles) glFinish();

    bool pair = havePrevious;
    havePrevious = true;
    return pair;
}

bool FrameGen::generate(float phase) {
    if (broken) return false;
    // The last generation is done before its command buffer and the engine's descriptors are reused (a frame ago at 2x:
    // no wait in practice), and its GPU time is read.
    if (vkWaitForFences(device, 1, &fence, VK_TRUE, 200ull * 1000 * 1000) != VK_SUCCESS) {
        broken = true;
        return false;
    }
    if (timestamps != VK_NULL_HANDLE && timestampsWritten) {
        uint64_t ts[2] = {};
        if (vkGetQueryPoolResults(device, timestamps, 0, 2, sizeof(ts), ts, sizeof(uint64_t), VK_QUERY_RESULT_64_BIT) == VK_SUCCESS)
            lastGenerateMs = float(double(ts[1] - ts[0]) * timestampPeriod / 1e6);
    }
    VkSemaphore wait = VK_NULL_HANDLE;
    if (syncFiles) {
        // Vulkan waits on the GPU for GL: the copy of the real frame and every read of the last generated frame.
        EGLSyncKHR sync = eglCreateSyncKHR(eglDisplay, EGL_SYNC_NATIVE_FENCE_ANDROID, nullptr);
        glFlush();
        int fd = sync != EGL_NO_SYNC_KHR ? dupNativeFenceFd(eglDisplay, sync) : -1;
        if (sync != EGL_NO_SYNC_KHR) eglDestroySyncKHR(eglDisplay, sync);
        if (fd >= 0) {
            VkImportSemaphoreFdInfoKHR import{VK_STRUCTURE_TYPE_IMPORT_SEMAPHORE_FD_INFO_KHR};
            import.semaphore = glDone;
            import.flags = VK_SEMAPHORE_IMPORT_TEMPORARY_BIT;
            import.handleType = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT;
            import.fd = fd;
            if (importSemaphoreFd(device, &import) == VK_SUCCESS) wait = glDone;
            else {
                close(fd);
                glFinish();
            }
        } else {
            glFinish();
        }
    } else {
        glFinish();
    }
    if (!record(phase, wait)) {
        FG_LOG("generation failed");
        broken = true;
        return false;
    }
    if (syncFiles) {
        // GL waits on the GPU for Vulkan before it samples the generated frame.
        int fd = -1;
        VkSemaphoreGetFdInfoKHR info{VK_STRUCTURE_TYPE_SEMAPHORE_GET_FD_INFO_KHR};
        info.semaphore = vkDone;
        info.handleType = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT;
        if (getSemaphoreFd(device, &info, &fd) == VK_SUCCESS && fd >= 0) {
            const EGLint attribs[] = {EGL_SYNC_NATIVE_FENCE_FD_ANDROID, fd, EGL_NONE};
            EGLSyncKHR sync = eglCreateSyncKHR(eglDisplay, EGL_SYNC_NATIVE_FENCE_ANDROID, attribs);
            if (sync != EGL_NO_SYNC_KHR) {
                eglWaitSyncKHR(eglDisplay, sync, 0);
                eglDestroySyncKHR(eglDisplay, sync);
            } else {
                close(fd);
                vkWaitForFences(device, 1, &fence, VK_TRUE, 200ull * 1000 * 1000);
            }
        } else {
            vkWaitForFences(device, 1, &fence, VK_TRUE, 200ull * 1000 * 1000);
        }
    } else {
        vkWaitForFences(device, 1, &fence, VK_TRUE, 200ull * 1000 * 1000);
    }
    return true;
}

bool FrameGen::record(float phase, VkSemaphore wait) {
    Shared &previous = slots[1 - current];
    Shared &latest = slots[current];
    vkResetFences(device, 1, &fence);
    vkResetCommandBuffer(cmd, 0);
    VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vkBeginCommandBuffer(cmd, &begin);
    if (timestamps != VK_NULL_HANDLE) {
        vkCmdResetQueryPool(cmd, timestamps, 0, 2);
        vkCmdWriteTimestamp(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, timestamps, 0);
    }

    // Ownership of the shared buffers comes from GL (the foreign queue) and goes back to it afterwards.
    auto transfer = [&](VkImage image, VkImageLayout from, VkImageLayout to, uint32_t srcFamily, uint32_t dstFamily) {
        VkImageMemoryBarrier b{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
        b.srcAccessMask = VK_ACCESS_MEMORY_WRITE_BIT;
        b.dstAccessMask = VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT;
        b.oldLayout = from;
        b.newLayout = to;
        b.srcQueueFamilyIndex = srcFamily;
        b.dstQueueFamilyIndex = dstFamily;
        b.image = image;
        b.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, 0, nullptr, 0,
                             nullptr, 1, &b);
    };
    const VkImageLayout read = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    transfer(previous.image, VK_IMAGE_LAYOUT_GENERAL, read, foreignFamily, family);
    transfer(latest.image, VK_IMAGE_LAYOUT_GENERAL, read, foreignFamily, family);
    transfer(output.image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL, foreignFamily, family);

    auto wrap = [&](const Shared &shared, VkImageLayout layout, VkFormat format) {
        zerofg::Image z;
        z.image = shared.image;
        z.view = shared.view;
        z.layout = layout;
        z.format = format;
        z.width = uint32_t(width);
        z.height = uint32_t(height);
        z.usage = shared.usage;
        z.tiling = VK_IMAGE_TILING_OPTIMAL;
        z.creation_facts_known = true;
        z.sequence = shared.sequence;
        return z;
    };
    zerofg::Status status = engine->Interpolate(cmd, 0, wrap(previous, read, VK_FORMAT_A2B10G10R10_UNORM_PACK32),
                                                wrap(latest, read, VK_FORMAT_A2B10G10R10_UNORM_PACK32), phase,
                                                wrap(output, VK_IMAGE_LAYOUT_GENERAL, VK_FORMAT_A2B10G10R10_UNORM_PACK32));

    transfer(previous.image, read, VK_IMAGE_LAYOUT_GENERAL, family, foreignFamily);
    transfer(latest.image, read, VK_IMAGE_LAYOUT_GENERAL, family, foreignFamily);
    transfer(output.image, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL, family, foreignFamily);
    if (timestamps != VK_NULL_HANDLE) {
        vkCmdWriteTimestamp(cmd, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, timestamps, 1);
        timestampsWritten = true;
    }
    vkEndCommandBuffer(cmd);
    if (status != zerofg::Status::kSuccess) {
        FG_LOG("interpolate: %d", (int)status);
        return false;
    }
    const VkPipelineStageFlags waitStage = VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
    VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    si.waitSemaphoreCount = wait != VK_NULL_HANDLE ? 1 : 0;
    si.pWaitSemaphores = &wait;
    si.pWaitDstStageMask = &waitStage;
    si.commandBufferCount = 1;
    si.pCommandBuffers = &cmd;
    si.signalSemaphoreCount = syncFiles ? 1 : 0;
    si.pSignalSemaphores = &vkDone;
    return vkQueueSubmit(queue, 1, &si, fence) == VK_SUCCESS;
}

void FrameGen::destroy() {
    destroySized();
    if (readFramebuffer) glDeleteFramebuffers(1, &readFramebuffer);
    readFramebuffer = 0;
    engine.reset();
    if (device != VK_NULL_HANDLE) {
        vkDeviceWaitIdle(device);
        if (fence) vkDestroyFence(device, fence, nullptr);
        if (glDone) vkDestroySemaphore(device, glDone, nullptr);
        if (vkDone) vkDestroySemaphore(device, vkDone, nullptr);
        vkDone = VK_NULL_HANDLE;
        if (timestamps) vkDestroyQueryPool(device, timestamps, nullptr);
        glDone = VK_NULL_HANDLE;
        timestamps = VK_NULL_HANDLE;
        timestampsWritten = false;
        if (pool) vkDestroyCommandPool(device, pool, nullptr);
        vkDestroyDevice(device, nullptr);
    }
    if (instance != VK_NULL_HANDLE) vkDestroyInstance(instance, nullptr);
    fence = VK_NULL_HANDLE;
    pool = VK_NULL_HANDLE;
    cmd = VK_NULL_HANDLE;
    device = VK_NULL_HANDLE;
    instance = VK_NULL_HANDLE;
    vulkanReady = false;
    broken = false;
}
