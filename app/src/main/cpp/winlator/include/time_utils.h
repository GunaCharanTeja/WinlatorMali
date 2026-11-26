#ifndef WINLATOR_TIME_UTILS_H
#define WINLATOR_TIME_UTILS_H

#include <sys/time.h>
#include <time.h>
#include <stdint.h>

#define NSEC_PER_SEC 1000000000UL

static inline uint64_t nanoTime(void) {
    struct timespec time;
    clock_gettime(CLOCK_REALTIME, &time);
    return time.tv_sec * NSEC_PER_SEC + time.tv_nsec;
}

static inline uint64_t currentTimeMillis(void) {
    return nanoTime() / 1000000UL;
}

static inline void timespecFromNanos(uint64_t ns, struct timespec* ts) {
    ts->tv_sec = ns / NSEC_PER_SEC;
    ts->tv_nsec = ns % NSEC_PER_SEC;
}

static inline uint64_t timespecToMillis(struct timespec* ts) {
    return (ts->tv_sec * NSEC_PER_SEC + ts->tv_nsec) / 1000000UL;
}

static inline void msleep(uint64_t ms) {
    struct timespec ts;
    ts.tv_sec = ms / 1000;
    ts.tv_nsec = (ms % 1000) * 1000000L;
    nanosleep(&ts, NULL);
}

#endif
