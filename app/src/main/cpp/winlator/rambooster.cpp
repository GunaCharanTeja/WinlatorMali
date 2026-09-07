#include <jni.h>
#include <cstdlib>
#include <cstring>
#include <unistd.h>
#include <sys/mman.h>
#include <android/log.h>
#include <vector>
#include <stdint.h>
#include <time.h>

#define TAG "RamBooster-Native"

struct AllocatedBlock {
    void* addr;
    size_t size;
};

// Helper to apply pressure with high-speed dirty filling
static void apply_pressure(size_t targetBytes, std::vector<AllocatedBlock>& blocks, size_t pageSize) {
    size_t allocated = 0;
    size_t chunkSize = 128 * 1024 * 1024; // Increased to 128MB for faster pressure

    while (allocated < targetBytes) {
        size_t toAlloc = (targetBytes - allocated > chunkSize) ? chunkSize : (targetBytes - allocated);
        void* m = mmap(nullptr, toAlloc, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);

        if (m == MAP_FAILED) {
            if (chunkSize > 1024 * 1024) { chunkSize /= 2; continue; }
            else break;
        }

        // Fill memory with pseudo-random pattern to prevent KSM (Kernel Samepage Merging)
        // and compression. We touch every single page.
        volatile char* ptr = static_cast<volatile char*>(m);
        char seed = static_cast<char>(time(nullptr) % 255);
        for (size_t offset = 0; offset < toAlloc; offset += pageSize) {
            ptr[offset] = static_cast<char>(seed + (offset % 128));
        }

        blocks.push_back({m, toAlloc});
        allocated += toAlloc;
    }
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_winlator_cmod_core_RamBooster_pressure(JNIEnv *env, jclass clazz, jlong targetBytes, jint chunkSleepMs, jint holdMs) {
    size_t totalToAlloc = static_cast<size_t>(targetBytes);
    if (totalToAlloc == 0) return JNI_TRUE;

    size_t pageSize = sysconf(_SC_PAGESIZE);
    if (pageSize == 0) pageSize = 4096;

    std::vector<AllocatedBlock> all_blocks;

    __android_log_print(ANDROID_LOG_INFO, TAG, "WAVE 1: Initial Pulse (%zu bytes)", totalToAlloc);
    apply_pressure(totalToAlloc, all_blocks, pageSize);

    // Manual/Max/High-Need profiles get the "Hammer" sequence
    if (holdMs >= 5000) {
        // WAVE 2: After a short delay, hit them again
        usleep(400000);
        size_t wave2 = 600 * 1024 * 1024;
        __android_log_print(ANDROID_LOG_INFO, TAG, "WAVE 2: Secondary Hammer (%zu bytes)", wave2);
        apply_pressure(wave2, all_blocks, pageSize);

        // WAVE 3: The "Final Shock" to kill the most stubborn apps
        usleep(300000);
        size_t wave3 = 400 * 1024 * 1024;
        __android_log_print(ANDROID_LOG_INFO, TAG, "WAVE 3: Final Shock (%zu bytes)", wave3);
        apply_pressure(wave3, all_blocks, pageSize);
    }

    if (holdMs > 0) {
        __android_log_print(ANDROID_LOG_INFO, TAG, "Locking physical RAM for %d ms...", holdMs);
        usleep(holdMs * 1000);
    }

    // Release phase
    size_t totalReleased = 0;
    for (const auto& block : all_blocks) {
        totalReleased += block.size;
        munmap(block.addr, block.size);
    }

    __android_log_print(ANDROID_LOG_INFO, TAG, "Booster Cycle Finished. Reclaimed space total: %zu bytes", totalReleased);
    return JNI_TRUE;
}
