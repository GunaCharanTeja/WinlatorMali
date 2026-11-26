#pragma once

#include <cstdint>
#include <memory>
#include <vector>

#include <vulkan/vulkan.h>
#include "zerofg/backend_capabilities.h"

namespace zerofg {

// ZeroFG deliberately does not own a Vulkan queue, swapchain, presentation,
// frame pacing, or synchronization with the host renderer.
//
// The host is responsible for:
//   - beginning/ending and submitting the command buffer;
//   - synchronization and image barriers;
//   - keeping input/output images alive;
//   - presenting the generated frame.
//
// ZeroFG only records temporal interpolation work into the supplied
// VkCommandBuffer.

enum class Status {
  kSuccess = 0,
  kInvalidArgument,
  kUnsupported,
  kOutOfMemory,
  kVulkanError,
};

// The product modes. Both run the same engine and logical contract.
enum class Mode : uint8_t {
  // Zero: a 20 px cell motion field verified at full resolution, with a
  // temporal prior, a photometric model, a continuity guard and its own
  // resolve. Stateful across pairs: see Image::sequence.
  kZero = 0,
  // ReallyZero: the economy tier of Zero, the same engine with two fine
  // propagation passes instead of four.
  kReallyZero,
};

// Algorithm and execution backend are independent axes: every backend
// produces the same logical result.
enum class Backend : uint8_t {
  kAuto = 0,
  kCompat,
  kModern,
  kQcom,
};

struct ActiveRect {
  uint32_t x = 0;
  uint32_t y = 0;
  uint32_t width = 0;
  uint32_t height = 0;
};

struct VulkanContext {
  VkInstance instance = VK_NULL_HANDLE;
  VkPhysicalDevice physical_device = VK_NULL_HANDLE;
  VkDevice device = VK_NULL_HANDLE;

  // ZeroFG resolves Vulkan entry points through the host loader so custom
  // ICDs and drivers use exactly the same Vulkan dispatch path as the host.
  PFN_vkGetInstanceProcAddr get_instance_proc_addr = nullptr;

  const VkAllocationCallbacks* allocator = nullptr;
};

struct Image {
  VkImage image = VK_NULL_HANDLE;
  VkImageView view = VK_NULL_HANDLE;
  VkImageLayout layout = VK_IMAGE_LAYOUT_UNDEFINED;
  VkFormat format = VK_FORMAT_UNDEFINED;
  uint32_t width = 0;
  uint32_t height = 0;
  VkImageUsageFlags usage = 0;
  // Optional backend qualification uses real creation facts; unknown callers
  // retain Compat rather than qualifying arbitrary imports by assumption.
  VkImageTiling tiling = VK_IMAGE_TILING_OPTIMAL;
  VkImageCreateFlags create_flags = 0;
  VkImageViewType view_type = VK_IMAGE_VIEW_TYPE_2D;
  bool creation_facts_known = false;

  // The physical image may contain padding or a letterboxed allocation.  A
  // zero-sized active rect means the complete physical image.
  ActiveRect active_rect;

  // Identity of the Real this image holds in the host's accepted order (any
  // strictly increasing counter; 0 = unknown). ZeroFG keeps the previous
  // pair's motion as a temporal prior and uses it only when the pair it is
  // about to record starts at the Real the previous recorded pair ended on,
  // i.e. when previous.sequence equals the current.sequence of a pair this
  // Interpolator recorded. A gap, a reset, a changed geometry or an unknown
  // (0) sequence simply start the prior from nothing.
  uint64_t sequence = 0;
};

struct CreateInfo {
  VulkanContext vulkan;

  Mode mode = Mode::kZero;
  Backend backend = Backend::kAuto;
  Capabilities capabilities;

  // Number of independent GPU resource contexts used for
  // frames in flight.
  uint32_t frame_context_count = 3;
};

class AlgorithmContext;

class Interpolator {
 public:
  static std::unique_ptr<Interpolator> Create(
      const CreateInfo& create_info,
      Status* status = nullptr);

  ~Interpolator();

  Interpolator(const Interpolator&) = delete;
  Interpolator& operator=(const Interpolator&) = delete;

  // Allocates/reallocates the internal working resources.
  // This should be called outside the hot presentation path whenever possible.
  Status Resize(uint32_t width,
                uint32_t height,
                VkFormat input_format,
                VkFormat output_format);

  // Records commands that generate a temporal frame between previous and
  // current.
  //
  // phase:
  //   0.0 = previous
  //   0.5 = midpoint (the ZeroFG 2x target)
  //   1.0 = current
  //
  // previous/current must have VK_IMAGE_USAGE_SAMPLED_BIT.
  // output must be distinct. The chosen backend requires either storage-image
  // output support or VK_IMAGE_USAGE_TRANSFER_DST_BIT for its format fallback.
  //
  // The command buffer must support compute and, when the format fallback is
  // selected, transfer/blit operations.
  // frame_context_index selects an independent GPU resource context.
  // The caller must not reuse the same index until the GPU submission
  // containing the previous Interpolate call for that index has completed.
  Status Interpolate(VkCommandBuffer command_buffer,
                     uint32_t frame_context_index,
                     const Image& previous,
                     const Image& current,
                     float phase,
                     const Image& output);

 private:
  explicit Interpolator(
      std::vector<std::unique_ptr<AlgorithmContext>> contexts);

  std::vector<std::unique_ptr<AlgorithmContext>> contexts_;
};

}  // namespace zerofg
