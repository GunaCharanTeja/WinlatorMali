#pragma once

#include <cstdint>
#include <vulkan/vulkan.h>

namespace zerofg {

struct Capabilities {
  uint32_t effective_api_version = VK_API_VERSION_1_0;
  bool synchronization2_enabled = false;
  bool shader_storage_image_extended_formats_enabled = false;
  bool pipeline_executable_extension_advertised = false;
  bool pipeline_executable_feature_supported = false;
  bool pipeline_executable_feature_enabled = false;

  // Backend experiments consume actual enabled logical-device features. These
  // supported facts are diagnostic only, never implicit permission to use them.
  bool shader_float16_supported = false;
  bool shader_float16_enabled = false;
  bool shader_int8_supported = false;
  bool shader_int8_enabled = false;
  bool shader_int16_supported = false;
  bool shader_int16_enabled = false;
  bool storage_buffer_16bit_access_supported = false;
  bool storage_buffer_16bit_access_enabled = false;
  uint32_t subgroup_size = 0;
  uint32_t subgroup_min_size = 0;
  uint32_t subgroup_max_size = 0;
  uint32_t max_compute_workgroup_subgroups = 0;
  VkShaderStageFlags subgroup_supported_stages = 0;
  VkSubgroupFeatureFlags subgroup_supported_operations = 0;
  bool subgroup_size_control_supported = false;
  bool subgroup_size_control_enabled = false;
  bool compute_full_subgroups_supported = false;
  bool compute_full_subgroups_enabled = false;
  VkShaderStageFlags required_subgroup_size_stages = 0;
  bool maintenance4_supported = false;
  bool maintenance4_enabled = false;
  bool shader_integer_dot_product_supported = false;
  bool shader_integer_dot_product_enabled = false;
  bool integer_dot_product_4x8_unsigned_accelerated = false;
  bool integer_dot_product_4x8_signed_accelerated = false;
  bool integer_dot_product_4x8_mixed_signedness_accelerated = false;
  bool filter_cubic_supported = false;
  bool filter_cubic_enabled = false;
  bool sampler_filter_minmax_supported = false;
  bool sampler_filter_minmax_enabled = false;
  bool filter_minmax_single_component_formats = false;
  bool filter_minmax_image_component_mapping = false;
  bool qcom_image_processing_supported = false;
  bool qcom_image_processing_enabled = false;
  bool qcom_texture_sample_weighted_supported = false;
  bool qcom_texture_sample_weighted_enabled = false;
  bool qcom_texture_box_filter_supported = false;
  bool qcom_texture_box_filter_enabled = false;
  bool qcom_texture_block_match_supported = false;
  bool qcom_texture_block_match_enabled = false;
  uint32_t qcom_max_weight_filter_phases = 0;
  VkExtent2D qcom_max_weight_filter_dimension = {};
  VkExtent2D qcom_max_block_match_region = {};
  VkExtent2D qcom_max_box_filter_block_size = {};
  // The following extensions remain strictly log-only in this batch.
  bool qcom_image_processing2_advertised = false;
  bool qcom_texture_block_match2_supported = false;
  VkExtent2D qcom_max_block_match_window = {};
  bool qcom_image_processing3_advertised = false;
  bool qcom_cubic_weights_advertised = false;
  bool qcom_selectable_cubic_weights_supported = false;
  bool qcom_cubic_clamp_advertised = false;
  bool qcom_cubic_range_clamp_supported = false;
  struct Format {
    VkFormat format = VK_FORMAT_UNDEFINED;
    VkImageUsageFlags usage = VK_IMAGE_USAGE_SAMPLED_BIT;
    VkImageCreateFlags create_flags = 0;
    VkImageTiling tiling = VK_IMAGE_TILING_OPTIMAL;
    VkImageViewType view_type = VK_IMAGE_VIEW_TYPE_2D;
    VkResult image_query_result = VK_ERROR_FORMAT_NOT_SUPPORTED;
    VkFormatFeatureFlags2 optimal_tiling_features = 0;
    bool cubic = false;
    bool cubic_minmax = false;
  };
  // R8_UNORM, R16_SFLOAT, R32_SFLOAT, source A2B10/RGBA8, R16_UNORM.
  Format backend_formats[6] = {};
};

}  // namespace zerofg
