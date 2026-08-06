#ifndef _DELAY_C_
#define _DELAY_C_
#include "delay.h"

#ifdef USE_DELAY_ms
void delay_ms(UINT16 t) {
	UINT16 i, j;
	for (i = 0; i < t; i++) {
		for (j = 0; j < 1000; j++) {
			nop;
			nop;
		}
	}
}
#endif

#ifdef USE_DELAY_us
void delay_us(UINT16 t) {
	while (--t) {
		nop;
		nop;
	}
}
#endif
#endif
