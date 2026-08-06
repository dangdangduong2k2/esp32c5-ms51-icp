/*  MILLIS Library
    Version: 1.0
    Date modified: 241023
    Author: Tran-Luyen
    Author Github: https://github.com/Tran-Luyen
    Github Link: https://github.com/Tran-Luyen/Code-Lib/blob/main/NUVOTON/Library
    File path: file:///D:\Works\Github_Projects\Code-Lib\NUVOTON/Library/millis.h
*/

#ifndef _MILLIS_
#define _MILLIS_

#define TICK_TYPE uint32_t
extern volatile TICK_TYPE millis_second;

TICK_TYPE millis(void);
bool      tick_timeout(TICK_TYPE *tick, TICK_TYPE timeout);
void      ticks_reset(TICK_TYPE *tick);

#endif