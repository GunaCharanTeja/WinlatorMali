#pragma once

#include <memory>

#include "zerofg/zerofg.h"

namespace zerofg {

struct Rc3Shared;

class AlgorithmContext {
 public:
  virtual ~AlgorithmContext() = default;

  virtual Status Resize(uint32_t width, uint32_t height,
                        VkFormat input_format,
                        VkFormat output_format) = 0;

  virtual Status Interpolate(VkCommandBuffer command_buffer,
                             const Image& previous,
                             const Image& current, float phase,
                             const Image& output) = 0;
};

// The frame contexts of one Interpolator share the temporal history.
std::unique_ptr<AlgorithmContext> CreateRc3Context(
    const CreateInfo& create_info, std::shared_ptr<Rc3Shared> shared,
    Status* status);
std::shared_ptr<Rc3Shared> CreateRc3Shared(const CreateInfo& create_info);

}  // namespace zerofg
