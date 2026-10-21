// Shared declarations of the ZeroFG engine shaders.
//
// Rules every RC3 shader follows (spec section 0):
//   * Vulkan 1.3, no fp64, no int64, no float atomic add, no subgroup rotate;
//   * push constants at most 128 bytes, shared memory at most 32 KB, at most 1024 invocations;
//   * wave-size agnostic: never assume 32, 64 or 128 lanes;
//   * sampling coordinates and reductions in FP32; the precision form is a specialization constant.
//
// Specialization constants (the same ids in every stage, so the host sets them in one place):
//   0 ZFG_MODE     stage mode: 0 pass-through (the RC1 behaviour), 1 simple, 2 full
//   1 ZFG_PREC     32 = Compat32 oracle, 16 = Modern mixed precision
//   2 ZFG_VARIANT  stage specific (documented in the stage)
//   3 ZFG_DEBUG    1 writes the stage's debug plane (diagnostic variants only)
//   8 ZFG_COUNTERS 1 counts diagnostics with global atomics, 0 compiles them out (the timing form)
//   4.. stage specific, documented in the stage
// A shader that does not declare an id ignores it.
// (A shader includes this file after its own `#extension GL_GOOGLE_include_directive : require`.)

// Precision policy (owner decision 2026-10-05: lower precision first, 32 bits only where it is needed). `half`, `hvec2`,
// `hvec3` and `hvec4` are the types of a value that may be held in half precision: colours, photometric errors and
// trust. The build with ZFG_HALF=1 makes them float16 (it needs `shaderFloat16` enabled on the device); without it they are
// float, the 32-bit form that is the fallback and the oracle, from the same source. Coordinates, positions, motion vectors
// and every accumulation that must be exact stay at full precision.
#ifdef ZFG_HALF
#extension GL_EXT_shader_explicit_arithmetic_types_float16 : require
#define half float16_t
#define hvec2 f16vec2
#define hvec3 f16vec3
#define hvec4 f16vec4
#else
#define half float
#define hvec2 vec2
#define hvec3 vec3
#define hvec4 vec4
#endif

// The Rec.709 luma of a source texel, as it is stored in the 8-bit luma planes. D0b (the full plane of B) and D0a (the
// lattice of A) both write this expression, so the 8-bit values agree word for word whichever pass produced them.
float SourceLuma(sampler2D source_image, ivec2 p) {
  hvec3 rgb = hvec3(texelFetch(source_image, p, 0).rgb);
  return float(dot(rgb, hvec3(half(0.2126), half(0.7152), half(0.0722))));
}

// The camera (global translation) sample points: a 16 x 16 grid over the image, jittered deterministically so that a
// periodic texture does not alias with the grid. Read by D4c (and written for A by D0a).
const int kCamSamples = 256;

ivec2 CameraSamplePoint(int i, ivec2 source_extent) {
  int gx = i % 16, gy = i / 16;
  int jx = (gy * 37 + gx * 11) % 32 - 16;
  int jy = (gx * 29 + gy * 13) % 32 - 16;
  return ivec2(clamp((gx * source_extent.x) / 16 + source_extent.x / 32 + jx, 0, source_extent.x - 1),
               clamp((gy * source_extent.y) / 16 + source_extent.y / 32 + jy, 0, source_extent.y - 1));
}

// Diagnostic counters (one uint buffer shared by every stage; the host clears it per Record and reads
// it back after the submit; src/rc3/rc3_engine.h lists the same indices with their names).
const uint kCtrD1Cells = 0u;       // C cells processed
const uint kCtrD1Mismatch = 1u;    // K extraction first pick differs from the tree best (must be 0)
const uint kCtrD1Alt1 = 2u;        // C cells with a valid second distinct basin
const uint kCtrD1Alt2 = 3u;        // C cells with a valid third distinct basin
const uint kCtrD2aCells = 4u;      // L1 cells
const uint kCtrD2aH1 = 5u;         // L1 cells with a valid H1
const uint kCtrD2bCells = 6u;      // M cells
const uint kCtrD2bH1 = 7u;         // M cells with a valid H1
const uint kCtrD2bH1Cost = 8u;     // M cells with a valid H1 whose cost is within 0.02 of H0's
const uint kCtrD3Valid = 9u;       // cells whose H1 is valid after the D3 gate
const uint kCtrD3Strong = 10u;     // ... with confidence above 0.5
const uint kCtrD5Alt = 11u;        // pixels whose output uses an H1 candidate
const uint kCtrD5AltCells = 12u;   // pixels with at least one H1 candidate available
const uint kCtrD5Parent = 13u;     // pixels whose output uses a neighbouring cell's H0 (the VMC parents)
const uint kCtrD5Zero = 14u;       // pixels whose output uses the zero vector
const uint kCtrD5Kept = 15u;       // pixels that kept the smooth RC1 candidate although alternatives existed
const uint kCtrD4Cells = 16u;      // cells the fine verification processed (all iterations)
const uint kCtrD4Changed = 17u;    // ... whose vector moved by more than 4 px
const uint kCtrD4Zero = 18u;       // ... whose winning candidate was the zero vector
const uint kCtrD4Camera = 19u;     // ... the camera hint
const uint kCtrD4Neighbour = 20u;  // ... a neighbour's vector (or a neighbour's H1)
const uint kCtrD4Good = 21u;       // ... with a full-resolution cost under 0.03
const uint kCtrCamVotes0 = 22u;   // votes of the strongest global translation peak (3 x 3 bins)
const uint kCtrCamVotes1 = 23u;   // ... and of the second one
const uint kCtrD4H1 = 24u;         // cells with a fine H1 (mixture gain and share above their gates)
const uint kCtrD4H1Strong = 25u;   // ... with a gain above 0.05
const uint kCtrD3Good = 26u;       // cells with a cost under the guard cost and a unique minimum (the pair guard)
const uint kCtrPhotoGain = 27u;        // the photometric estimate (d0p_photo.comp): the gain of A onto B, as float bits (1.0 = no exposure change)
const uint kCtrPhotoConfidence = 28u;  // ... the share of the valid samples inside its peak, per mille
const uint kCtrPhotoShare = 29u;       // ... the share of the 2048 samples that were valid (A not black), per mille
const uint kCtrPhotoBias = 30u;        // ... the bias of A onto B, per mille, as a signed int
const uint kCtrPhotoModel = 31u;       // ... 0 no exposure change, 1 a gain (F1), 2 a gain and a bias (F2)
const uint kCtrPhotoCorrelation = 32u; // ... the Pearson correlation of the co-located A and B samples, per mille as a signed int (a scene cut has none)
const uint kCtrD3Filled = 33u;         // cells whose vector the D3 consensus fill replaced (cfill)
const uint kCtrPhotoChromaR = 40u;     // the per-channel gains of the exposure model relative to the luma gain (d0p_photo.comp), x 1000: red, green, blue
const uint kCtrPhotoChromaG = 41u;
const uint kCtrPhotoChromaB = 42u;
const uint kCtrD5LayerApplied = 43u;   // output pixels whose persistent-layer mix has non-zero weight
const uint kCtrGuardFired = 44u;       // 1 when the scene-cut guard of D5 held B for this pair (counted once, by the first invocation)
const uint kCtrPhotoSigmaA = 45u;      // the spread of A's valid samples' luma (d0p_photo.comp), x 1000 (luma units), and of B's
const uint kCtrPhotoSigmaB = 46u;
const uint kCtrD3Info = 48u;           // STUDY (guard witnesses, logged before adopted): cells whose A texture (mean absolute deviation of the lattice samples) is at least 0.02
const uint kCtrD3Rel35 = 49u;          // ... informative cells whose fine cost is at most 0.35 of their texture
const uint kCtrD3Rel50 = 50u;          // ... at most 0.50
const uint kCtrD3Rel75 = 51u;          // ... at most 0.75
const uint kCtrD3Uniq = 52u;           // cells with a cost under 0.06 whose margin to the best distinct alternative is at least 0.3 of that alternative's cost
const uint kCtrD3Coh = 53u;            // cells with a cost under 0.06 and at least two of their four neighbours within 3 source pixels of their vector
const uint kCtrD3Conf = 54u;           // cells whose confidence (quality x uniqueness x the support of the neighbours) is at least 0.5
const uint kCtrCount = 56u;

// The photometric normalisation of A (rc3 photo): A's value as it would read at B's exposure. photo.x is the gain, photo.y the bias
// (d0p_photo.comp); the identity (1, 0) when no exposure change was found, in which case the result is the input exactly.
float PhotoNormalize(float value, vec4 photo) {
  return clamp(value * photo.x + photo.y, 0.0, 1.0);
}

// Every global atomic of the diagnostics goes through this macro. The counters are one address per
// statistic shared by all workgroups, so on the Adreno they serialise: the product form has none.
layout(constant_id = 8) const int ZFG_COUNTERS = 1;
#define ZFG_COUNT(index) if (ZFG_COUNTERS != 0) atomicAdd(counters[index], 1u)

// A support record is (margin, hops, root vector, root id) in one vec4 of floats: the root vector is packed
// into one float exactly (each component -512..511, 20 bits, below 2^24) and the root id is the index of
// the cell that measured it, so two seeds with the same motion are two witnesses and one seed arriving by two
// paths is one.
float PackRoot(ivec2 v) {
  v = clamp(v, ivec2(-512), ivec2(511));
  return float((v.x + 512) * 1024 + (v.y + 512));
}

ivec2 UnpackRoot(float packed) {
  int i = int(packed);
  return ivec2(i / 1024 - 512, i % 1024 - 512);
}

// The temporal history of a cell, narrow: three words (rc3 tprior). Word 0 the final vector rounded to whole pixels as two int16; word 1
// the support margin and the hops of the final support record as two half floats; word 2 the ROOT vector of that record (PackRoot, 20 bits)
// and the id of the cell that measured it (12 bits, below 4096 cells). Written by D3 (commit), read by D4 pass 0.
uint PackHistoryVector(ivec2 v) {
  return (uint(v.x) & 0xFFFFu) | (uint(v.y) << 16);
}

ivec2 UnpackHistoryVector(uint word) {
  return ivec2(bitfieldExtract(int(word), 0, 16), bitfieldExtract(int(word), 16, 16));
}

bool DirectionLess(ivec2 a, ivec2 b) {
  return a.y < b.y || (a.y == b.y && a.x < b.x);
}

// RC1 reliability of a field texel (motion.xy, best cost, second cost): quality times uniqueness.
float Reliability(vec4 value) {
  float quality = clamp(1.0 - value.z, 0.0, 1.0);
  float uniqueness = clamp((value.w - value.z) / max(value.w, 1e-6), 0.0, 1.0);
  return quality * uniqueness;
}
