#include <jni.h>
#include <cstdlib>
#include <cstring>
#include <unistd.h>
#include <sys/mman.h>
#include <android/log.h>
#include <vector>
#include <stdint.h>
#include <time.h>
#include <sched.h>
#include <sys/resource.h>

#include <cstdio>
#include <algorithm>

#define TAG "RamBooster-Native"

struct AllocatedBlock {
    void* addr;
    size_t size;
};

// Reads available memory from /proc/meminfo in real-time
static size_t get_mem_available_bytes() {
    FILE* f = fopen("/proc/meminfo", "r");
    if (!f) return 0;
    char line[128];
    size_t avail_kb = 0;
    while (fgets(line, sizeof(line), f)) {
        if (sscanf(line, "MemAvailable: %zu kB", &avail_kb) == 1) {
            break;
        }
    }
    fclose(f);
    return avail_kb * 1024;
}

// Helper to apply pressure with high-speed dirty filling and CPU yielding
static void apply_pressure(size_t targetBytes, std::vector<AllocatedBlock>& blocks, size_t pageSize, int chunkSleepMs) {
    size_t allocated = 0;
    size_t chunkSize = 64 * 1024 * 1024; // 64MB chunks for smoother bus pacing

    while (allocated < targetBytes) {
        size_t toAlloc = (targetBytes - allocated > chunkSize) ? chunkSize : (targetBytes - allocated);
        void* m = mmap(nullptr, toAlloc, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);

        if (m == MAP_FAILED) {
            if (chunkSize > 1024 * 1024) { chunkSize /= 2; continue; }
            else break;
        }

        volatile char* ptr = static_cast<volatile char*>(m);
        char seed = static_cast<char>(time(nullptr) % 255);

        for (size_t offset = 0; offset < toAlloc; offset += pageSize) {
            ptr[offset] = static_cast<char>(seed + (offset % 128));

            // Yield every 16MB of processing to keep the game smooth
            if (offset % (16 * 1024 * 1024) == 0) {
                sched_yield();
            }
        }

        blocks.push_back({m, toAlloc});
        allocated += toAlloc;

        // Paced sleep between chunks to protect memory bus bandwidth
        useconds_t sleepUs = (chunkSleepMs > 0) ? (static_cast<useconds_t>(chunkSleepMs) * 1000) : 1000;
        usleep(sleepUs);
    }
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_winlator_cmod_core_RamBooster_pressure(JNIEnv *env, jclass clazz, jlong targetBytes, jint chunkSleepMs, jint holdMs) {
    size_t totalToAlloc = static_cast<size_t>(targetBytes);
    if (totalToAlloc == 0) return JNI_TRUE;

    // Set booster thread to lowest possible priority to avoid game lag
    setpriority(PRIO_PROCESS, 0, 19);

    size_t pageSize = sysconf(_SC_PAGESIZE);
    if (pageSize == 0) pageSize = 4096;

    std::vector<AllocatedBlock> all_blocks;

    __android_log_print(ANDROID_LOG_INFO, TAG, "WAVE 1: Initial Pulse (%zu bytes)", totalToAlloc);
    apply_pressure(totalToAlloc, all_blocks, pageSize, chunkSleepMs);

    if (holdMs >= 5000) {
        // Before triggering aggressive secondary waves, check that the hardware has enough runway!
        size_t availNow = get_mem_available_bytes();
        const size_t MIN_SAFE_RUNWAY = 600ULL * 1024 * 1024; // 600MB safe floor

        if (availNow > MIN_SAFE_RUNWAY) {
            // Spaced out waves to reduce peak CPU contention
            usleep(800000); // 800ms
            size_t wave2 = std::min(static_cast<size_t>(600ULL * 1024 * 1024), availNow - MIN_SAFE_RUNWAY);
            if (wave2 > 50 * 1024 * 1024) {
                __android_log_print(ANDROID_LOG_INFO, TAG, "WAVE 2: Secondary Hammer (%zu bytes)", wave2);
                apply_pressure(wave2, all_blocks, pageSize, chunkSleepMs);
            }

            availNow = get_mem_available_bytes();
            if (availNow > MIN_SAFE_RUNWAY) {
                usleep(600000); // 600ms
                size_t wave3 = std::min(static_cast<size_t>(400ULL * 1024 * 1024), availNow - MIN_SAFE_RUNWAY);
                if (wave3 > 50 * 1024 * 1024) {
                    __android_log_print(ANDROID_LOG_INFO, TAG, "WAVE 3: Final Shock (%zu bytes)", wave3);
                    apply_pressure(wave3, all_blocks, pageSize, chunkSleepMs);
                }
            }
        } else {
            __android_log_print(ANDROID_LOG_WARN, TAG, "Hammer waves throttled: free RAM (%zu MB) below safety runway (600 MB)",
                                availNow / (1024 * 1024));
        }
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
