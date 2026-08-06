#ifndef _DELAY_H_
#define _DELAY_H_
#define USE_DELAY_ms
#define USE_DELAY_us

#ifdef USE_DELAY_ms
void delay_ms(UINT16 t);
#endif

#ifdef USE_DELAY_us
void delay_us(UINT16 t);
#endif
#endif
