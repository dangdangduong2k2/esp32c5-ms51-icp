#ifndef _Config_GPIO_C_
#define _Config_GPIO_C_
#include "Config_GPIO.h"

#ifndef _BIT_8_C_
#define _BIT_8_C_
bool    bit_test_8(uint8_t dat, uint8_t n) { return ((dat >> n) & 0x01); }
uint8_t bit_set_8(uint8_t dat, uint8_t n) { return dat | (1 << n); }
uint8_t bit_clr_8(uint8_t dat, uint8_t n) { return dat & ~(1 << n); }
uint8_t bit_write_8(uint8_t dat, uint8_t n, uint8_t value) { return value ? bit_set_8(dat, n) : bit_clr_8(dat, n); }
#endif

void Config_GPIO(uint8_t pin, uint8_t mode) {
	switch (pin & 0xF0) {
		case 0x80:
			P0M1 = bit_write_8(P0M1, pin & 0x0F, bit_test_8(mode, 1));
			P0M2 = bit_write_8(P0M2, pin & 0x0F, bit_test_8(mode, 0));
			break;
		case 0x90:
			P1M1 = bit_write_8(P1M1, pin & 0x0F, bit_test_8(mode, 1));
			P1M2 = bit_write_8(P1M2, pin & 0x0F, bit_test_8(mode, 0));
			break;
#ifdef _P2_CONFIG_
		case 0xA0:
			P2M1 = bit_write_8(P2M1, pin & 0x0F, bit_test_8(mode, 1));
			P2M2 = bit_write_8(P2M2, pin & 0x0F, bit_test_8(mode, 0));
			break;
#endif
		case 0xB0:
			P3M1 = bit_write_8(P3M1, pin & 0x0F, bit_test_8(mode, 1));
			P3M2 = bit_write_8(P3M2, pin & 0x0F, bit_test_8(mode, 0));
			break;
		default: break;
	}
}

void Output_Pin(uint8_t pin, bool state) {
	switch (pin & 0xF0) {
		case 0x80:
			switch (pin & 0x0F) {
				case 0: P00 = state; break;
				case 1: P01 = state; break;
				case 2: P02 = state; break;
				case 3: P03 = state; break;
				case 4: P04 = state; break;
				case 5: P05 = state; break;
				case 6: P06 = state; break;
				case 7: P07 = state; break;
				default: break;
			}
			break;
		case 0x90:
			switch (pin & 0x0F) {
				case 0: P10 = state; break;
				case 1: P11 = state; break;
				case 2: P12 = state; break;
				case 3: P13 = state; break;
				case 4: P14 = state; break;
				case 5: P15 = state; break;
				case 6: P16 = state; break;
				case 7: P17 = state; break;
				default: break;
			}
			break;
#ifdef _P2_CONFIG_
		case 0xA0:
			switch (pin & 0x0F) {
				case 0: P20 = state; break;
				case 1: P21 = state; break;
				case 2: P22 = state; break;
				case 3: P23 = state; break;
				case 4: P24 = state; break;
				case 5: P25 = state; break;
				case 6: P26 = state; break;
				case 7: P27 = state; break;
				default: break;
			}
			break;
#endif
		case 0xB0:
			switch (pin & 0x0F) {
				case 0: P30 = state; break;
				// case 1: P31 = state; break;
				// case 2: P32 = state; break;
				// case 3: P33 = state; break;
				// case 4: P34 = state; break;
				// case 5: P35 = state; break;
				// case 6: P36 = state; break;
				// case 7: P37 = state; break;
				default: break;
			}
			break;
		default: break;
	}
}

bool Input(uint8_t pin) {
	switch (pin & 0xF0) {
		case 0x80: return bit_test_8(P0, pin & 0x0F);
		case 0x90: return bit_test_8(P1, pin & 0x0F);
#ifdef _P2_CONFIG_
		case 0xA0: return bit_test_8(P2, pin & 0x0F);
#endif
		case 0xB0: return bit_test_8(P3, pin & 0x0F);
		default: return false;
	}
}

void output_high(uint8_t pin) { Output_Pin(pin, 1); }

void output_low(uint8_t pin) { Output_Pin(pin, 0); }

void output_toggle(uint8_t pin) { Output_Pin(pin, !Input(pin)); }


#endif
