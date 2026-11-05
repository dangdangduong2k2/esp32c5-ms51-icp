#pragma once
#include <stdbool.h>
#include <stdint.h>
#include <math.h>

#define MS51_CLOUD_TIME_FLOOR INT64_C(1767225600)
typedef enum {
    MS51_CLOUD_TIME_OK,
    MS51_CLOUD_TIME_INVALID,
    MS51_CLOUD_TIME_UNSYNCED,
    MS51_CLOUD_TIME_FUTURE,
    MS51_CLOUD_TIME_EXPIRED,
} ms51_cloud_time_result_t;

static inline ms51_cloud_time_result_t ms51_cloud_check_time(double issued, double expires, int64_t now) {
    if (!isfinite(issued) || !isfinite(expires) || expires <= issued || expires-issued > 60)
        return MS51_CLOUD_TIME_INVALID;
    if (now <= MS51_CLOUD_TIME_FLOOR) return MS51_CLOUD_TIME_UNSYNCED;
    if (issued > (double)now+5) return MS51_CLOUD_TIME_FUTURE;
    if (expires < (double)now) return MS51_CLOUD_TIME_EXPIRED;
    return MS51_CLOUD_TIME_OK;
}

/* A retained RTC value is not evidence that SNTP has started on this boot. */
static inline bool ms51_cloud_sync_due(bool started, bool clock_ready, bool requested,
                                        int64_t now_us, int64_t last_attempt_us) {
    int64_t interval = requested ? INT64_C(15000000) : INT64_C(60000000);
    return (!started || !clock_ready || requested) &&
           (last_attempt_us < 0 || now_us-last_attempt_us >= interval);
}
