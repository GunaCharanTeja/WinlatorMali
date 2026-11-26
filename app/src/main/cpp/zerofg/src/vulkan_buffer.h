#pragma once

#include <cstddef>
#include <cstdint>
#include <cstring>

#include "zerofg/zerofg.h"
#include "vulkan_dispatch.h"

namespace zerofg {

class OwnedBuffer {
 public:
  OwnedBuffer() = default;
  ~OwnedBuffer() = default;

  OwnedBuffer(const OwnedBuffer&) = delete;
  OwnedBuffer& operator=(const OwnedBuffer&) = delete;

  Status Create(const VulkanContext& vulkan, const VulkanDispatch& dispatch,
                VkDeviceSize size, VkBufferUsageFlags usage) {
    if (vulkan.physical_device == VK_NULL_HANDLE ||
        vulkan.device == VK_NULL_HANDLE || size == 0) {
      return Status::kInvalidArgument;
    }
    Destroy(vulkan, dispatch);

    VkBufferCreateInfo buffer_info{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
    buffer_info.size = size;
    buffer_info.usage = usage;
    buffer_info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    if (dispatch.create_buffer(vulkan.device, &buffer_info, vulkan.allocator,
                               &buffer_) != VK_SUCCESS) {
      return Status::kVulkanError;
    }

    VkMemoryRequirements requirements{};
    dispatch.get_buffer_memory_requirements(vulkan.device, buffer_,
                                            &requirements);
    VkPhysicalDeviceMemoryProperties memory_properties{};
    dispatch.get_physical_device_memory_properties(
        vulkan.physical_device, &memory_properties);

    uint32_t memory_type_index = UINT32_MAX;
    for (uint32_t i = 0; i < memory_properties.memoryTypeCount; ++i) {
      if ((requirements.memoryTypeBits & (1u << i)) != 0 &&
          (memory_properties.memoryTypes[i].propertyFlags &
           VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) != 0) {
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

    VkMemoryAllocateInfo allocate_info{
        VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    allocate_info.allocationSize = requirements.size;
    allocate_info.memoryTypeIndex = memory_type_index;
    if (dispatch.allocate_memory(vulkan.device, &allocate_info,
                                 vulkan.allocator,
                                 &memory_) != VK_SUCCESS) {
      Destroy(vulkan, dispatch);
      return Status::kOutOfMemory;
    }
    if (dispatch.bind_buffer_memory(vulkan.device, buffer_, memory_, 0) !=
        VK_SUCCESS) {
      Destroy(vulkan, dispatch);
      return Status::kVulkanError;
    }
    size_ = size;
    return Status::kSuccess;
  }

  void Destroy(const VulkanContext& vulkan,
               const VulkanDispatch& dispatch) {
    if (vulkan.device == VK_NULL_HANDLE) {
      return;
    }
    if (buffer_ != VK_NULL_HANDLE) {
      dispatch.destroy_buffer(vulkan.device, buffer_, vulkan.allocator);
      buffer_ = VK_NULL_HANDLE;
    }
    if (memory_ != VK_NULL_HANDLE) {
      dispatch.free_memory(vulkan.device, memory_, vulkan.allocator);
      memory_ = VK_NULL_HANDLE;
    }
    size_ = 0;
  }

  VkBuffer buffer() const { return buffer_; }
  VkDeviceSize size() const { return size_; }

 private:
  VkBuffer buffer_ = VK_NULL_HANDLE;
  VkDeviceMemory memory_ = VK_NULL_HANDLE;
  VkDeviceSize size_ = 0;
};

// Dedicated mapped readback buffer for small GPU-written diagnostic data.
// The caller must wait for the producing submission before reading or
// invalidating it, and must not reuse the buffer while that read is pending.
class HostVisibleBuffer {
 public:
  HostVisibleBuffer() = default;
  ~HostVisibleBuffer() = default;

  HostVisibleBuffer(const HostVisibleBuffer&) = delete;
  HostVisibleBuffer& operator=(const HostVisibleBuffer&) = delete;

  Status Create(const VulkanContext& vulkan, const VulkanDispatch& dispatch,
                VkDeviceSize size, VkBufferUsageFlags usage) {
    if (vulkan.physical_device == VK_NULL_HANDLE ||
        vulkan.device == VK_NULL_HANDLE || size == 0 ||
        (usage & (VK_BUFFER_USAGE_STORAGE_BUFFER_BIT |
                  VK_BUFFER_USAGE_TRANSFER_DST_BIT)) !=
            (VK_BUFFER_USAGE_STORAGE_BUFFER_BIT |
             VK_BUFFER_USAGE_TRANSFER_DST_BIT)) {
      return Status::kInvalidArgument;
    }
    Destroy(vulkan, dispatch);

    VkBufferCreateInfo buffer_info{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
    buffer_info.size = size;
    buffer_info.usage = usage;
    buffer_info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    if (dispatch.create_buffer(vulkan.device, &buffer_info, vulkan.allocator,
                               &buffer_) != VK_SUCCESS) {
      return Status::kVulkanError;
    }

    VkMemoryRequirements requirements{};
    dispatch.get_buffer_memory_requirements(vulkan.device, buffer_,
                                            &requirements);
    VkPhysicalDeviceMemoryProperties memory_properties{};
    dispatch.get_physical_device_memory_properties(
        vulkan.physical_device, &memory_properties);

    uint32_t memory_type_index = UINT32_MAX;
    int best_score = -1;
    for (uint32_t i = 0; i < memory_properties.memoryTypeCount; ++i) {
      if ((requirements.memoryTypeBits & (1u << i)) == 0) {
        continue;
      }
      const VkMemoryPropertyFlags flags =
          memory_properties.memoryTypes[i].propertyFlags;
      if ((flags & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) == 0) {
        continue;
      }
      const int score =
          ((flags & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) ? 4 : 0) +
          ((flags & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) ? 2 : 0) +
          ((flags & VK_MEMORY_PROPERTY_HOST_CACHED_BIT) ? 1 : 0);
      if (score > best_score) {
        memory_type_index = i;
        memory_flags_ = flags;
        best_score = score;
      }
    }
    if (memory_type_index == UINT32_MAX) {
      Destroy(vulkan, dispatch);
      return Status::kUnsupported;
    }

    VkMemoryAllocateInfo allocate_info{
        VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    allocate_info.allocationSize = requirements.size;
    allocate_info.memoryTypeIndex = memory_type_index;
    if (dispatch.allocate_memory(vulkan.device, &allocate_info,
                                 vulkan.allocator, &memory_) != VK_SUCCESS) {
      Destroy(vulkan, dispatch);
      return Status::kOutOfMemory;
    }
    if (dispatch.bind_buffer_memory(vulkan.device, buffer_, memory_, 0) !=
        VK_SUCCESS) {
      Destroy(vulkan, dispatch);
      return Status::kVulkanError;
    }
    if (dispatch.map_memory(vulkan.device, memory_, 0, VK_WHOLE_SIZE, 0,
                            &mapped_data_) != VK_SUCCESS) {
      Destroy(vulkan, dispatch);
      return Status::kVulkanError;
    }

    VkPhysicalDeviceProperties properties{};
    dispatch.get_physical_device_properties(vulkan.physical_device,
                                            &properties);
    non_coherent_atom_size_ = properties.limits.nonCoherentAtomSize;
    allocation_size_ = requirements.size;
    size_ = size;
    return Status::kSuccess;
  }

  // For noncoherent memory, invalidate an atom-aligned range after GPU
  // completion. VK_WHOLE_SIZE is used for an aligned tail at allocation end.
  Status Invalidate(const VulkanContext& vulkan,
                    const VulkanDispatch& dispatch,
                    VkDeviceSize offset = 0,
                    VkDeviceSize size = VK_WHOLE_SIZE) const {
    if (vulkan.device == VK_NULL_HANDLE || mapped_data_ == nullptr ||
        offset > size_) {
      return Status::kInvalidArgument;
    }
    const VkDeviceSize length =
        size == VK_WHOLE_SIZE ? size_ - offset : size;
    if (length > size_ - offset) {
      return Status::kInvalidArgument;
    }
    if (length == 0 || coherent()) {
      return Status::kSuccess;
    }

    const VkDeviceSize atom =
        non_coherent_atom_size_ ? non_coherent_atom_size_ : 1;
    const VkDeviceSize aligned_offset = offset - offset % atom;
    const VkDeviceSize end = offset + length;
    VkDeviceSize aligned_size = VK_WHOLE_SIZE;
    if (end < allocation_size_) {
      const VkDeviceSize padding = (atom - end % atom) % atom;
      if (padding < allocation_size_ - end) {
        aligned_size = end + padding - aligned_offset;
      }
    }
    VkMappedMemoryRange range{VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE};
    range.memory = memory_;
    range.offset = aligned_offset;
    range.size = aligned_size;
    return dispatch.invalidate_mapped_memory_ranges(vulkan.device, 1,
                                                    &range) == VK_SUCCESS
               ? Status::kSuccess
               : Status::kVulkanError;
  }

  bool Read(const VulkanContext& vulkan, const VulkanDispatch& dispatch,
            void* dst, size_t bytes) const {
    if (mapped_data_ == nullptr || dst == nullptr || bytes > size_ ||
        Invalidate(vulkan, dispatch, 0, VkDeviceSize(bytes)) !=
            Status::kSuccess) {
      return false;
    }
    std::memcpy(dst, mapped_data_, bytes);
    return true;
  }

  void Destroy(const VulkanContext& vulkan,
               const VulkanDispatch& dispatch) {
    if (vulkan.device == VK_NULL_HANDLE) {
      return;
    }
    if (mapped_data_ != nullptr) {
      dispatch.unmap_memory(vulkan.device, memory_);
      mapped_data_ = nullptr;
    }
    if (buffer_ != VK_NULL_HANDLE) {
      dispatch.destroy_buffer(vulkan.device, buffer_, vulkan.allocator);
      buffer_ = VK_NULL_HANDLE;
    }
    if (memory_ != VK_NULL_HANDLE) {
      dispatch.free_memory(vulkan.device, memory_, vulkan.allocator);
      memory_ = VK_NULL_HANDLE;
    }
    size_ = 0;
    allocation_size_ = 0;
    non_coherent_atom_size_ = 1;
    memory_flags_ = 0;
  }

  VkBuffer buffer() const { return buffer_; }
  VkDeviceSize size() const { return size_; }
  const void* mapped_data() const { return mapped_data_; }
  bool coherent() const {
    return (memory_flags_ & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) != 0;
  }

 private:
  VkBuffer buffer_ = VK_NULL_HANDLE;
  VkDeviceMemory memory_ = VK_NULL_HANDLE;
  void* mapped_data_ = nullptr;
  VkDeviceSize size_ = 0;
  VkDeviceSize allocation_size_ = 0;
  VkDeviceSize non_coherent_atom_size_ = 1;
  VkMemoryPropertyFlags memory_flags_ = 0;
};

}  // namespace zerofg
