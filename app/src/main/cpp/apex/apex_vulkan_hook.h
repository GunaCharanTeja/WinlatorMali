#pragma once

#include <vulkan/vulkan.h>
#include <android/hardware_buffer.h>
#include <atomic>
#include <vector>
#include <mutex>

#define APEX_HOOK_TAG "ApexVulkanHook"

namespace apex {

struct DepthBufferCandidate {
    VkImage image = VK_NULL_HANDLE;
    VkFormat format = VK_FORMAT_UNDEFINED;
    uint32_t width = 0;
    uint32_t height = 0;
    uint32_t drawCallCount = 0;
    AHardwareBuffer* hardwareBuffer = nullptr;
};

class VulkanDepthHookManager {
public:
    static VulkanDepthHookManager& getInstance();

    void onDeviceCreated(VkDevice device, VkPhysicalDevice physicalDevice);
    void onDeviceDestroyed(VkDevice device);

    void onBeginRenderPass(VkCommandBuffer cmd, const VkRenderPassBeginInfo* pRenderPassBegin);
    void onEndRenderPass(VkCommandBuffer cmd);
    void onDraw(VkCommandBuffer cmd, uint32_t vertexCount, uint32_t instanceCount);

    void onQueuePresent(VkQueue queue, const VkPresentInfoKHR* pPresentInfo);

    bool isDepthAvailable() const;
    AHardwareBuffer* getActiveDepthBuffer();
    AHardwareBuffer* getActiveClean3DBuffer();
    AHardwareBuffer* getActiveHUDMask();

    void setHookEnabled(bool enabled);
    bool isHookEnabled() const;

private:
    VulkanDepthHookManager();
    ~VulkanDepthHookManager();

    std::atomic<bool> mEnabled{true};
    std::mutex mMutex;
    VkDevice mDevice = VK_NULL_HANDLE;
    VkPhysicalDevice mPhysicalDevice = VK_NULL_HANDLE;

    std::vector<DepthBufferCandidate> mDepthCandidates;
    int mBestDepthCandidateIdx = -1;
    bool mInside3DPass = false;
    bool mInsideUIPass = false;

    AHardwareBuffer* mClean3DHardwareBuffer = nullptr;
    AHardwareBuffer* mDepthHardwareBuffer = nullptr;
    AHardwareBuffer* mHUDHardwareBuffer = nullptr;
};

} // namespace apex
