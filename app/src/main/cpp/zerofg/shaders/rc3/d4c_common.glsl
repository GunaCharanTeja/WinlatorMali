// Shared by the three D4c stages (the global translation candidates): d4c_camera.comp (histogram and
// peaks), d4c_cost.comp (the window search, one workgroup per candidate offset) and d4c_pick.comp (the
// best offset and its sub-pixel part). Include rc3_common.glsl first and define CAM_WORK_BINDING.
//
// The work buffer (the "camera buffer" of the engine; D4 reads camera[0] and camera[1]):
//   camera[0], camera[1]   the two hypotheses: x, y (source pixels), cost, valid
//   camera[2], camera[3]   the two histogram peaks: centre x, y (whole pixels), votes, valid
//   a_luma                 the A luma at the 256 sample points (8 bit, 0..255)
//   window_cost            per peak, the cost of each of the 17 x 17 whole-pixel offsets around its
//                          centre: the sum over the 256 samples of |A - B(offset)| in 1/255 units
//                          (an integer, so exact whatever the lane split), a sample outside the image 255
layout(std430, binding = CAM_WORK_BINDING) buffer Work {
  vec4 camera[4];
  int a_luma[256];
  int window_cost[578];
};

const int kCamBins = 80;           // 8 px bins over +-320 px, the RC1 search range
const float kCamBinPx = 8.0;
const int kCamRadius = 8;
const int kCamSide = 2 * kCamRadius + 1;
const int kCamWindow = kCamSide * kCamSide;
const int kCamOutside = 255;
const int kCamNone = 0x7fffffff;

// The sample points (CameraSamplePoint, kCamSamples) are in rc3_common.glsl: D0a writes the A luma at them.

int CameraLuma(sampler2D plane, ivec2 p) {
  return int(texelFetch(plane, p, 0).r * 255.0 + 0.5);
}
