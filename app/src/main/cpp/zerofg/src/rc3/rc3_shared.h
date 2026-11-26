#pragma once

#include <cstdint>

#include "vulkan_buffer.h"
#include "vulkan_dispatch.h"
#include "zerofg/zerofg.h"

namespace zerofg {

// What the frame contexts of one RC3 Interpolator share: the temporal history, the previous pair's final vector, support record and root per cell
// (three words, written by D3, read by D4 pass 0). Two slots keyed by the sequence of the Real the pair ended on: a pair that starts at the Real
// a slot is keyed to reads that slot and writes the other one, so replaying a pair reads the same history again and a gap or an unknown sequence
// finds none (the pair then starts from a zeroed slot). The slot also records the span of the pair that wrote it (current.sequence minus
// previous.sequence): the prior is motion per pair, so it is used only when the pair it is read by spans the same number of Source frames (an
// IssueSwap gap inside one pair makes that pair's motion a multiple of the previous one). The host records pairs in their execution order; every
// record starts with a barrier that covers the earlier submissions of the queue, which is what orders the history between frame contexts.
struct Rc3Shared {
  VulkanContext vulkan;
  VulkanDispatch dispatch;
  OwnedBuffer history[2];
  uint64_t history_sequence[2] = {0, 0};
  uint64_t history_span[2] = {0, 0};
  uint32_t width = 0;
  uint32_t height = 0;

  ~Rc3Shared() {
    for (OwnedBuffer& buffer : history) {
      buffer.Destroy(vulkan, dispatch);
    }
  }
};

}  // namespace zerofg
