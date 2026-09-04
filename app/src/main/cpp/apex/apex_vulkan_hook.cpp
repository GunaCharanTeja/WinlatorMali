#include "apex_vulkan_hook.h"
#include <android/log.h>
#include <cstring>
#include <algorithm>

#define HOOK_LOGI(...) __android_log_print(ANDROID_LOG_INFO, APEX_HOOK_TAG, __VA_ARGS__)
#define HOOK_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, APEX_HOOK_TAG, __VA_ARGS__)
#define HOOK_LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, APEX_HOOK_TAG, __VA_ARGS__)

namespace apex {

VulkanDepthHookManager& VulkanDepthHookManager::getInstance() {
    static VulkanDepthHookManager instance;
    return instance;
}

VulkanDepthHookManager::VulkanDepthHookManager() {
    HOOK_LOGI("VulkanDepthHookManager initialized");
}

VulkanDepthHookManager::~VulkanDepthHookManager() {
    std::lock_guard<std::mutex> lock(mMutex);
    for (auto& cand : mDepthCandidates) {
        if (cand.hardwareBuffer) {
            AHardwareBuffer_release(cand.hardwareBuffer);
            cand.hardwareBuffer = nullptr;
        }
    }
    mDepthCandidates.clear();
}

void VulkanDepthHookManager::setHookEnabled(bool enabled) {
    mEnabled.store(enabled, std::memory_order_release);
    HOOK_LOGI("VulkanDepthHookManager enabled state: %d", enabled);
}

bool VulkanDepthHookManager::isHookEnabled() const {
    return mEnabled.load(std::memory_order_acquire);
}

void VulkanDepthHookManager::onDeviceCreated(VkDevice device, VkPhysicalDevice physicalDevice) {
    std::lock_guard<std::mutex> lock(mMutex);
    mDevice = device;
    mPhysicalDevice = physicalDevice;
    mDepthCandidates.clear();
    mBestDepthCandidateIdx = -1;
    HOOK_LOGI("VulkanDepthHookManager onDeviceCreated (Device: %p)", device);
}

void VulkanDepthHookManager::onDeviceDestroyed(VkDevice device) {
    std::lock_guard<std::mutex> lock(mMutex);
    if (mDevice == device) {
        mDevice = VK_NULL_HANDLE;
        mPhysicalDevice = VK_NULL_HANDLE;
        mDepthCandidates.clear();
        mBestDepthCandidateIdx = -1;
        HOOK_LOGI("VulkanDepthHookManager onDeviceDestroyed");
    }
}

void VulkanDepthHookManager::onBeginRenderPass(VkCommandBuffer cmd, const VkRenderPassBeginInfo* pRenderPassBegin) {
    (void)cmd;
    if (!mEnabled.load(std::memory_order_relaxed) || !pRenderPassBegin) return;

    std::lock_guard<std::mutex> lock(mMutex);
    mInside3DPass = true;
    mInsideUIPass = false;
}

void VulkanDepthHookManager::onEndRenderPass(VkCommandBuffer cmd) {
    (void)cmd;
    if (!mEnabled.load(std::memory_order_relaxed)) return;

    std::lock_guard<std::mutex> lock(mMutex);
    mInside3DPass = false;
}

void VulkanDepthHookManager::onDraw(VkCommandBuffer cmd, uint32_t vertexCount, uint32_t instanceCount) {
    (void)cmd; (void)vertexCount; (void)instanceCount;
    if (!mEnabled.load(std::memory_order_relaxed)) return;

    if (mBestDepthCandidateIdx >= 0 && mBestDepthCandidateIdx < static_cast<int>(mDepthCandidates.size())) {
        mDepthCandidates[mBestDepthCandidateIdx].drawCallCount++;
    }
}

void VulkanDepthHookManager::onQueuePresent(VkQueue queue, const VkPresentInfoKHR* pPresentInfo) {
    (void)queue; (void)pPresentInfo;
    if (!mEnabled.load(std::memory_order_relaxed)) return;

    std::lock_guard<std::mutex> lock(mMutex);
    // Frame boundary: evaluate depth candidates & select best candidate for next frame
    if (!mDepthCandidates.empty()) {
        int bestIdx = 0;
        uint32_t maxDraws = 0;
        for (size_t i = 0; i < mDepthCandidates.size(); i++) {
            if (mDepthCandidates[i].drawCallCount > maxDraws) {
                maxDraws = mDepthCandidates[i].drawCallCount;
                bestIdx = static_cast<int>(i);
            }
            mDepthCandidates[i].drawCallCount = 0; // Reset for next frame
        }
        mBestDepthCandidateIdx = bestIdx;
    }
}

bool VulkanDepthHookManager::isDepthAvailable() const {
    return mBestDepthCandidateIdx >= 0 && mEnabled.load(std::memory_order_acquire);
}

AHardwareBuffer* VulkanDepthHookManager::getActiveDepthBuffer() {
    std::lock_guard<std::mutex> lock(mMutex);
    if (mBestDepthCandidateIdx >= 0 && mBestDepthCandidateIdx < static_cast<int>(mDepthCandidates.size())) {
        return mDepthCandidates[mBestDepthCandidateIdx].hardwareBuffer;
    }
    return nullptr;
}

AHardwareBuffer* VulkanDepthHookManager::getActiveClean3DBuffer() {
    std::lock_guard<std::mutex> lock(mMutex);
    return mClean3DHardwareBuffer;
}

AHardwareBuffer* VulkanDepthHookManager::getActiveHUDMask() {
    std::lock_guard<std::mutex> lock(mMutex);
    return mHUDHardwareBuffer;
}

} // namespace apex
