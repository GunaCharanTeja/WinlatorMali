#pragma once

#include <vulkan/vulkan.h>

namespace zerofg {

struct VulkanDispatch {
  PFN_vkGetDeviceProcAddr get_device_proc_addr = nullptr;

  PFN_vkGetPhysicalDeviceMemoryProperties get_physical_device_memory_properties =
      nullptr;
  PFN_vkGetPhysicalDeviceProperties get_physical_device_properties = nullptr;
  PFN_vkGetPhysicalDeviceFormatProperties get_physical_device_format_properties =
      nullptr;

  PFN_vkCreateImage create_image = nullptr;
  PFN_vkDestroyImage destroy_image = nullptr;
  PFN_vkGetImageMemoryRequirements get_image_memory_requirements = nullptr;
  PFN_vkAllocateMemory allocate_memory = nullptr;
  PFN_vkFreeMemory free_memory = nullptr;
  PFN_vkBindImageMemory bind_image_memory = nullptr;

  PFN_vkCreateBuffer create_buffer = nullptr;
  PFN_vkDestroyBuffer destroy_buffer = nullptr;
  PFN_vkGetBufferMemoryRequirements get_buffer_memory_requirements = nullptr;
  PFN_vkBindBufferMemory bind_buffer_memory = nullptr;
  PFN_vkMapMemory map_memory = nullptr;
  PFN_vkUnmapMemory unmap_memory = nullptr;
  PFN_vkInvalidateMappedMemoryRanges invalidate_mapped_memory_ranges = nullptr;

  PFN_vkCreateImageView create_image_view = nullptr;
  PFN_vkDestroyImageView destroy_image_view = nullptr;

  PFN_vkCreateSampler create_sampler = nullptr;
  PFN_vkDestroySampler destroy_sampler = nullptr;

  PFN_vkCreateDescriptorSetLayout create_descriptor_set_layout = nullptr;
  PFN_vkDestroyDescriptorSetLayout destroy_descriptor_set_layout = nullptr;
  PFN_vkCreateDescriptorPool create_descriptor_pool = nullptr;
  PFN_vkDestroyDescriptorPool destroy_descriptor_pool = nullptr;
  PFN_vkAllocateDescriptorSets allocate_descriptor_sets = nullptr;
  PFN_vkUpdateDescriptorSets update_descriptor_sets = nullptr;

  PFN_vkCreatePipelineLayout create_pipeline_layout = nullptr;
  PFN_vkDestroyPipelineLayout destroy_pipeline_layout = nullptr;
  PFN_vkCreateShaderModule create_shader_module = nullptr;
  PFN_vkDestroyShaderModule destroy_shader_module = nullptr;
  PFN_vkCreateComputePipelines create_compute_pipelines = nullptr;
  PFN_vkDestroyPipeline destroy_pipeline = nullptr;
  PFN_vkGetPipelineExecutablePropertiesKHR
      get_pipeline_executable_properties = nullptr;
  PFN_vkGetPipelineExecutableStatisticsKHR
      get_pipeline_executable_statistics = nullptr;

  PFN_vkCmdBindPipeline cmd_bind_pipeline = nullptr;
  PFN_vkCmdBindDescriptorSets cmd_bind_descriptor_sets = nullptr;
  PFN_vkCmdPushConstants cmd_push_constants = nullptr;
  PFN_vkCmdDispatch cmd_dispatch = nullptr;
  // Resolved only by the optional deferred-F2 context, never by default Load.
  PFN_vkCmdDispatchIndirect cmd_dispatch_indirect = nullptr;
  PFN_vkCmdCopyBuffer cmd_copy_buffer = nullptr;
  PFN_vkCmdFillBuffer cmd_fill_buffer = nullptr;
  PFN_vkCmdWriteTimestamp cmd_write_timestamp = nullptr;
  PFN_vkCmdPipelineBarrier cmd_pipeline_barrier = nullptr;
  PFN_vkCmdPipelineBarrier2 cmd_pipeline_barrier_2 = nullptr;
  PFN_vkCmdBlitImage cmd_blit_image = nullptr;

  bool Load(VkInstance instance,
            VkDevice device,
            PFN_vkGetInstanceProcAddr get_instance_proc_addr) {
    if (instance == VK_NULL_HANDLE || device == VK_NULL_HANDLE ||
        get_instance_proc_addr == nullptr) {
      return false;
    }

    get_device_proc_addr = reinterpret_cast<PFN_vkGetDeviceProcAddr>(
        get_instance_proc_addr(instance, "vkGetDeviceProcAddr"));
    get_physical_device_memory_properties =
        reinterpret_cast<PFN_vkGetPhysicalDeviceMemoryProperties>(
            get_instance_proc_addr(
                instance, "vkGetPhysicalDeviceMemoryProperties"));
    get_physical_device_properties =
        reinterpret_cast<PFN_vkGetPhysicalDeviceProperties>(
            get_instance_proc_addr(instance, "vkGetPhysicalDeviceProperties"));
    get_physical_device_format_properties =
        reinterpret_cast<PFN_vkGetPhysicalDeviceFormatProperties>(
            get_instance_proc_addr(
                instance, "vkGetPhysicalDeviceFormatProperties"));

    if (!get_device_proc_addr ||
        !get_physical_device_memory_properties ||
        !get_physical_device_properties ||
        !get_physical_device_format_properties) {
      return false;
    }

#define ZEROFG_LOAD_DEVICE(name, type, vulkan_name)                       \
    name = reinterpret_cast<type>(                                        \
        get_device_proc_addr(device, vulkan_name));                       \
    if (!name) {                                                          \
      return false;                                                       \
    }

    ZEROFG_LOAD_DEVICE(create_image, PFN_vkCreateImage, "vkCreateImage");
    ZEROFG_LOAD_DEVICE(destroy_image, PFN_vkDestroyImage, "vkDestroyImage");
    ZEROFG_LOAD_DEVICE(get_image_memory_requirements,
                       PFN_vkGetImageMemoryRequirements,
                       "vkGetImageMemoryRequirements");
    ZEROFG_LOAD_DEVICE(allocate_memory,
                       PFN_vkAllocateMemory,
                       "vkAllocateMemory");
    ZEROFG_LOAD_DEVICE(free_memory, PFN_vkFreeMemory, "vkFreeMemory");
    ZEROFG_LOAD_DEVICE(bind_image_memory,
                       PFN_vkBindImageMemory,
                       "vkBindImageMemory");
    ZEROFG_LOAD_DEVICE(create_buffer, PFN_vkCreateBuffer, "vkCreateBuffer");
    ZEROFG_LOAD_DEVICE(destroy_buffer, PFN_vkDestroyBuffer,
                       "vkDestroyBuffer");
    ZEROFG_LOAD_DEVICE(get_buffer_memory_requirements,
                       PFN_vkGetBufferMemoryRequirements,
                       "vkGetBufferMemoryRequirements");
    ZEROFG_LOAD_DEVICE(bind_buffer_memory, PFN_vkBindBufferMemory,
                       "vkBindBufferMemory");
    ZEROFG_LOAD_DEVICE(map_memory, PFN_vkMapMemory, "vkMapMemory");
    ZEROFG_LOAD_DEVICE(unmap_memory, PFN_vkUnmapMemory, "vkUnmapMemory");
    ZEROFG_LOAD_DEVICE(invalidate_mapped_memory_ranges,
                       PFN_vkInvalidateMappedMemoryRanges,
                       "vkInvalidateMappedMemoryRanges");
    ZEROFG_LOAD_DEVICE(create_image_view,
                       PFN_vkCreateImageView,
                       "vkCreateImageView");
    ZEROFG_LOAD_DEVICE(destroy_image_view,
                       PFN_vkDestroyImageView,
                       "vkDestroyImageView");
    ZEROFG_LOAD_DEVICE(create_sampler,
                       PFN_vkCreateSampler,
                       "vkCreateSampler");
    ZEROFG_LOAD_DEVICE(destroy_sampler,
                       PFN_vkDestroySampler,
                       "vkDestroySampler");
    ZEROFG_LOAD_DEVICE(create_descriptor_set_layout,
                       PFN_vkCreateDescriptorSetLayout,
                       "vkCreateDescriptorSetLayout");
    ZEROFG_LOAD_DEVICE(destroy_descriptor_set_layout,
                       PFN_vkDestroyDescriptorSetLayout,
                       "vkDestroyDescriptorSetLayout");
    ZEROFG_LOAD_DEVICE(create_descriptor_pool,
                       PFN_vkCreateDescriptorPool,
                       "vkCreateDescriptorPool");
    ZEROFG_LOAD_DEVICE(destroy_descriptor_pool,
                       PFN_vkDestroyDescriptorPool,
                       "vkDestroyDescriptorPool");
    ZEROFG_LOAD_DEVICE(allocate_descriptor_sets,
                       PFN_vkAllocateDescriptorSets,
                       "vkAllocateDescriptorSets");
    ZEROFG_LOAD_DEVICE(update_descriptor_sets,
                       PFN_vkUpdateDescriptorSets,
                       "vkUpdateDescriptorSets");
    ZEROFG_LOAD_DEVICE(create_pipeline_layout,
                       PFN_vkCreatePipelineLayout,
                       "vkCreatePipelineLayout");
    ZEROFG_LOAD_DEVICE(destroy_pipeline_layout,
                       PFN_vkDestroyPipelineLayout,
                       "vkDestroyPipelineLayout");
    ZEROFG_LOAD_DEVICE(create_shader_module,
                       PFN_vkCreateShaderModule,
                       "vkCreateShaderModule");
    ZEROFG_LOAD_DEVICE(destroy_shader_module,
                       PFN_vkDestroyShaderModule,
                       "vkDestroyShaderModule");
    ZEROFG_LOAD_DEVICE(create_compute_pipelines,
                       PFN_vkCreateComputePipelines,
                       "vkCreateComputePipelines");
    ZEROFG_LOAD_DEVICE(destroy_pipeline,
                       PFN_vkDestroyPipeline,
                       "vkDestroyPipeline");
    ZEROFG_LOAD_DEVICE(cmd_bind_pipeline,
                       PFN_vkCmdBindPipeline,
                       "vkCmdBindPipeline");
    ZEROFG_LOAD_DEVICE(cmd_bind_descriptor_sets,
                       PFN_vkCmdBindDescriptorSets,
                       "vkCmdBindDescriptorSets");
    ZEROFG_LOAD_DEVICE(cmd_push_constants,
                       PFN_vkCmdPushConstants,
                       "vkCmdPushConstants");
    ZEROFG_LOAD_DEVICE(cmd_dispatch,
                       PFN_vkCmdDispatch,
                       "vkCmdDispatch");
    ZEROFG_LOAD_DEVICE(cmd_copy_buffer, PFN_vkCmdCopyBuffer,
                       "vkCmdCopyBuffer");
    ZEROFG_LOAD_DEVICE(cmd_fill_buffer, PFN_vkCmdFillBuffer,
                       "vkCmdFillBuffer");
    ZEROFG_LOAD_DEVICE(cmd_write_timestamp,
                       PFN_vkCmdWriteTimestamp,
                       "vkCmdWriteTimestamp");
    ZEROFG_LOAD_DEVICE(cmd_pipeline_barrier,
                       PFN_vkCmdPipelineBarrier,
                       "vkCmdPipelineBarrier");
    ZEROFG_LOAD_DEVICE(cmd_blit_image,
                       PFN_vkCmdBlitImage,
                       "vkCmdBlitImage");

#undef ZEROFG_LOAD_DEVICE

    // Optional diagnostic APIs. They are used only when the host confirms the
    // extension feature was enabled; missing pointers do not gate ZeroFG.
    get_pipeline_executable_properties =
        reinterpret_cast<PFN_vkGetPipelineExecutablePropertiesKHR>(
            get_device_proc_addr(device,
                                 "vkGetPipelineExecutablePropertiesKHR"));
    get_pipeline_executable_statistics =
        reinterpret_cast<PFN_vkGetPipelineExecutableStatisticsKHR>(
            get_device_proc_addr(device,
                                 "vkGetPipelineExecutableStatisticsKHR"));

    // Vulkan 1.3-only. The historical SubZero path must remain usable on the
    // legacy XenDroid Vulkan floor; RC1 validates this pointer and the
    // enabled synchronization2 feature independently.
    cmd_pipeline_barrier_2 = reinterpret_cast<PFN_vkCmdPipelineBarrier2>(
        get_device_proc_addr(device, "vkCmdPipelineBarrier2"));

    return true;
  }
};

}  // namespace zerofg


