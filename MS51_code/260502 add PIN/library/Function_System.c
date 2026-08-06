#ifndef FUNCTION_SYSTEM_C_
#define FUNCTION_SYSTEM_C_
#include "Function_System.h"
#include "stdbool.h"

void memset_User(uint8_t *destination, uint8_t value, uint8_t len) {
	uint8_t i = 0;
	for (i = 0; i < len; i++, destination++) {
		*destination = value;
	}
}

void memcpy_User(uint8_t *destination, uint8_t *source, uint8_t len) {
	uint8_t i = 0;
	for (i = 0; i < len; i++, destination++, source++) {
		*destination = *source;
	}
}

void reboot_system_AP_ROM(void) {
	bool temp = EA;
	EA        = 0;
	TA_protected;
	CHPCON = 0x00;
	TA_protected;
	CHPCON = 0x80;
	EA     = temp;
}

#endif