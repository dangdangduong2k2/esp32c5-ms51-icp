#include "millis.h"

TICK_TYPE millis(void) { return millis_second; }

bool tick_timeout(TICK_TYPE *tick, TICK_TYPE timeout) {
	TICK_TYPE m = millis();
	if (((TICK_TYPE)(m - *tick)) >= timeout) {
		*tick = m;
		return true;
	}
	return false;
}

void ticks_reset(TICK_TYPE *tick) { *tick = millis(); }