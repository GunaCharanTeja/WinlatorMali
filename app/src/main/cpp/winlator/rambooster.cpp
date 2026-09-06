#include <jni.h>
#include <cstdlib>
#include <cstring>
#include <unistd.h>
#include <sys/mman.h>
#include <android/log.h>
#include <vector>

#define TAG "RamBooster-Native"

struct AllocatedBlock {
    void* addr;
    size_t size;
};

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_winlator_cmod_core_RamBooster_pressure(JNIEnv *env, jclass clazz, jlong targetBytes, jint chunkSleepMs) {
    size_t totalToAlloc = static_cast<size_t>(targetBytes);
    if (totalToAlloc == 0) return JNI_TRUE;

    size_t chunkSize = 64 * 1024 * 1024; // 64MB chunks
    size_t pageSize = sysconf(_SC_PAGESIZE);
    if (pageSize == 0) pageSize = 4096;

    std::vector<AllocatedBlock> blocks;
    size_t allocated = 0;

    __android_log_print(ANDROID_LOG_INFO, TAG, "Starting pressure: %zu bytes", totalToAlloc);

    while (allocated < totalToAlloc) {
        size_t toAlloc = (totalToAlloc - allocated > chunkSize) ? chunkSize : (totalToAlloc - allocated);

        void* m = mmap(nullptr, toAlloc, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);

        if (m == MAP_FAILED) {
            if (chunkSize > 1024 * 1024) {
                chunkSize /= 2;
                continue;
            } else break;
        }

        // Touch every page to force physical commitment
        for (size_t offset = 0; offset < toAlloc; offset += pageSize) {
            ((volatile char*)m)[offset] = 1;
        }

        blocks.push_back({m, toAlloc});
        allocated += toAlloc;

        if (chunkSleepMs > 0) usleep(chunkSleepMs * 1000);
    }

    // Hold for a moment to let LMK work
    usleep(500000);

    // Release memory
    for (const auto& block : blocks) {
        munmap(block.addr, block.size);
    }

    __android_log_print(ANDROID_LOG_INFO, TAG, "Pressure finished. Released %zu bytes", allocated);

    return JNI_TRUE;
}
