// Modified in WinlatorMali: Mali form (16-wide subgroup D4, split D5 with the cell field as an image), any-phase resolve that reuses the analysis of the same pair.
// The ZeroFG engine (Zero and ReallyZero). The algorithm and its defaults were frozen from the ZeroFG laboratory; the shaders
// (shaders/rc3) are specialised here with the frozen values, and this engine matches the laboratory engine word for word.
//
//   D0p  the exposure model of A onto B, the A-B correlation and the texture ratio (one workgroup)
//   D0   the 20 px luma pyramid of A and B             D0a  A's luma lattice, one 4 x 4 block per cell      D0b  B's full-resolution luma plane
//   D1   the coarse evidence (L2)                      D2a, D2b  the refine (L2 -> L1 -> L0)
//   D4c  the global translation hypotheses             D4  the fine verification, four propagation passes with the temporal prior
//   D3   the cell analysis, the guard's counters and the history of the pair
//   D5   the resolve: warp trust, fallback, the continuity guard and the final colour
//
// The core records into the caller's command buffer and owns no queue: everything the lab engine did at Init with a submission (zeroing its buffers)
// is recorded in the first Interpolate of the context, and the temporal history lives in an Rc3Shared owned by the Interpolator (see rc3_shared.h).

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <limits>
#include <memory>
#include <utility>
#include <vector>

#include "algorithm.h"
#include "rc3/rc3_shared.h"
#include "vulkan_buffer.h"
#include "vulkan_dispatch.h"
#include "vulkan_image.h"


#include "rc3_crop.h"
#include "rc3_d0.h"
#include "rc3_d0a.h"
#include "rc3_d0b.h"
#include "rc3_d0p.h"
#include "rc3_d1.h"
#include "rc3_d2_h0.h"
#include "rc3_d3.h"
#include "rc3_d4.h"
#include "rc3_d4c.h"
#include "rc3_d4cc.h"
#include "rc3_d4cp.h"
#include "rc3_d4sg.h"
#include "rc3_d5a.h"
#include "rc3_d5ah.h"
#include "rc3_mali_d2.h"
#include "rc3_mali_d4.h"
#include "rc3_mali_d5a.h"
#include "rc3_mali_d5b.h"

namespace zerofg {
namespace {

constexpr uint32_t kPyramidSide = 64;      // the long side of the cell field
constexpr uint32_t kCameraSamples = 256;   // kCamSamples of shaders/rc3/rc3_common.glsl
constexpr uint32_t kCounterCount = 56;     // kCtrCount of shaders/rc3/rc3_common.glsl
constexpr int32_t kSamples = 4;            // D4 sample points per axis in a cell
constexpr int32_t kFineIterations = 4;     // D4 propagation passes (the descriptor sets; RC3 Lite runs the first two)
constexpr int32_t kLiteFineIterations = 2;  // Mode::kReallyZero: two fine passes
constexpr uint32_t kCtrD2aCells = 4, kCtrD2aH1 = 5, kCtrD2bCells = 6, kCtrD2bH1 = 7;

// The frozen parameters (the defaults of the lab profile at the freeze).
constexpr float kRidge = 0.015f, kDeltaCost = 0.06f, kRidgeFull = 0.05f, kConfMin = 0.05f;
constexpr float kMargin = 0.08f, kErrorCap = 0.2f;
constexpr int32_t kCandidates = 1, kTrustMode = 3;
constexpr float kFlatLow = 0.01f, kFlatHigh = 0.04f, kAmbiguityFloor = 0.02f;
constexpr float kQualityLow = 0.04f, kQualityHigh = 0.14f;
constexpr float kGuard = 0.6f, kGuardCost = 0.06f, kGuardUnique = 0.0f, kGuardCorrelation = 0.3f, kGuardFlat = 0.1f;
constexpr float kGuardRelative = 0.13f, kGuardInformative = 0.15f;
constexpr float kFineGain = 0.015f, kFineShare = 0.10f;
constexpr int32_t kRadius = 2, kPrior = 32, kDrift = 1;
constexpr float kPriorConfidence = 0.04f, kPriorDecay = 0.85f, kPriorRoot = 0.04f;
constexpr float kSupportTrust = 1.0f;
constexpr float kFallbackLow = 0.2f, kFallbackHigh = 0.5f;
constexpr float kStaticEps = 0.012f, kStaticDelta = 0.08f, kStaticGrad = 0.06f;
constexpr int32_t kStaticN = 2;
constexpr float kLayerMagnitude = 0.12f, kLayerCosine = 0.6f, kLayerAway = 0.04f, kLayerOffset = 1.0f;
constexpr float kPhotoConfidence = 0.45f, kPhotoDeadZone = 0.06f, kPhotoCorrelation = 0.4f;
constexpr int32_t kTemporal = 7;  // candidate, recorded-root support, look-back

uint32_t Groups(uint32_t extent, uint32_t local) { return (extent + local - 1) / local; }

// The RC1 pyramid geometry: the long side is 64 cells, the short side keeps the aspect, rounded to a multiple of 4 (so L1 and L2 divide exactly).
VkExtent2D ComputeL0Extent(uint32_t width, uint32_t height) {
  const bool landscape = width >= height;
  const uint32_t major = std::max(width, height);
  const uint32_t minor = std::min(width, height);
  uint32_t fitted =
      major ? uint32_t((uint64_t(minor) * kPyramidSide + uint64_t(major) / 2) / major) : 0;
  fitted = std::clamp(((fitted + 2) / 4) * 4, 8u, kPyramidSide);
  return landscape ? VkExtent2D{kPyramidSide, fitted} : VkExtent2D{fitted, kPyramidSide};
}

// The cells tile a width x height extent: whole cells, and the 4 x 4 lattice of D0a inside each one (exact at any such extent).
bool CellsTile(uint32_t width, uint32_t height) {
  const VkExtent2D l0 = ComputeL0Extent(width, height);
  if (width % l0.width != 0 || height % l0.height != 0) {
    return false;
  }
  const uint32_t cell_w = width / l0.width, cell_h = height / l0.height;
  const uint32_t stride = (cell_w + uint32_t(kSamples) - 1) / uint32_t(kSamples);
  return 1 + stride * uint32_t(kSamples - 1) < cell_w && 1 + stride * uint32_t(kSamples - 1) < cell_h;
}

// The extent RC3 runs a picture at: the picture itself when the cells tile it, otherwise the smallest larger extent they tile (Halo 3's 1152 x 640
// runs at 1152 x 648). False when none is within reach.
bool TiledExtent(uint32_t width, uint32_t height, uint32_t* tiled_width, uint32_t* tiled_height) {
  constexpr uint32_t kReach = 128;  // a whole cell more than the widest cell on either axis
  uint64_t best_area = std::numeric_limits<uint64_t>::max();
  for (uint32_t h = height; h < height + kReach && h <= 16384; ++h) {
    for (uint32_t w = width; w < width + kReach && w <= 16384; ++w) {
      const uint64_t area = uint64_t(w) * h;
      if (area >= best_area) {
        break;
      }
      if (CellsTile(w, h)) {
        best_area = area;
        *tiled_width = w;
        *tiled_height = h;
        break;
      }
    }
  }
  return best_area != std::numeric_limits<uint64_t>::max();
}

ActiveRect ResolveRect(const Image& image) {
  if (image.active_rect.width == 0 || image.active_rect.height == 0) {
    return {0, 0, image.width, image.height};
  }
  return image.active_rect;
}

struct Pass {
  VkDescriptorSetLayout set_layout = VK_NULL_HANDLE;
  VkPipelineLayout layout = VK_NULL_HANDLE;
  VkPipeline pipeline = VK_NULL_HANDLE;
  VkDescriptorPool pool = VK_NULL_HANDLE;
  std::vector<VkDescriptorSet> sets;
};

enum class Stage : uint32_t { kCrop, kD0p, kD0, kD0a, kD0b, kD1, kD2a, kD2b, kD4c, kD4cCost, kD4cPick, kD4, kD3, kD5, kD5b, kCount };

class Rc3Context final : public AlgorithmContext {
 public:
  Rc3Context(const CreateInfo& create_info, std::shared_ptr<Rc3Shared> shared)
      : vulkan_(create_info.vulkan),
        capabilities_(create_info.capabilities),
        backend_(create_info.backend),
        fine_iterations_(create_info.mode == Mode::kReallyZero ? kLiteFineIterations : kFineIterations),
        shared_(std::move(shared)) {}

  ~Rc3Context() override { DestroyAll(); }

  bool Initialize() {
    if (!dispatch_.Load(vulkan_.instance, vulkan_.device, vulkan_.get_instance_proc_addr) ||
        dispatch_.cmd_pipeline_barrier_2 == nullptr || dispatch_.cmd_fill_buffer == nullptr ||
        dispatch_.cmd_blit_image == nullptr || dispatch_.create_sampler == nullptr) {
      return false;
    }
    cmd_update_buffer_ = reinterpret_cast<PFN_vkCmdUpdateBuffer>(
        dispatch_.get_device_proc_addr(vulkan_.device, "vkCmdUpdateBuffer"));
    return shared_ != nullptr && cmd_update_buffer_ != nullptr;
  }

  Status Resize(uint32_t width, uint32_t height, VkFormat input_format,
                VkFormat output_format) override {
    if (width == 0 || height == 0 || width > 16384 || height > 16384) {
      return Status::kInvalidArgument;
    }
    if (!capabilities_.shader_storage_image_extended_formats_enabled ||
        capabilities_.effective_api_version < VK_API_VERSION_1_3 ||
        !capabilities_.synchronization2_enabled) {
      return Status::kUnsupported;
    }
    const VkFormatFeatureFlags input_features =
        VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT | VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT;
    const VkFormatFeatureFlags work_features =
        VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT | VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT;
    if (!HasOptimalFeatures(input_format, input_features) ||
        !HasOptimalFeatures(VK_FORMAT_R8_UNORM, work_features) ||
        !HasOptimalFeatures(VK_FORMAT_A2B10G10R10_UNORM_PACK32, VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT |
                                                                    VK_FORMAT_FEATURE_BLIT_SRC_BIT)) {
      return Status::kUnsupported;
    }
    const bool direct = output_format == VK_FORMAT_A2B10G10R10_UNORM_PACK32;
    if (!direct && !HasOptimalFeatures(output_format, VK_FORMAT_FEATURE_BLIT_DST_BIT)) {
      return Status::kUnsupported;
    }
    // The cells must tile the extent the engine runs at and the 4 x 4 lattice must stay inside its cell (CellsTile). A picture they do not tile
    // runs padded: the crop repeats its last column and row into the pad, the engine runs on the padded copy, and only the picture is written out
    // (through the internal image and the blit). A tiled picture runs exactly as before.
    uint32_t tiled_w = 0, tiled_h = 0;
    if (!TiledExtent(width, height, &tiled_w, &tiled_h)) {
      return Status::kUnsupported;
    }
    const bool padded = tiled_w != width || tiled_h != height;
    if (padded && (input_format != VK_FORMAT_A2B10G10R10_UNORM_PACK32 ||
                   !HasOptimalFeatures(output_format, VK_FORMAT_FEATURE_BLIT_DST_BIT))) {
      return Status::kUnsupported;  // the crop copies A2B10G10R10 only, and the picture leaves through a blit
    }
    const VkExtent2D l0 = ComputeL0Extent(tiled_w, tiled_h);

    DestroyWorking();
    ResolveForms(input_format);
    l0_w_ = l0.width;
    l0_h_ = l0.height;
    l1_w_ = l0.width / 2;
    l1_h_ = l0.height / 2;
    l2_w_ = l0.width / 4;
    l2_h_ = l0.height / 4;
    width_ = tiled_w;
    height_ = tiled_h;
    picture_w_ = width;
    picture_h_ = height;
    input_format_ = input_format;
    output_format_ = output_format;
    direct_output_ = direct && !padded;

    Status status = CreateWorkingResources();
    if (status == Status::kSuccess) {
      status = CreatePasses();
    }
    if (status == Status::kSuccess) {
      status = EnsureShared();
    }
    if (status != Status::kSuccess) {
      DestroyWorking();
      return status;
    }
    BindStaticDescriptors();
    initialized_ = true;
    first_record_ = true;
    return Status::kSuccess;
  }

  Status Interpolate(VkCommandBuffer command_buffer, const Image& previous, const Image& current,
                     float phase, const Image& output) override {
    if (!initialized_ || command_buffer == VK_NULL_HANDLE || previous.image == VK_NULL_HANDLE ||
        previous.view == VK_NULL_HANDLE || current.image == VK_NULL_HANDLE ||
        current.view == VK_NULL_HANDLE || output.image == VK_NULL_HANDLE ||
        output.view == VK_NULL_HANDLE || output.width != picture_w_ || output.height != picture_h_ ||
        previous.width == 0 || previous.height == 0 || current.width != previous.width ||
        current.height != previous.height || previous.format != input_format_ ||
        current.format != input_format_ || output.format != output_format_ ||
        (previous.usage & VK_IMAGE_USAGE_SAMPLED_BIT) == 0 ||
        (current.usage & VK_IMAGE_USAGE_SAMPLED_BIT) == 0 ||
        previous.layout == VK_IMAGE_LAYOUT_UNDEFINED || current.layout == VK_IMAGE_LAYOUT_UNDEFINED ||
        output.layout == VK_IMAGE_LAYOUT_UNDEFINED || output.image == previous.image ||
        output.image == current.image || !std::isfinite(phase)) {
      return Status::kInvalidArgument;
    }
    if (phase <= 0.0f || phase >= 1.0f) {
      return Status::kUnsupported;
    }
    // The active rectangle is the picture (it is what Resize was called with); a physical image larger than it, a rectangle that does not start at
    // (0, 0), or a picture that runs padded, is cropped into a tight copy first.
    const ActiveRect rect = ResolveRect(previous), current_rect = ResolveRect(current);
    if (rect.width != picture_w_ || rect.height != picture_h_ || current_rect.x != rect.x || current_rect.y != rect.y ||
        current_rect.width != rect.width || current_rect.height != rect.height ||
        rect.x > previous.width || rect.y > previous.height || rect.width > previous.width - rect.x ||
        rect.height > previous.height - rect.y) {
      return Status::kInvalidArgument;
    }
    const bool crop = width_ != picture_w_ || height_ != picture_h_ || rect.x != 0 || rect.y != 0 ||
                      previous.width != picture_w_ || previous.height != picture_h_;
    if (crop) {
      if (input_format_ != VK_FORMAT_A2B10G10R10_UNORM_PACK32) {
        return Status::kUnsupported;
      }
      const Status status = EnsureCropImages();
      if (status != Status::kSuccess) {
        return status;
      }
    }
    // The resolve writes the output directly when it is an A2B10G10R10 storage image; any other output, or one without storage usage, is written
    // through the internal image and blitted.
    const bool write_direct = direct_output_ && (output.usage & VK_IMAGE_USAGE_STORAGE_BIT) != 0;
    if (!write_direct && (output.usage & VK_IMAGE_USAGE_TRANSFER_DST_BIT) == 0) {
      return Status::kInvalidArgument;
    }

    // Another phase of the pair the last record analysed (a multiplier above 2): the analysis is reused and only the resolve runs.
    if (previous.sequence != 0 && previous.sequence == last_previous_sequence_ && current.sequence == last_current_sequence_ &&
        previous.image == last_previous_image_ && current.image == last_current_image_ && output.image == last_output_image_ && !crop) {
      RecordFrame(command_buffer, output, write_direct, phase, true);
      return Status::kSuccess;
    }
    last_previous_sequence_ = previous.sequence;
    last_current_sequence_ = current.sequence;
    last_previous_image_ = previous.image;
    last_current_image_ = current.image;
    last_output_image_ = output.image;

    // Which history slot this pair reads and which it writes (see rc3_shared.h).
    Rc3Shared& shared = *shared_;
    // The prior is the previous pair's motion: it is read only when this pair starts at the Real that pair ended on AND spans the same number of
    // Source frames (a gap inside the pair, an IssueSwap the host did not accept, scales its motion). Sequences must strictly increase; 0 = unknown.
    const uint64_t span = (previous.sequence != 0 && current.sequence > previous.sequence) ? current.sequence - previous.sequence : 0;
    int read_slot = -1;
    if (span != 0) {
      for (int slot = 0; slot < 2; ++slot) {
        if (shared.history_sequence[slot] == previous.sequence && shared.history_span[slot] == span) read_slot = slot;
      }
    }
    const bool continuous = read_slot >= 0;
    const int write_slot = continuous ? 1 - read_slot : 0;
    if (!continuous) {
      read_slot = 1 - write_slot;
    }

    const VkImageView source_a = crop ? tight_[0].view() : previous.view;
    const VkImageView source_b = crop ? tight_[1].view() : current.view;
    const VkImageLayout layout_a = crop ? VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL : previous.layout;
    const VkImageLayout layout_b = crop ? VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL : current.layout;
    UpdateDynamicDescriptors(source_a, layout_a, source_b, layout_b, output, write_direct, read_slot, write_slot);
    if (crop) {
      UpdateCropDescriptors(previous, current);
    }

    // Everything the earlier submissions of this queue wrote (the history of the previous pair above all) is visible to this record, and this
    // record's writes do not overtake their reads.
    MemoryBarrier(command_buffer, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                  VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT | VK_ACCESS_2_TRANSFER_WRITE_BIT,
                  VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                  VK_ACCESS_2_SHADER_STORAGE_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT |
                      VK_ACCESS_2_TRANSFER_WRITE_BIT);
    if (first_record_) {
      // What the lab engine did at Init with a submission: every working buffer zeroed, and the photometric estimate at the identity (gain 1, bias 0,
      // correlation 1 not measured, texture ratio unknown) for any pass that reads it before the estimator has written it.
      for (OwnedBuffer* buffer : AllBuffers()) {
        dispatch_.cmd_fill_buffer(command_buffer, buffer->buffer(), 0, VK_WHOLE_SIZE, 0);
      }
      const float identity[12] = {1.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, -1.0f, 0.0f, 1.0f, 1.0f, 1.0f, 0.0f};
      cmd_update_buffer_(command_buffer, photo_.buffer(), 0, sizeof(identity), identity);
      first_record_ = false;
    }
    if (!continuous) {
      // No history for this pair: D4 reads zeros (no candidate, no support).
      dispatch_.cmd_fill_buffer(command_buffer, shared.history[read_slot].buffer(), 0, VK_WHOLE_SIZE, 0);
      shared.history_sequence[read_slot] = 0;
      shared.history_span[read_slot] = 0;
    }
    dispatch_.cmd_fill_buffer(command_buffer, counters_.buffer(), 0, VK_WHOLE_SIZE, 0);
    MemoryBarrier(command_buffer, VK_PIPELINE_STAGE_2_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT,
                  VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                  VK_ACCESS_2_SHADER_STORAGE_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);

    if (crop) {
      RecordCrop(command_buffer, rect);
    }
    RecordFrame(command_buffer, output, write_direct, phase, false);

    shared.history_sequence[write_slot] = current.sequence;
    shared.history_span[write_slot] = span;
    return Status::kSuccess;
  }

 private:
  // ---- capabilities ------------------------------------------------------------------------------------------------------------------------
  VkFormatProperties FormatProperties(VkFormat format) const {
    VkFormatProperties properties{};
    dispatch_.get_physical_device_format_properties(vulkan_.physical_device, format, &properties);
    return properties;
  }

  bool HasOptimalFeatures(VkFormat format, VkFormatFeatureFlags features) const {
    return (FormatProperties(format).optimalTilingFeatures & features) == features;
  }

  // Compat is the golden form: the 32-bit pass, the tournaments over shared memory and the nine-tap kernel. Auto, Modern and QCOM take the generic
  // fast paths the device really has enabled: half colour/error/trust in D5, the subgroup D4, the hardware cubic final colour.
  void ResolveForms(VkFormat input_format) {
    const bool compat = backend_ == Backend::kCompat;
    const uint32_t needed_ops = VK_SUBGROUP_FEATURE_ARITHMETIC_BIT | VK_SUBGROUP_FEATURE_BALLOT_BIT |
                                VK_SUBGROUP_FEATURE_SHUFFLE_BIT;
    const bool subgroup_device =
        capabilities_.subgroup_size == 64 && capabilities_.subgroup_min_size == 64 &&
        capabilities_.subgroup_max_size == 64 &&
        (capabilities_.subgroup_supported_operations & needed_ops) == needed_ops &&
        (capabilities_.subgroup_supported_stages & VK_SHADER_STAGE_COMPUTE_BIT) != 0;
    use_subgroup_ = !compat && subgroup_device;
    half_resolve_ = !compat && capabilities_.shader_float16_enabled;
    // The Mali form (16-wide subgroups, fp16): D4 one subgroup per cell, D2 without compaction, D5 split in two passes.
    mali_ = !compat && half_resolve_ && capabilities_.subgroup_size == 16 && capabilities_.subgroup_min_size == 16 &&
        capabilities_.subgroup_max_size == 16 && (capabilities_.subgroup_supported_operations & needed_ops) == needed_ops &&
        (capabilities_.subgroup_supported_stages & VK_SHADER_STAGE_COMPUTE_BIT) != 0;
    precision_ = half_resolve_ ? 16 : 32;
    bool cubic_ok = false;
    if (!compat && capabilities_.filter_cubic_enabled) {
      cubic_ok = (FormatProperties(input_format).optimalTilingFeatures &
                  VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_CUBIC_BIT_EXT) != 0;
    }
    sharp_ = cubic_ok ? 3 : 1;
  }

  // ---- resources ---------------------------------------------------------------------------------------------------------------------------
  std::array<OwnedBuffer*, 20> AllBuffers() {
    return {&seed_field_, &l1_field_, &raw_field_, &cell_field_, &fine_[0], &fine_[1], &observ_,
            &fine_alt_[0], &fine_alt_[1], &aux_[0], &aux_[1], &aux_zero_, &changed_[0], &changed_[1],
            &active_list_, &coarse_alt_, &l1_alt_, &raw_alt_, &cell_alt_, &camera_buffer_};
  }

  Status CreateWorkingResources() {
    const VkImageUsageFlags work = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
    const uint32_t extents[3][2] = {{l0_w_, l0_h_}, {l1_w_, l1_h_}, {l2_w_, l2_h_}};
    for (auto& per_source : pyramid_) {
      for (uint32_t level = 0; level < 3; ++level) {
        Status status = per_source[level].Create(vulkan_, dispatch_, extents[level][0], extents[level][1],
                                                 VK_FORMAT_R8_UNORM, work);
        if (status != Status::kSuccess) return status;
      }
    }
    Status status = luma_b_.Create(vulkan_, dispatch_, width_, height_, VK_FORMAT_R8_UNORM, work);
    if (status != Status::kSuccess) return status;
    status = lattice_.Create(vulkan_, dispatch_, l0_w_ * kSamples, l0_h_ * kSamples, VK_FORMAT_R8_UNORM, work);
    if (status != Status::kSuccess) return status;
    // The L1 lattice is written by D0a whatever the chain: a dummy one block image.
    status = lattice_l1_.Create(vulkan_, dispatch_, 1, 1, VK_FORMAT_R8_UNORM, work);
    if (status != Status::kSuccess) return status;
    status = camera_image_.Create(vulkan_, dispatch_, kCameraSamples, 1, VK_FORMAT_R8_UNORM, work);
    if (status != Status::kSuccess) return status;
    status = phase_dummy_.Create(vulkan_, dispatch_, 1, 1, VK_FORMAT_R8_UNORM, work);
    if (status != Status::kSuccess) return status;
    status = fallback_output_.Create(vulkan_, dispatch_, width_, height_, VK_FORMAT_A2B10G10R10_UNORM_PACK32,
                                     VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT);
    if (status != Status::kSuccess) return status;

    const VkDeviceSize c2 = VkDeviceSize(l2_w_) * l2_h_, c1 = VkDeviceSize(l1_w_) * l1_h_,
                       c0 = VkDeviceSize(l0_w_) * l0_h_;
    const VkBufferUsageFlags storage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
    const std::array<std::pair<OwnedBuffer*, VkDeviceSize>, 20> buffers = {{
        {&seed_field_, c2 * 16}, {&l1_field_, c1 * 16}, {&raw_field_, c0 * 16},
        {&fine_[0], c0 * 16}, {&fine_[1], c0 * 16}, {&observ_, c0 * 16}, {&fine_alt_[0], c0 * 16},
        {&fine_alt_[1], c0 * 16}, {&aux_[0], c0 * 16}, {&aux_[1], c0 * 16}, {&aux_zero_, c0 * 16},
        {&changed_[0], c0 * 4}, {&changed_[1], c0 * 4}, {&active_list_, c0 * 4}, {&coarse_alt_, c2 * 2 * 16},
        {&l1_alt_, c1 * 16}, {&raw_alt_, c0 * 16}, {&cell_alt_, c0 * 16}, {&camera_buffer_, 4096},
        {&counters_, kCounterCount * 4}}};
    for (const auto& [buffer, size] : buffers) {
      status = buffer->Create(vulkan_, dispatch_, size, storage);
      if (status != Status::kSuccess) return status;
    }
    status = photo_.Create(vulkan_, dispatch_, 48, storage);
    if (status != Status::kSuccess) return status;
    status = split_list_.Create(vulkan_, dispatch_, VkDeviceSize(width_) * height_ * 4, storage);
    if (status != Status::kSuccess) return status;
    status = split_args_.Create(vulkan_, dispatch_, 16, storage | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT);
    if (status != Status::kSuccess) return status;
    // The resolve reads the cells through the texture unit (a copy of the cell field as an image): its load/store unit is
    // the bottleneck on a Mali-G615 (a texel buffer is not enough: Mali fetches those on the load/store unit too).
    status = cell_field_.Create(vulkan_, dispatch_, c0 * 16, storage | VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
    if (status != Status::kSuccess) return status;
    status = cell_field_image_.Create(vulkan_, dispatch_, l0_w_, l0_h_, VK_FORMAT_R32G32B32A32_SFLOAT,
                                      VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
    if (status != Status::kSuccess) return status;

    VkSamplerCreateInfo sampler_info{VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO};
    sampler_info.magFilter = VK_FILTER_LINEAR;
    sampler_info.minFilter = VK_FILTER_LINEAR;
    sampler_info.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
    sampler_info.addressModeU = sampler_info.addressModeV = sampler_info.addressModeW =
        VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    sampler_info.minLod = 0.0f;
    sampler_info.maxLod = 0.0f;
    if (dispatch_.create_sampler(vulkan_.device, &sampler_info, vulkan_.allocator, &sampler_) != VK_SUCCESS) {
      return Status::kVulkanError;
    }
    if (sharp_ == 3) {
      VkSamplerCreateInfo cubic_info = sampler_info;
      cubic_info.magFilter = VK_FILTER_CUBIC_EXT;
      cubic_info.minFilter = VK_FILTER_CUBIC_EXT;
      if (dispatch_.create_sampler(vulkan_.device, &cubic_info, vulkan_.allocator, &cubic_sampler_) !=
          VK_SUCCESS) {
        return Status::kVulkanError;
      }
    }
    return Status::kSuccess;
  }

  // The temporal history (two slots) belongs to the Interpolator: the first context to be resized creates it for the geometry, the others find it.
  Status EnsureShared() {
    Rc3Shared& shared = *shared_;
    const VkDeviceSize bytes = VkDeviceSize(l0_w_) * l0_h_ * 12;
    if (shared.width == width_ && shared.height == height_ && shared.history[0].buffer() != VK_NULL_HANDLE) {
      return Status::kSuccess;
    }
    for (int slot = 0; slot < 2; ++slot) {
      shared.history[slot].Destroy(vulkan_, dispatch_);
      shared.history_sequence[slot] = 0;
      shared.history_span[slot] = 0;
      Status status = shared.history[slot].Create(
          vulkan_, dispatch_, bytes, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT);
      if (status != Status::kSuccess) return status;
    }
    shared.width = width_;
    shared.height = height_;
    return Status::kSuccess;
  }

  // ---- passes ------------------------------------------------------------------------------------------------------------------------------
  std::vector<int32_t> Spec(int mode, int variant, int alt_stride = 1, int counter_cells = 0,
                            int counter_h1 = 0) const {
    // 0 mode, 1 precision, 2 variant, 3 debug, 4 alt stride, 5 cells counter, 6 H1 counter, 7 D4 samples, 8 diagnostic counters (never in the product).
    return {mode, precision_, variant, 0, alt_stride, counter_cells, counter_h1, kSamples, 0};
  }

  bool MakePass(Pass* pass, const uint32_t* code, size_t bytes, const std::vector<VkDescriptorType>& bindings,
                uint32_t push_bytes, const std::vector<int32_t>& spec, uint32_t set_count) {
    std::vector<VkDescriptorSetLayoutBinding> layout_bindings;
    for (uint32_t i = 0; i < bindings.size(); ++i) {
      VkDescriptorSetLayoutBinding b{};
      b.binding = i;
      b.descriptorType = bindings[i];
      b.descriptorCount = 1;
      b.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
      layout_bindings.push_back(b);
    }
    VkDescriptorSetLayoutCreateInfo dsl{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
    dsl.bindingCount = uint32_t(layout_bindings.size());
    dsl.pBindings = layout_bindings.data();
    if (dispatch_.create_descriptor_set_layout(vulkan_.device, &dsl, vulkan_.allocator, &pass->set_layout) != VK_SUCCESS) {
      return false;
    }
    VkPushConstantRange range{VK_SHADER_STAGE_COMPUTE_BIT, 0, push_bytes};
    VkPipelineLayoutCreateInfo pl{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
    pl.setLayoutCount = 1;
    pl.pSetLayouts = &pass->set_layout;
    pl.pushConstantRangeCount = push_bytes ? 1 : 0;
    pl.pPushConstantRanges = &range;
    if (dispatch_.create_pipeline_layout(vulkan_.device, &pl, vulkan_.allocator, &pass->layout) != VK_SUCCESS) {
      return false;
    }
    VkShaderModuleCreateInfo smi{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
    smi.codeSize = bytes;
    smi.pCode = code;
    VkShaderModule module = VK_NULL_HANDLE;
    if (dispatch_.create_shader_module(vulkan_.device, &smi, vulkan_.allocator, &module) != VK_SUCCESS) {
      return false;
    }
    std::vector<VkSpecializationMapEntry> entries;
    for (uint32_t i = 0; i < spec.size(); ++i) {
      entries.push_back({i, i * uint32_t(sizeof(int32_t)), sizeof(int32_t)});
    }
    VkSpecializationInfo spec_info{};
    spec_info.mapEntryCount = uint32_t(entries.size());
    spec_info.pMapEntries = entries.data();
    spec_info.dataSize = spec.size() * sizeof(int32_t);
    spec_info.pData = spec.data();
    VkComputePipelineCreateInfo cpi{VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};
    cpi.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    cpi.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    cpi.stage.module = module;
    cpi.stage.pName = "main";
    cpi.stage.pSpecializationInfo = spec.empty() ? nullptr : &spec_info;
    cpi.layout = pass->layout;
    const VkResult result = dispatch_.create_compute_pipelines(vulkan_.device, VK_NULL_HANDLE, 1, &cpi,
                                                               vulkan_.allocator, &pass->pipeline);
    dispatch_.destroy_shader_module(vulkan_.device, module, vulkan_.allocator);
    if (result != VK_SUCCESS) {
      return false;
    }
    std::vector<VkDescriptorPoolSize> sizes;
    for (VkDescriptorType type : bindings) {
      auto it = std::find_if(sizes.begin(), sizes.end(), [&](const VkDescriptorPoolSize& s) { return s.type == type; });
      if (it == sizes.end()) sizes.push_back({type, set_count});
      else it->descriptorCount += set_count;
    }
    VkDescriptorPoolCreateInfo dpi{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
    dpi.maxSets = set_count;
    dpi.poolSizeCount = uint32_t(sizes.size());
    dpi.pPoolSizes = sizes.data();
    if (dispatch_.create_descriptor_pool(vulkan_.device, &dpi, vulkan_.allocator, &pass->pool) != VK_SUCCESS) {
      return false;
    }
    std::vector<VkDescriptorSetLayout> layouts(set_count, pass->set_layout);
    pass->sets.assign(set_count, VK_NULL_HANDLE);
    VkDescriptorSetAllocateInfo dsa{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
    dsa.descriptorPool = pass->pool;
    dsa.descriptorSetCount = set_count;
    dsa.pSetLayouts = layouts.data();
    return dispatch_.allocate_descriptor_sets(vulkan_.device, &dsa, pass->sets.data()) == VK_SUCCESS;
  }

  Status CreatePasses() {
    const VkDescriptorType kSampler = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    const VkDescriptorType kImage = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    const VkDescriptorType kBuffer = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    auto with_agrid = [](std::vector<int32_t> v, int steps) {
      v.resize(24, 0);
      v[23] = steps;  // ZFG_AGRID: the A lattice (always, here)
      return v;
    };
    auto with_photo = [](std::vector<int32_t> v) {
      v.resize(10, 0);
      v[9] = 1;  // ZFG_PHOTO: the exposure normalisation of A
      return v;
    };
    std::vector<int32_t> d0p = Spec(1, 1);  // variant 1: the gain and the bias (photo = 2)
    d0p[3] = 0;                             // ZFG_CHROMA off
    std::vector<int32_t> d3 = Spec(1, 0);
    d3.resize(33, 0);
    d3[32] = 1;  // ZFG_ATEX: A's lattice gives the texture of a cell (the relative match of the guard)
    d3[29] = 1;  // ZFG_HISTORY
    std::vector<int32_t> d4 = Spec(1, 0);
    d4.resize(32, 0);
    d4[26] = kTemporal;       // ZFG_TEMPORAL
    d4[23] = 1;               // ZFG_AGRID
    d4[24] = 1;               // ZFG_PENFORM: branch-free over the nine neighbours
    d4[10] = kRadius;         // ZFG_RADIUS
    d4[12] = kPrior;          // ZFG_PRIOR
    d4[17] = kDrift;          // ZFG_DRIFT
    d4[20] = 1;               // ZFG_MOVE
    d4[21] = 1;               // ZFG_WINPEN
    std::vector<int32_t> d5 = Spec(1, 0);
    d5.resize(38, 0);
    d5[37] = int32_t(kGuardInformative * 1000.0f + 0.5f);  // ZFG_GINFO
    d5[36] = int32_t(kGuardRelative * 1000.0f + 0.5f);     // ZFG_GREL
    d5[34] = 1;                                            // ZFG_FBUNK
    d5[30] = 1;                                            // ZFG_PHOTO
    d5[26] = sharp_;                                       // ZFG_SHARP
    d5[27] = 1;                                            // ZFG_SGUARD
    d5[29] = 4;                                            // ZFG_FB: the blend where the PAIR is continuous
    const uint32_t* d4_code = use_subgroup_ ? zerofg_rc3_d4sg_spv : zerofg_rc3_d4_spv;
    size_t d4_bytes = use_subgroup_ ? sizeof(zerofg_rc3_d4sg_spv) : sizeof(zerofg_rc3_d4_spv);
    const uint32_t* d5_code = half_resolve_ ? zerofg_rc3_d5ah_spv : zerofg_rc3_d5a_spv;
    size_t d5_bytes = half_resolve_ ? sizeof(zerofg_rc3_d5ah_spv) : sizeof(zerofg_rc3_d5a_spv);
    const uint32_t* d5b_code = d5_code;
    size_t d5b_bytes = d5_bytes;
    const uint32_t* d2_code = zerofg_rc3_d2_h0_spv;
    size_t d2_bytes = sizeof(zerofg_rc3_d2_h0_spv);
    split_ = false;
    if (mali_) {
      d4_code = zerofg_rc3_mali_d4_spv;
      d4_bytes = sizeof(zerofg_rc3_mali_d4_spv);
      d5_code = zerofg_rc3_mali_d5a_spv;
      d5_bytes = sizeof(zerofg_rc3_mali_d5a_spv);
      d5b_code = zerofg_rc3_mali_d5b_spv;
      d5b_bytes = sizeof(zerofg_rc3_mali_d5b_spv);
      d2_code = zerofg_rc3_mali_d2_spv;
      d2_bytes = sizeof(zerofg_rc3_mali_d2_spv);
      split_ = true;
    }
    const bool ok =
        MakePass(&passes_[uint32_t(Stage::kCrop)], zerofg_rc3_crop_spv, sizeof(zerofg_rc3_crop_spv),
                 {kSampler, kImage}, 24, {}, 2) &&
        MakePass(&passes_[uint32_t(Stage::kD0p)], zerofg_rc3_d0p_spv, sizeof(zerofg_rc3_d0p_spv),
                 {kSampler, kSampler, kBuffer, kBuffer}, 20, d0p, 1) &&
        MakePass(&passes_[uint32_t(Stage::kD0)], zerofg_rc3_d0_spv, sizeof(zerofg_rc3_d0_spv),
                 {kSampler, kImage, kImage, kImage}, 32, Spec(0, 0), 2) &&
        MakePass(&passes_[uint32_t(Stage::kD0a)], zerofg_rc3_d0a_spv, sizeof(zerofg_rc3_d0a_spv),
                 {kSampler, kImage, kImage, kImage, kBuffer}, 52, with_photo(Spec(1, 0)), 1) &&
        MakePass(&passes_[uint32_t(Stage::kD0b)], zerofg_rc3_d0b_spv, sizeof(zerofg_rc3_d0b_spv),
                 {kSampler, kImage, kImage}, 20, Spec(1, 0), 1) &&
        MakePass(&passes_[uint32_t(Stage::kD1)], zerofg_rc3_d1_spv, sizeof(zerofg_rc3_d1_spv),
                 {kSampler, kSampler, kBuffer, kBuffer, kBuffer, kBuffer}, 24, with_photo(Spec(0, 0)), 1) &&
        MakePass(&passes_[uint32_t(Stage::kD2a)], d2_code, d2_bytes,
                 {kSampler, kSampler, kBuffer, kBuffer, kBuffer, kBuffer, kBuffer, kBuffer}, 32,
                 with_photo(Spec(0, 0, 2, kCtrD2aCells, kCtrD2aH1)), 1) &&
        MakePass(&passes_[uint32_t(Stage::kD2b)], d2_code, d2_bytes,
                 {kSampler, kSampler, kBuffer, kBuffer, kBuffer, kBuffer, kBuffer, kBuffer}, 32,
                 with_photo(Spec(0, 1, 1, kCtrD2bCells, kCtrD2bH1)), 1) &&
        MakePass(&passes_[uint32_t(Stage::kD4c)], zerofg_rc3_d4c_spv, sizeof(zerofg_rc3_d4c_spv),
                 {kSampler, kBuffer, kBuffer, kBuffer}, 16, with_agrid(Spec(1, 0), 1), 1) &&
        MakePass(&passes_[uint32_t(Stage::kD4cCost)], zerofg_rc3_d4cc_spv, sizeof(zerofg_rc3_d4cc_spv),
                 {kSampler, kBuffer}, 8, Spec(1, 0), 1) &&
        MakePass(&passes_[uint32_t(Stage::kD4cPick)], zerofg_rc3_d4cp_spv, sizeof(zerofg_rc3_d4cp_spv), {kBuffer}, 0,
                 Spec(1, 0), 1) &&
        MakePass(&passes_[uint32_t(Stage::kD4)], d4_code, d4_bytes,
                 {kSampler, kSampler, kBuffer, kBuffer, kBuffer, kBuffer, kBuffer, kBuffer, kBuffer, kBuffer,
                  kBuffer, kBuffer, kBuffer, kBuffer, kBuffer},
                 68, d4, uint32_t(kFineIterations)) &&
        MakePass(&passes_[uint32_t(Stage::kD3)], zerofg_rc3_d3_spv, sizeof(zerofg_rc3_d3_spv),
                 {kBuffer, kBuffer, kBuffer, kBuffer, kBuffer, kBuffer, kBuffer, kBuffer, kBuffer, kSampler}, 84, d3, 1) &&
        MakePass(&passes_[uint32_t(Stage::kD5)], d5_code, d5_bytes,
                 {kSampler, kSampler, kBuffer, kImage, kBuffer, kBuffer, kSampler, kSampler, kBuffer, kBuffer, kBuffer, kSampler}, 128, d5, 1) &&
        MakePass(&passes_[uint32_t(Stage::kD5b)], d5b_code, d5b_bytes,
                 {kSampler, kSampler, kBuffer, kImage, kBuffer, kBuffer, kSampler, kSampler, kBuffer, kBuffer, kBuffer, kSampler}, 128, d5, 1);
    return ok ? Status::kSuccess : Status::kVulkanError;
  }

  // ---- descriptors -------------------------------------------------------------------------------------------------------------------------
  Pass& P(Stage stage) { return passes_[uint32_t(stage)]; }

  void BindBuffer(Stage stage, uint32_t set, uint32_t binding, const OwnedBuffer& buffer) {
    VkDescriptorBufferInfo info{buffer.buffer(), 0, VK_WHOLE_SIZE};
    VkWriteDescriptorSet write{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
    write.dstSet = P(stage).sets[set];
    write.dstBinding = binding;
    write.descriptorCount = 1;
    write.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    write.pBufferInfo = &info;
    dispatch_.update_descriptor_sets(vulkan_.device, 1, &write, 0, nullptr);
  }

  void BindSampled(Stage stage, uint32_t set, uint32_t binding, VkImageView view, VkImageLayout layout,
                   VkSampler sampler) {
    VkDescriptorImageInfo info{sampler, view, layout};
    VkWriteDescriptorSet write{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
    write.dstSet = P(stage).sets[set];
    write.dstBinding = binding;
    write.descriptorCount = 1;
    write.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    write.pImageInfo = &info;
    dispatch_.update_descriptor_sets(vulkan_.device, 1, &write, 0, nullptr);
  }

  void BindStorageImage(Stage stage, uint32_t set, uint32_t binding, VkImageView view) {
    VkDescriptorImageInfo info{VK_NULL_HANDLE, view, VK_IMAGE_LAYOUT_GENERAL};
    VkWriteDescriptorSet write{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
    write.dstSet = P(stage).sets[set];
    write.dstBinding = binding;
    write.descriptorCount = 1;
    write.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    write.pImageInfo = &info;
    dispatch_.update_descriptor_sets(vulkan_.device, 1, &write, 0, nullptr);
  }

  // What never changes between frames: the pyramids, the luma images and the buffers.
  void BindStaticDescriptors() {
    const VkImageLayout read = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    for (uint32_t s = 0; s < 2; ++s) {
      for (uint32_t level = 0; level < 3; ++level) {
        BindStorageImage(Stage::kD0, s, 1 + level, pyramid_[s][level].view());
      }
    }
    BindBuffer(Stage::kD0p, 0, 2, photo_);
    BindBuffer(Stage::kD0p, 0, 3, counters_);
    BindStorageImage(Stage::kD0a, 0, 1, lattice_.view());
    BindStorageImage(Stage::kD0a, 0, 2, lattice_l1_.view());
    BindStorageImage(Stage::kD0a, 0, 3, camera_image_.view());
    BindBuffer(Stage::kD0a, 0, 4, photo_);
    BindStorageImage(Stage::kD0b, 0, 1, luma_b_.view());
    BindStorageImage(Stage::kD0b, 0, 2, phase_dummy_.view());  // the QCOM phase layout of the lab, never written here
    BindSampled(Stage::kD1, 0, 0, pyramid_[0][2].view(), read, sampler_);
    BindSampled(Stage::kD1, 0, 1, pyramid_[1][2].view(), read, sampler_);
    BindBuffer(Stage::kD1, 0, 2, seed_field_);
    BindBuffer(Stage::kD1, 0, 3, coarse_alt_);
    BindBuffer(Stage::kD1, 0, 4, counters_);
    BindBuffer(Stage::kD1, 0, 5, photo_);
    BindSampled(Stage::kD2a, 0, 0, pyramid_[0][1].view(), read, sampler_);
    BindSampled(Stage::kD2a, 0, 1, pyramid_[1][1].view(), read, sampler_);
    BindBuffer(Stage::kD2a, 0, 2, seed_field_);
    BindBuffer(Stage::kD2a, 0, 3, l1_field_);
    BindBuffer(Stage::kD2a, 0, 4, coarse_alt_);
    BindBuffer(Stage::kD2a, 0, 5, l1_alt_);
    BindBuffer(Stage::kD2a, 0, 6, counters_);
    BindBuffer(Stage::kD2a, 0, 7, photo_);
    BindSampled(Stage::kD2b, 0, 0, pyramid_[0][0].view(), read, sampler_);
    BindSampled(Stage::kD2b, 0, 1, pyramid_[1][0].view(), read, sampler_);
    BindBuffer(Stage::kD2b, 0, 2, l1_field_);
    BindBuffer(Stage::kD2b, 0, 3, raw_field_);
    BindBuffer(Stage::kD2b, 0, 4, l1_alt_);
    BindBuffer(Stage::kD2b, 0, 5, raw_alt_);
    BindBuffer(Stage::kD2b, 0, 6, counters_);
    BindBuffer(Stage::kD2b, 0, 7, photo_);
    BindSampled(Stage::kD4c, 0, 0, camera_image_.view(), read, sampler_);
    BindBuffer(Stage::kD4c, 0, 1, raw_field_);
    BindBuffer(Stage::kD4c, 0, 2, camera_buffer_);
    BindBuffer(Stage::kD4c, 0, 3, counters_);
    BindSampled(Stage::kD4cCost, 0, 0, luma_b_.view(), read, sampler_);
    BindBuffer(Stage::kD4cCost, 0, 1, camera_buffer_);
    BindBuffer(Stage::kD4cPick, 0, 0, camera_buffer_);
    for (uint32_t k = 0; k < uint32_t(kFineIterations); ++k) {
      BindSampled(Stage::kD4, k, 0, lattice_.view(), read, sampler_);
      BindSampled(Stage::kD4, k, 1, luma_b_.view(), read, sampler_);
      BindBuffer(Stage::kD4, k, 2, k == 0 ? raw_field_ : fine_[(k - 1) % 2]);
      BindBuffer(Stage::kD4, k, 3, fine_[k % 2]);
      BindBuffer(Stage::kD4, k, 4, k == 0 ? raw_alt_ : fine_alt_[(k - 1) % 2]);
      BindBuffer(Stage::kD4, k, 5, counters_);
      BindBuffer(Stage::kD4, k, 6, camera_buffer_);
      BindBuffer(Stage::kD4, k, 7, fine_alt_[k % 2]);
      BindBuffer(Stage::kD4, k, 8, k == 0 ? aux_zero_ : aux_[(k - 1) % 2]);
      BindBuffer(Stage::kD4, k, 9, aux_[k % 2]);
      BindBuffer(Stage::kD4, k, 10, changed_[k % 2]);
      BindBuffer(Stage::kD4, k, 11, active_list_);
      BindBuffer(Stage::kD4, k, 12, l1_field_);
      BindBuffer(Stage::kD4, k, 14, observ_);
    }
    const uint32_t last = uint32_t(fine_iterations_ - 1) % 2;  // D3 reads the last pass that runs
    BindBuffer(Stage::kD3, 0, 0, fine_[last]);
    BindBuffer(Stage::kD3, 0, 1, cell_field_);
    BindBuffer(Stage::kD3, 0, 2, fine_alt_[last]);
    BindBuffer(Stage::kD3, 0, 3, cell_alt_);
    BindBuffer(Stage::kD3, 0, 4, counters_);
    BindBuffer(Stage::kD3, 0, 5, aux_[last]);
    BindBuffer(Stage::kD3, 0, 7, observ_);
    BindBuffer(Stage::kD3, 0, 8, photo_);
    BindSampled(Stage::kD3, 0, 9, lattice_.view(), read, sampler_);
    BindBuffer(Stage::kD5, 0, 2, cell_field_);
    BindBuffer(Stage::kD5, 0, 4, cell_alt_);
    BindBuffer(Stage::kD5, 0, 5, counters_);
    BindBuffer(Stage::kD5, 0, 8, photo_);
    BindBuffer(Stage::kD5, 0, 9, split_list_);
    BindBuffer(Stage::kD5, 0, 10, split_args_);
    BindBuffer(Stage::kD5b, 0, 2, cell_field_);
    BindBuffer(Stage::kD5b, 0, 4, cell_alt_);
    BindBuffer(Stage::kD5b, 0, 5, counters_);
    BindBuffer(Stage::kD5b, 0, 8, photo_);
    BindBuffer(Stage::kD5b, 0, 9, split_list_);
    BindBuffer(Stage::kD5b, 0, 10, split_args_);
    BindSampled(Stage::kD5, 0, 11, cell_field_image_.view(), read, sampler_);
    BindSampled(Stage::kD5b, 0, 11, cell_field_image_.view(), read, sampler_);
  }

  // What changes with the call: the Reals, the output and the two history slots.
  void UpdateDynamicDescriptors(VkImageView view_a, VkImageLayout layout_a, VkImageView view_b,
                                VkImageLayout layout_b, const Image& output, bool write_direct, int read_slot,
                                int write_slot) {
    BindSampled(Stage::kD0, 0, 0, view_a, layout_a, sampler_);
    BindSampled(Stage::kD0, 1, 0, view_b, layout_b, sampler_);
    BindSampled(Stage::kD0a, 0, 0, view_a, layout_a, sampler_);
    BindSampled(Stage::kD0b, 0, 0, view_b, layout_b, sampler_);
    BindSampled(Stage::kD0p, 0, 0, view_a, layout_a, sampler_);
    BindSampled(Stage::kD0p, 0, 1, view_b, layout_b, sampler_);
    BindSampled(Stage::kD5, 0, 0, view_a, layout_a, sampler_);
    BindSampled(Stage::kD5, 0, 1, view_b, layout_b, sampler_);
    VkSampler cubic = cubic_sampler_ != VK_NULL_HANDLE ? cubic_sampler_ : sampler_;
    BindSampled(Stage::kD5, 0, 6, view_a, layout_a, cubic);
    BindSampled(Stage::kD5, 0, 7, view_b, layout_b, cubic);
    BindStorageImage(Stage::kD5, 0, 3, write_direct ? output.view : fallback_output_.view());
    BindSampled(Stage::kD5b, 0, 0, view_a, layout_a, sampler_);
    BindSampled(Stage::kD5b, 0, 1, view_b, layout_b, sampler_);
    BindSampled(Stage::kD5b, 0, 6, view_a, layout_a, cubic);
    BindSampled(Stage::kD5b, 0, 7, view_b, layout_b, cubic);
    BindStorageImage(Stage::kD5b, 0, 3, write_direct ? output.view : fallback_output_.view());
    for (uint32_t k = 0; k < uint32_t(kFineIterations); ++k) {
      BindBuffer(Stage::kD4, k, 13, shared_->history[read_slot]);
    }
    BindBuffer(Stage::kD3, 0, 6, shared_->history[write_slot]);
  }

  // ---- recording ---------------------------------------------------------------------------------------------------------------------------
  void MemoryBarrier(VkCommandBuffer cmd, VkPipelineStageFlags2 src_stage, VkAccessFlags2 src_access,
                     VkPipelineStageFlags2 dst_stage, VkAccessFlags2 dst_access) {
    VkMemoryBarrier2 barrier{VK_STRUCTURE_TYPE_MEMORY_BARRIER_2};
    barrier.srcStageMask = src_stage;
    barrier.srcAccessMask = src_access;
    barrier.dstStageMask = dst_stage;
    barrier.dstAccessMask = dst_access;
    VkDependencyInfo dependency{VK_STRUCTURE_TYPE_DEPENDENCY_INFO};
    dependency.memoryBarrierCount = 1;
    dependency.pMemoryBarriers = &barrier;
    dispatch_.cmd_pipeline_barrier_2(cmd, &dependency);
  }

  // Between two compute passes. The SOURCE is the broad shader write, not the storage-only bit: one driver (AMD, Windows) did not order the storage
  // writes of a chain of dependent passes on the latter (lab, 2026-10-05).
  void ComputeBarrier(VkCommandBuffer cmd) {
    MemoryBarrier(cmd, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_WRITE_BIT,
                  VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_READ_BIT | VK_ACCESS_2_SHADER_WRITE_BIT);
  }

  void ImageBarrier(VkCommandBuffer cmd, VkImage image, VkImageLayout old_layout, VkImageLayout new_layout,
                    VkPipelineStageFlags2 src_stage, VkAccessFlags2 src_access, VkPipelineStageFlags2 dst_stage,
                    VkAccessFlags2 dst_access) {
    VkImageMemoryBarrier2 barrier{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER_2};
    barrier.srcStageMask = src_stage;
    barrier.srcAccessMask = src_access;
    barrier.dstStageMask = dst_stage;
    barrier.dstAccessMask = dst_access;
    barrier.oldLayout = old_layout;
    barrier.newLayout = new_layout;
    barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.image = image;
    barrier.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    VkDependencyInfo dependency{VK_STRUCTURE_TYPE_DEPENDENCY_INFO};
    dependency.imageMemoryBarrierCount = 1;
    dependency.pImageMemoryBarriers = &barrier;
    dispatch_.cmd_pipeline_barrier_2(cmd, &dependency);
  }

  void Dispatch(VkCommandBuffer cmd, Stage stage, uint32_t set, const void* push, uint32_t push_bytes, uint32_t gx,
                uint32_t gy) {
    Pass& pass = P(stage);
    dispatch_.cmd_bind_pipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pass.pipeline);
    dispatch_.cmd_bind_descriptor_sets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pass.layout, 0, 1, &pass.sets[set], 0,
                                       nullptr);
    if (push_bytes) {
      dispatch_.cmd_push_constants(cmd, pass.layout, VK_SHADER_STAGE_COMPUTE_BIT, 0, push_bytes, push);
    }
    dispatch_.cmd_dispatch(cmd, gx, gy, 1);
  }

  // The tight copies of A and B, allocated the first time a physical image larger than the picture arrives or a padded picture runs (never when the
  // host hands exact images of a tiled picture).
  Status EnsureCropImages() {
    if (tight_[0].image() != VK_NULL_HANDLE) {
      return Status::kSuccess;
    }
    for (OwnedImage& image : tight_) {
      const Status status = image.Create(vulkan_, dispatch_, width_, height_, VK_FORMAT_A2B10G10R10_UNORM_PACK32,
                                         VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT);
      if (status != Status::kSuccess) {
        return status;
      }
    }
    return Status::kSuccess;
  }

  void UpdateCropDescriptors(const Image& previous, const Image& current) {
    BindSampled(Stage::kCrop, 0, 0, previous.view, previous.layout, sampler_);
    BindStorageImage(Stage::kCrop, 0, 1, tight_[0].view());
    BindSampled(Stage::kCrop, 1, 0, current.view, current.layout, sampler_);
    BindStorageImage(Stage::kCrop, 1, 1, tight_[1].view());
  }

  void RecordCrop(VkCommandBuffer cmd, const ActiveRect& rect) {
    const VkPipelineStageFlags2 kCompute = VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT;
    struct CropPush {
      int32_t origin[2], extent[2], picture[2];
    } push{{int32_t(rect.x), int32_t(rect.y)},
           {int32_t(width_), int32_t(height_)},
           {int32_t(picture_w_), int32_t(picture_h_)}};
    static_assert(sizeof(CropPush) == 24);
    for (uint32_t s = 0; s < 2; ++s) {
      ImageBarrier(cmd, tight_[s].image(), VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL,
                   VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT, 0, kCompute, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
      Dispatch(cmd, Stage::kCrop, s, &push, sizeof(push), Groups(width_, 8), Groups(height_, 8));
      ImageBarrier(cmd, tight_[s].image(), VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, kCompute,
                   VK_ACCESS_2_SHADER_WRITE_BIT, kCompute, VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);
    }
  }

  // resolve_only: the pair is the one the last record analysed (another phase of it): only D5 runs, on the cells and counters already there.
  void RecordFrame(VkCommandBuffer cmd, const Image& output, bool write_direct, float phase, bool resolve_only) {
    const VkPipelineStageFlags2 kCompute = VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT;
    const VkImageLayout kRead = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    if (!resolve_only) {

    // The images the frontend writes this record: the six pyramid levels first, then the luma images (the last one is only bound, never written:
    // it gets the same transitions so that its descriptor is in the layout it declares).
    std::array<VkImage, 11> written = {
        pyramid_[0][0].image(), pyramid_[0][1].image(), pyramid_[0][2].image(), pyramid_[1][0].image(),
        pyramid_[1][1].image(), pyramid_[1][2].image(), luma_b_.image(), lattice_.image(),
        lattice_l1_.image(), camera_image_.image(), phase_dummy_.image()};
    const size_t written_count = written.size();
    for (size_t i = 0; i < written_count; ++i) {
      ImageBarrier(cmd, written[i], VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL,
                   VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT, 0, kCompute, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
    }
    auto read_barrier = [&](size_t first, size_t last) {
      for (size_t i = first; i < last && i < written_count; ++i) {
        ImageBarrier(cmd, written[i], VK_IMAGE_LAYOUT_GENERAL, kRead, kCompute, VK_ACCESS_2_SHADER_WRITE_BIT, kCompute,
                     VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);
      }
    };

    // D0p: the exposure model, before anything reads A (one workgroup); D0: the pyramids.
    struct PhotoPush {
      int32_t extent[2];
      float conf_min, dead_zone, corr_min;
    } photo_push{{int32_t(width_), int32_t(height_)}, kPhotoConfidence, kPhotoDeadZone, kPhotoCorrelation};
    static_assert(sizeof(PhotoPush) == 20);
    Dispatch(cmd, Stage::kD0p, 0, &photo_push, sizeof(photo_push), 1, 1);
    struct FrontendPush {
      int32_t source_extent[2], active_origin[2], active_extent[2], l0_extent[2];
    } d0_push{{int32_t(width_), int32_t(height_)},
              {0, 0},
              {int32_t(width_), int32_t(height_)},
              {int32_t(l0_w_), int32_t(l0_h_)}};
    static_assert(sizeof(FrontendPush) == 32);
    for (uint32_t s = 0; s < 2; ++s) {
      Dispatch(cmd, Stage::kD0, s, &d0_push, sizeof(d0_push), Groups(l0_w_, 8), Groups(l0_h_, 8));
    }
    read_barrier(0, 6);
    ComputeBarrier(cmd);  // the estimate (a buffer) before D1 and the later readers

    struct EvidencePush {
      int32_t extent[2];
      float source_pixels_per_texel[2];
      float ridge, pad;
    } d1_push{{int32_t(l2_w_), int32_t(l2_h_)},
              {float(width_) / float(l2_w_), float(height_) / float(l2_h_)},
              kRidge,
              0.0f};
    static_assert(sizeof(EvidencePush) == 24);
    Dispatch(cmd, Stage::kD1, 0, &d1_push, sizeof(d1_push), l2_w_, l2_h_);
    ComputeBarrier(cmd);

    struct RefinePush {
      int32_t fine_extent[2], coarse_extent[2];
      float source_pixels_per_fine_texel[2];
      float ridge, delta_cost;
    };
    static_assert(sizeof(RefinePush) == 32);
    const RefinePush d2a_push{{int32_t(l1_w_), int32_t(l1_h_)},
                              {int32_t(l2_w_), int32_t(l2_h_)},
                              {float(width_) / float(l1_w_), float(height_) / float(l1_h_)},
                              kRidge,
                              kDeltaCost};
    Dispatch(cmd, Stage::kD2a, 0, &d2a_push, sizeof(d2a_push), Groups(l1_w_, 8), Groups(l1_h_, 8));
    ComputeBarrier(cmd);
    const RefinePush d2b_push{{int32_t(l0_w_), int32_t(l0_h_)},
                              {int32_t(l1_w_), int32_t(l1_h_)},
                              {float(width_) / float(l0_w_), float(height_) / float(l0_h_)},
                              kRidge,
                              kDeltaCost};
    Dispatch(cmd, Stage::kD2b, 0, &d2b_push, sizeof(d2b_push), Groups(l0_w_, 8), Groups(l0_h_, 8));

    // The luma planes do not depend on D1 and D2, whose few workgroups leave the GPU almost idle: they are recorded next to D2b, with no barrier
    // between (the lab's ovl=1).
    struct LatticePush {
      int32_t extent[2], block_extent[2], cell[2], block_extent_l1[2], cell_l1[2], stride, stride_l1, samples;
    };
    static_assert(sizeof(LatticePush) == 52);
    const int32_t cell_w = int32_t(width_ / l0_w_), cell_h = int32_t(height_ / l0_h_);
    const LatticePush d0a_push{{int32_t(width_), int32_t(height_)},
                               {int32_t(l0_w_) * kSamples, int32_t(l0_h_) * kSamples},
                               {cell_w, cell_h},
                               {0, 0},
                               {0, 0},
                               (cell_w + kSamples - 1) / kSamples,
                               0,
                               kSamples};
    Dispatch(cmd, Stage::kD0a, 0, &d0a_push, sizeof(d0a_push), Groups(std::max(uint32_t(kSamples) * l0_w_, kCameraSamples), 8),
             Groups(uint32_t(kSamples) * l0_h_, 8));
    struct LumaPush {
      int32_t extent[2];
      int32_t phase_tile[2];
      int32_t stride;
    } d0b_push{{int32_t(width_), int32_t(height_)}, {0, 0}, 0};
    static_assert(sizeof(LumaPush) == 20);
    Dispatch(cmd, Stage::kD0b, 0, &d0b_push, sizeof(d0b_push), Groups(width_, 8), Groups(height_, 8));
    read_barrier(6, written_count);
    ComputeBarrier(cmd);

    // D4c: the global translation hypotheses (the histogram and its peaks, the window search around each, the best offset of each).
    struct CameraPush {
      int32_t field_extent[2], source_extent[2];
    } d4c_push{{int32_t(l0_w_), int32_t(l0_h_)}, {int32_t(width_), int32_t(height_)}};
    static_assert(sizeof(CameraPush) == 16);
    const int32_t cost_push[2] = {int32_t(width_), int32_t(height_)};
    Dispatch(cmd, Stage::kD4c, 0, &d4c_push, sizeof(d4c_push), 1, 1);
    ComputeBarrier(cmd);
    Dispatch(cmd, Stage::kD4cCost, 0, cost_push, sizeof(cost_push), 17 * 17, 2);
    ComputeBarrier(cmd);
    Dispatch(cmd, Stage::kD4cPick, 0, nullptr, 0, 1, 1);
    ComputeBarrier(cmd);

    // D4: the fine verification against the full-resolution luma, four propagation passes (the first one reads the temporal prior).
    struct FinePush {
      int32_t field_extent[2], source_extent[2], cell_size[2];
      float h1_gain, h1_share;
      float prior_conf, prior_root, prior_decay;
      int32_t step, pass, l1_w, l1_h;
      int32_t phase_tile_x, phase_tile_y;
    } d4_push{{int32_t(l0_w_), int32_t(l0_h_)},
              {int32_t(width_), int32_t(height_)},
              {cell_w, cell_h},
              kFineGain,
              kFineShare,
              kPriorConfidence,
              kPriorRoot,
              kPriorDecay,
              1,
              0,
              int32_t(l1_w_),
              int32_t(l1_h_),
              0,
              0};
    static_assert(sizeof(FinePush) == 68);
    // The Mali form of D4 takes four cells per workgroup (one 16-wide subgroup each).
    const int d4_cells_per_group = mali_ ? 4 : 0;
    for (int32_t k = 0; k < fine_iterations_; ++k) {
      d4_push.step = 1;
      d4_push.pass = k;
      if (d4_cells_per_group > 1) {
        Dispatch(cmd, Stage::kD4, uint32_t(k), &d4_push, sizeof(d4_push), Groups(l0_w_ * l0_h_, uint32_t(d4_cells_per_group)), 1);
      } else {
        Dispatch(cmd, Stage::kD4, uint32_t(k), &d4_push, sizeof(d4_push), l0_w_, l0_h_);
      }
      ComputeBarrier(cmd);
    }

    // D3: the cell analysis, the guard's counters and the history of this pair.
    struct CellsPush {
      int32_t extent[2];
      float source_pixels_per_texel[2];
      float ridge_full, conf_min, quality_low, quality_high, guard_cost, support_trust;
      int32_t commit;
      float obs_weight, obs_scale;
      float cfill_lo, cfill_hi, cfill_cost, cfill_d, cfill_conf, cfill_corr;
      int32_t cfill_n;
      float guard_unique;
    } d3_push{{int32_t(l0_w_), int32_t(l0_h_)},
              {float(width_) / float(l0_w_), float(height_) / float(l0_h_)},
              kRidgeFull,
              kConfMin,
              kQualityLow,
              kQualityHigh,
              kGuardCost,
              kSupportTrust,
              1,
              0.0f,
              80.0f,
              0.25f,
              0.6f,
              0.10f,
              3.0f,
              0.5f,
              0.5f,
              2,
              kGuardUnique};
    static_assert(sizeof(CellsPush) == 84);
    Dispatch(cmd, Stage::kD3, 0, &d3_push, sizeof(d3_push), Groups(l0_w_, 8), Groups(l0_h_, 8));
    ComputeBarrier(cmd);
    if (split_) {
      // The cell field as an image for the resolve (the whole image is written: its previous contents are discarded).
      MemoryBarrier(cmd, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_WRITE_BIT, VK_PIPELINE_STAGE_2_COPY_BIT,
                    VK_ACCESS_2_TRANSFER_READ_BIT);
      ImageBarrier(cmd, cell_field_image_.image(), VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                   VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, 0, VK_PIPELINE_STAGE_2_COPY_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT);
      VkBufferImageCopy region{};
      region.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
      region.imageExtent = {l0_w_, l0_h_, 1};
      vkCmdCopyBufferToImage(cmd, cell_field_.buffer(), cell_field_image_.image(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1,
                             &region);
      ImageBarrier(cmd, cell_field_image_.image(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                   VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_PIPELINE_STAGE_2_COPY_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT,
                   VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);
    }
    }  // !resolve_only

    // D5: the resolve, into the output (or the internal image that is blitted to it).
    VkImage target = write_direct ? output.image : fallback_output_.image();
    if (write_direct) {
      ImageBarrier(cmd, target, output.layout, VK_IMAGE_LAYOUT_GENERAL, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,
                   VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT, kCompute,
                   VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
    } else {
      ImageBarrier(cmd, target, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL,
                   VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT, 0, kCompute, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
    }
    struct ResolvePush {
      int32_t source_extent[2], active_origin[2], active_extent[2], output_extent[2], field_extent[2];
      float phase, margin, error_cap;
      int32_t cand, trust_mode;
      float guard;
      int32_t early;
      float fb_low, fb_high, st_eps, st_delta, st_grad;
      float ly_mag, ly_cos, ly_away, ly_off;
      float flat_lo, flat_hi;
      float amb_floor;
      int32_t st_n;
      float guard_corr;
      float guard_flat;
    } d5_push{{int32_t(width_), int32_t(height_)},
              {0, 0},
              {int32_t(width_), int32_t(height_)},
              {int32_t(width_), int32_t(height_)},
              {int32_t(l0_w_), int32_t(l0_h_)},
              phase,
              kMargin,
              kErrorCap,
              kCandidates,
              kTrustMode,
              kGuard,
              1,
              kFallbackLow,
              kFallbackHigh,
              kStaticEps,
              kStaticDelta,
              kStaticGrad,
              kLayerMagnitude,
              kLayerCosine,
              kLayerAway,
              kLayerOffset,
              kFlatLow,
              kFlatHigh,
              kAmbiguityFloor,
              kStaticN,
              kGuardCorrelation,
              kGuardFlat};
    static_assert(sizeof(ResolvePush) == 128);
    if (split_) {
      // The list starts empty and the second pass's dispatch at (0, 1, 1).
      const uint32_t args[4] = {0u, 0u, 1u, 1u};
      vkCmdUpdateBuffer(cmd, split_args_.buffer(), 0, sizeof(args), args);
      VkMemoryBarrier reset{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
      reset.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
      reset.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
      vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &reset, 0,
                           nullptr, 0, nullptr);
    }
    Dispatch(cmd, Stage::kD5, 0, &d5_push, sizeof(d5_push), Groups(width_, 8), Groups(height_, 8));
    if (split_) {
      VkMemoryBarrier listed{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
      listed.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
      listed.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_INDIRECT_COMMAND_READ_BIT;
      vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                           VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT, 0, 1, &listed, 0,
                           nullptr, 0, nullptr);
      Pass& second = P(Stage::kD5b);
      dispatch_.cmd_bind_pipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, second.pipeline);
      dispatch_.cmd_bind_descriptor_sets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, second.layout, 0, 1, &second.sets[0], 0,
                                         nullptr);
      dispatch_.cmd_push_constants(cmd, second.layout, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(d5_push), &d5_push);
      vkCmdDispatchIndirect(cmd, split_args_.buffer(), 4);
    }

    if (write_direct) {
      ImageBarrier(cmd, output.image, VK_IMAGE_LAYOUT_GENERAL, output.layout, kCompute, VK_ACCESS_2_SHADER_WRITE_BIT,
                   VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT);
    } else {
      ImageBarrier(cmd, fallback_output_.image(), VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                   kCompute, VK_ACCESS_2_SHADER_WRITE_BIT, VK_PIPELINE_STAGE_2_BLIT_BIT,
                   VK_ACCESS_2_TRANSFER_READ_BIT);
      ImageBarrier(cmd, output.image, output.layout, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                   VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT,
                   VK_PIPELINE_STAGE_2_BLIT_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT);
      VkImageBlit blit{};
      blit.srcSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
      blit.srcSubresource.layerCount = 1;
      // Only the picture leaves: a padded run's pad stays in the internal image.
      blit.srcOffsets[1] = {int32_t(picture_w_), int32_t(picture_h_), 1};
      blit.dstSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
      blit.dstSubresource.layerCount = 1;
      blit.dstOffsets[1] = {int32_t(picture_w_), int32_t(picture_h_), 1};
      dispatch_.cmd_blit_image(cmd, fallback_output_.image(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, output.image,
                               VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &blit, VK_FILTER_NEAREST);
      ImageBarrier(cmd, output.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, output.layout,
                   VK_PIPELINE_STAGE_2_BLIT_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,
                   VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT);
    }
  }

  // ---- teardown ----------------------------------------------------------------------------------------------------------------------------
  void DestroyPass(Pass* pass) {
    if (pass->pool != VK_NULL_HANDLE) dispatch_.destroy_descriptor_pool(vulkan_.device, pass->pool, vulkan_.allocator);
    if (pass->pipeline != VK_NULL_HANDLE) dispatch_.destroy_pipeline(vulkan_.device, pass->pipeline, vulkan_.allocator);
    if (pass->layout != VK_NULL_HANDLE) {
      dispatch_.destroy_pipeline_layout(vulkan_.device, pass->layout, vulkan_.allocator);
    }
    if (pass->set_layout != VK_NULL_HANDLE) {
      dispatch_.destroy_descriptor_set_layout(vulkan_.device, pass->set_layout, vulkan_.allocator);
    }
    *pass = {};
  }

  void DestroyWorking() {
    initialized_ = false;
    if (vulkan_.device == VK_NULL_HANDLE || dispatch_.get_device_proc_addr == nullptr) {
      return;
    }
    for (Pass& pass : passes_) DestroyPass(&pass);
    for (auto& per_source : pyramid_) {
      for (OwnedImage& image : per_source) image.Destroy(vulkan_, dispatch_);
    }
    for (OwnedImage* image : {&luma_b_, &lattice_, &lattice_l1_, &camera_image_, &phase_dummy_, &fallback_output_,
                              &tight_[0], &tight_[1]}) {
      image->Destroy(vulkan_, dispatch_);
    }
    cell_field_image_.Destroy(vulkan_, dispatch_);
    for (OwnedBuffer* buffer : AllBuffers()) buffer->Destroy(vulkan_, dispatch_);
    split_list_.Destroy(vulkan_, dispatch_);
    split_args_.Destroy(vulkan_, dispatch_);
    counters_.Destroy(vulkan_, dispatch_);
    photo_.Destroy(vulkan_, dispatch_);
    if (sampler_ != VK_NULL_HANDLE) dispatch_.destroy_sampler(vulkan_.device, sampler_, vulkan_.allocator);
    if (cubic_sampler_ != VK_NULL_HANDLE) dispatch_.destroy_sampler(vulkan_.device, cubic_sampler_, vulkan_.allocator);
    sampler_ = cubic_sampler_ = VK_NULL_HANDLE;
  }

  void DestroyAll() { DestroyWorking(); }

  VulkanContext vulkan_;
  VulkanDispatch dispatch_;
  Capabilities capabilities_;
  Backend backend_;
  int32_t fine_iterations_;  // D4 propagation passes that run: 4 (RC3) or 2 (RC3 Lite)
  std::shared_ptr<Rc3Shared> shared_;

  bool initialized_ = false;
  bool first_record_ = true;
  bool use_subgroup_ = false;
  bool half_resolve_ = false;
  bool direct_output_ = false;
  int precision_ = 32;
  int sharp_ = 1;
  uint32_t width_ = 0, height_ = 0;            // the extent the engine runs at (the picture, or the padded extent the cells tile)
  uint32_t picture_w_ = 0, picture_h_ = 0;     // the picture Resize was called with: what the host hands and receives
  uint32_t l0_w_ = 0, l0_h_ = 0, l1_w_ = 0, l1_h_ = 0, l2_w_ = 0, l2_h_ = 0;
  VkFormat input_format_ = VK_FORMAT_UNDEFINED;
  VkFormat output_format_ = VK_FORMAT_UNDEFINED;

  std::array<Pass, size_t(Stage::kCount)> passes_{};
  OwnedImage pyramid_[2][3];
  OwnedImage luma_b_, lattice_, lattice_l1_, camera_image_, phase_dummy_, fallback_output_;
  OwnedImage tight_[2];  // cropped copies of A and B (allocated on first use)
  OwnedBuffer seed_field_, l1_field_, raw_field_, cell_field_, observ_, aux_zero_, active_list_, coarse_alt_, l1_alt_,
      raw_alt_, cell_alt_, camera_buffer_, counters_, photo_;
  OwnedBuffer fine_[2], fine_alt_[2], aux_[2], changed_[2];
  VkSampler sampler_ = VK_NULL_HANDLE;
  VkSampler cubic_sampler_ = VK_NULL_HANDLE;
  PFN_vkCmdUpdateBuffer cmd_update_buffer_ = nullptr;
  // The split resolve (Mali form): the pixels of the second pass and its indirect dispatch.
  OwnedBuffer split_list_, split_args_;
  OwnedImage cell_field_image_;  // the cell field as an image, for the resolve
  bool split_ = false;
  bool mali_ = false;
  // The pair of the last full record (resolve-only records of other phases reuse its analysis).
  uint64_t last_previous_sequence_ = 0, last_current_sequence_ = 0;
  VkImage last_previous_image_ = VK_NULL_HANDLE, last_current_image_ = VK_NULL_HANDLE, last_output_image_ = VK_NULL_HANDLE;
};

}  // namespace

std::shared_ptr<Rc3Shared> CreateRc3Shared(const CreateInfo& create_info) {
  auto shared = std::make_shared<Rc3Shared>();
  shared->vulkan = create_info.vulkan;
  if (!shared->dispatch.Load(create_info.vulkan.instance, create_info.vulkan.device,
                             create_info.vulkan.get_instance_proc_addr)) {
    return nullptr;
  }
  return shared;
}

std::unique_ptr<AlgorithmContext> CreateRc3Context(const CreateInfo& create_info,
                                                   std::shared_ptr<Rc3Shared> shared, Status* status) {
  auto context = std::make_unique<Rc3Context>(create_info, std::move(shared));
  if (!context->Initialize()) {
    if (status) *status = Status::kUnsupported;
    return nullptr;
  }
  if (status) *status = Status::kSuccess;
  return context;
}

}  // namespace zerofg
