#pragma once

#include <cstdint>

#include "zerofg/zerofg.h"
#include "vulkan_dispatch.h"

namespace zerofg {

class OwnedImage {
 public:
  OwnedImage() = default;

  ~OwnedImage() = default;

  OwnedImage(const OwnedImage&) = delete;
  OwnedImage& operator=(const OwnedImage&) = delete;

  Status Create(const VulkanContext& vulkan,
                const VulkanDispatch& dispatch,
                uint32_t width,
                uint32_t height,
                VkFormat format,
                VkImageUsageFlags usage) {
    if (vulkan.physical_device == VK_NULL_HANDLE ||
        vulkan.device == VK_NULL_HANDLE ||
        width == 0 || height == 0 ||
        format == VK_FORMAT_UNDEFINED) {
      return Status::kInvalidArgument;
    }

    Destroy(vulkan, dispatch);

    VkImageCreateInfo image_info{};
    image_info.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    image_info.imageType = VK_IMAGE_TYPE_2D;
    image_info.format = format;
    image_info.extent = {width, height, 1};
    image_info.mipLevels = 1;
    image_info.arrayLayers = 1;
    image_info.samples = VK_SAMPLE_COUNT_1_BIT;
    image_info.tiling = VK_IMAGE_TILING_OPTIMAL;
    image_info.usage = usage;
    image_info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    image_info.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;

    if (dispatch.create_image(
            vulkan.device, &image_info, vulkan.allocator, &image_) !=
        VK_SUCCESS) {
      return Status::kVulkanError;
    }

    VkMemoryRequirements requirements{};
    dispatch.get_image_memory_requirements(
        vulkan.device, image_, &requirements);

    VkPhysicalDeviceMemoryProperties memory_properties{};
    dispatch.get_physical_device_memory_properties(
        vulkan.physical_device, &memory_properties);

    uint32_t memory_type_index = UINT32_MAX;

    for (uint32_t i = 0; i < memory_properties.memoryTypeCount; ++i) {
      const bool supported =
          (requirements.memoryTypeBits & (1u << i)) != 0;
      const bool device_local =
          (memory_properties.memoryTypes[i].propertyFlags &
           VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) != 0;

      if (supported && device_local) {
        memory_type_index = i;
        break;
      }
    }

    if (memory_type_index == UINT32_MAX) {
      for (uint32_t i = 0; i < memory_properties.memoryTypeCount; ++i) {
        if ((requirements.memoryTypeBits & (1u << i)) != 0) {
          memory_type_index = i;
          break;
        }
      }
    }

    if (memory_type_index == UINT32_MAX) {
      Destroy(vulkan, dispatch);
      return Status::kUnsupported;
    }

    VkMemoryAllocateInfo allocate_info{};
    allocate_info.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocate_info.allocationSize = requirements.size;
    allocate_info.memoryTypeIndex = memory_type_index;

    if (dispatch.allocate_memory(
            vulkan.device, &allocate_info, vulkan.allocator, &memory_) !=
        VK_SUCCESS) {
      Destroy(vulkan, dispatch);
      return Status::kOutOfMemory;
    }

    if (dispatch.bind_image_memory(
            vulkan.device, image_, memory_, 0) != VK_SUCCESS) {
      Destroy(vulkan, dispatch);
      return Status::kVulkanError;
    }

    VkImageViewCreateInfo view_info{};
    view_info.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    view_info.image = image_;
    view_info.viewType = VK_IMAGE_VIEW_TYPE_2D;
    view_info.format = format;
    view_info.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    view_info.subresourceRange.baseMipLevel = 0;
    view_info.subresourceRange.levelCount = 1;
    view_info.subresourceRange.baseArrayLayer = 0;
    view_info.subresourceRange.layerCount = 1;

    if (dispatch.create_image_view(
            vulkan.device, &view_info, vulkan.allocator, &view_) !=
        VK_SUCCESS) {
      Destroy(vulkan, dispatch);
      return Status::kVulkanError;
    }

    width_ = width;
    height_ = height;
    format_ = format;

    return Status::kSuccess;
  }

  void Destroy(const VulkanContext& vulkan,
               const VulkanDispatch& dispatch) {
    if (vulkan.device == VK_NULL_HANDLE) {
      return;
    }

    if (view_ != VK_NULL_HANDLE) {
      dispatch.destroy_image_view(
          vulkan.device, view_, vulkan.allocator);
      view_ = VK_NULL_HANDLE;
    }

    if (image_ != VK_NULL_HANDLE) {
      dispatch.destroy_image(
          vulkan.device, image_, vulkan.allocator);
      image_ = VK_NULL_HANDLE;
    }

    if (memory_ != VK_NULL_HANDLE) {
      dispatch.free_memory(
          vulkan.device, memory_, vulkan.allocator);
      memory_ = VK_NULL_HANDLE;
    }

    width_ = 0;
    height_ = 0;
    format_ = VK_FORMAT_UNDEFINED;
  }

  VkImage image() const { return image_; }
  VkImageView view() const { return view_; }
  VkFormat format() const { return format_; }
  uint32_t width() const { return width_; }
  uint32_t height() const { return height_; }

 private:
  VkImage image_ = VK_NULL_HANDLE;
  VkDeviceMemory memory_ = VK_NULL_HANDLE;
  VkImageView view_ = VK_NULL_HANDLE;

  VkFormat format_ = VK_FORMAT_UNDEFINED;
  uint32_t width_ = 0;
  uint32_t height_ = 0;
};

}  // namespace zerofg


