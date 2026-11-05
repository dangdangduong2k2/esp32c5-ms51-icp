#include <assert.h>
#include <math.h>
#include <stdio.h>
#include "ms51_cloud_time.h"

int main(void) {
    int64_t now=INT64_C(1793898000);
    assert(ms51_cloud_check_time(now,now+45,now)==MS51_CLOUD_TIME_OK);
    assert(ms51_cloud_check_time(now-45,now,now)==MS51_CLOUD_TIME_OK);
    assert(ms51_cloud_check_time(now+5,now+60,now)==MS51_CLOUD_TIME_OK);
    assert(ms51_cloud_check_time(now+6,now+45,now)==MS51_CLOUD_TIME_FUTURE);
    assert(ms51_cloud_check_time(now-60,now-1,now)==MS51_CLOUD_TIME_EXPIRED);
    assert(ms51_cloud_check_time(now,now+61,now)==MS51_CLOUD_TIME_INVALID);
    assert(ms51_cloud_check_time(now+1,now,now)==MS51_CLOUD_TIME_INVALID);
    assert(ms51_cloud_check_time(now,now,now)==MS51_CLOUD_TIME_INVALID);
    assert(ms51_cloud_check_time(NAN,now+45,now)==MS51_CLOUD_TIME_INVALID);
    assert(ms51_cloud_check_time(now,INFINITY,now)==MS51_CLOUD_TIME_INVALID);
    assert(ms51_cloud_check_time(now,now+45,0)==MS51_CLOUD_TIME_UNSYNCED);
    // The live R3 case: a retained but slow RTC must not suppress initial SNTP.
    assert(ms51_cloud_check_time(now,now+45,now-30)==MS51_CLOUD_TIME_FUTURE);
    assert(ms51_cloud_sync_due(false,true,false,1000,-60000000));
    assert(!ms51_cloud_sync_due(true,true,false,60000000,1000));
    assert(ms51_cloud_sync_due(false,true,false,60000000,-60000000));
    assert(!ms51_cloud_sync_due(false,false,false,59999999,0));
    assert(ms51_cloud_sync_due(false,false,false,60000000,0));
    assert(!ms51_cloud_sync_due(true,true,true,14999999,0));
    assert(ms51_cloud_sync_due(true,true,true,15000000,0));
    // Resync never causes an invalid, stale or future command to execute.
    assert(ms51_cloud_check_time(now,now+45,now-30)!=MS51_CLOUD_TIME_OK);
    assert(ms51_cloud_check_time(now,now+45,now)==MS51_CLOUD_TIME_OK);
    puts("PASS: retained RTC sync, retries, resync rate limit, strict command lifetime and replay-time boundaries");
    return 0;
}
