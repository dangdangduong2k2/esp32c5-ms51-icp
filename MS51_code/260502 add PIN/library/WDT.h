#ifndef _WDT_H_
#define _WDT_H_
#include "stdbool.h"
#ifndef _IAP_H_
#define BYTE_READ_CONFIG    0xC0
#define BYTE_PROGRAM_CONFIG 0xE1
#endif

#ifndef _TA_
#define _TA_
#define TA_protected                                                                                                                                 \
	TA = 0xAA;                                                                                                                                       \
	TA = 0x55
#endif

void Clear_WDT(void);
void On_WDT_1638_mS(void);

#endif
