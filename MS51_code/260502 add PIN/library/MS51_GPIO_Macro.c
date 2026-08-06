#include "MS51_GPIO_Macro.h"
#include "bit.h"

void write_sfr(uint8_t sfr_address, uint8_t value) {
	switch (sfr_address) {
		case 0x80: P0 = value; break;
		case 0x90: P1 = value; break;
		case 0xA0: P2 = value; break;
		case 0xB0: P3 = value; break;
		default: break;
	}
}

uint8_t read_sfr(uint8_t sfr_address) {
	switch (sfr_address) {
		case 0x80: return P0; break;
		case 0x90: return P1; break;
		case 0xA0: return P2; break;
		case 0xB0: return P3; break;
		default: break;
	}
	return 0;
}