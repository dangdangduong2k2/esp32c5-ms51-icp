#ifndef _CRC8_C_
#define _CRC8_C_
#include "CRC8.h"

uint8_t CRC8_Array(uint8_t *dat, uint8_t length) {
	uint8_t crc = 0x00;
	uint8_t extract;
	uint8_t sum;
	uint8_t i;
	uint8_t tempI;
	for (i = 0; i < length; i++) {
		extract = *dat;
		for (tempI = 8; tempI; tempI--) {
			sum = (crc ^ extract) & 0x01;
			crc >>= 1;
			if (sum) crc ^= 0x8C;
			extract >>= 1;
		}
		dat++;
	}
	return crc;
}

uint8_t CRC8(uint8_t crc, uint8_t dat) {
	uint8_t extract;
	uint8_t sum;
	uint8_t tempI;
	uint8_t _crc = crc;

	extract = dat;
	for (tempI = 8; tempI; tempI--) {
		sum = (_crc ^ extract) & 0x01;
		_crc >>= 1;
		if (sum) _crc ^= 0x8C;
		extract >>= 1;
	}
	return _crc;
}
#endif
