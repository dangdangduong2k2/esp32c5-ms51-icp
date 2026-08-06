#ifndef _WDT_C_
#define _WDT_C_
#include "WDT.h"

#ifndef _IAP_C_
uint8_t IAP_Read_Byte(uint8_t cmd, uint16_t addr) {
	uint8_t dat = 0;

	IAPCN = cmd;
	IAPAH = (addr >> 8) & 0xFF;
	IAPAL = addr & 0xFF;
	TA_protected;
	IAPTRG |= SET_BIT0; // IAP Go
	dat = IAPFD;
	return dat;
}

void IAP_Write_Byte(unsigned char cmd, unsigned int addr, unsigned char dat) {
	IAPCN = cmd;
	IAPAH = (addr >> 8) & 0xFF;
	IAPAL = addr & 0xFF;
	IAPFD = dat;
	TA_protected;
	IAPTRG |= SET_BIT0; // IAP Go
}
#endif

void Clear_WDT(void) {
	bool temp = EA;
	EA        = 0;
	TA_protected;
	WDCON |= SET_BIT6;
	EA = temp;
}

void On_WDT_1638_mS(void) {
	bool    temp = EA;
	uint8_t CF4  = 0;

	EA = 0;

	TA_protected;
	CHPCON |= SET_BIT0;

	CF4 = IAP_Read_Byte(BYTE_READ_CONFIG, 0x0004);

	if ((CF4 & 0xF0) == 0xF0) {
		TA_protected;
		IAPUEN |= SET_BIT2; // CFUEN enable update CONFIG
		IAP_Write_Byte(BYTE_PROGRAM_CONFIG, 0x0004, 0x0F);
		TA_protected;
		IAPUEN &= ~SET_BIT2; // CFUEN disable update CONFIG
		while ((CHPCON & SET_BIT6) == SET_BIT6)
			; // check IAPFF (CHPCON.6)
		TA_protected;
		CHPCON &= 0x00;
		TA_protected;
		CHPCON |= 0x80;
	}

	TA_protected;
	CHPCON &= ~SET_BIT0;

	TA_protected;
	WDCON |= 0x07; // set clock pre-scalar select
	Clear_WDT();
	while ((WDCON | ~SET_BIT6) == 0xFF)
		; // confirm WDT clear is ok before into power down mode
	TA_protected;
	WDCON |= SET_BIT7; // WDT run
	EA = temp;
}
#endif
