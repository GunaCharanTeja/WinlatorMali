#pragma once

#include <android/hardware_buffer.h>

// Native fence fd signalled when the last GLX/Gladio copy into this buffer finished on the GPU,
// or -1 if none is pending. The caller takes ownership of the fd.
int takeReadbackFence(AHardwareBuffer *ahb);
