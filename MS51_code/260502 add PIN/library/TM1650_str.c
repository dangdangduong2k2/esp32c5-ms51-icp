#include "TM1650_str.h"

void TM1650_Set_Config(TM1650_Config *TM) {
	I2C_SW_Init(&TM->I2C);
	I2C_SW_Start(&TM->I2C);
	I2C_SW_Write(&TM->I2C, 0x48);
	I2C_SW_Write(&TM->I2C, TM->brightness | TM->display);
	I2C_SW_Stop(&TM->I2C);
}

void TM1650_Set_Display(TM1650_Config *TM, bool enable_display) { TM->display = enable_display; }

void TM1650_Set_Bright(TM1650_Config *TM, uint8_t brightness) { TM->brightness = brightness; }

void TM1650_INIT(TM1650_Config *TM) {
	I2C_SW_Init(&TM->I2C);
	TM1650_Set_Config(TM);
}

uint8_t TM1650_Read(TM1650_Config *TM, uint8_t *ack) {
	uint8_t dat = 0;
	I2C_SW_Start(&TM->I2C);
	*ack = I2C_SW_Write(&TM->I2C, 0x49);
	dat  = I2C_SW_Read(&TM->I2C, 0);
	I2C_SW_Stop(&TM->I2C);
	// if (bit_test(dat, 6) == 0) dat = 0;
	return dat;
}

void TM1650_Write(TM1650_Config *TM, uint8_t addr, uint8_t dat) {
	I2C_SW_Start(&TM->I2C);
	I2C_SW_Write(&TM->I2C, addr);
	I2C_SW_Write(&TM->I2C, dat);
	I2C_SW_Stop(&TM->I2C);
}

void TM1650_write_nByte(TM1650_Config *TM, uint8_t *dat) {
	uint8_t addr = 0x6E;
	uint8_t i;
	for (i = 0; i < 4; i++, dat++) {
		I2C_SW_Start(&TM->I2C);
		I2C_SW_Write(&TM->I2C, addr);
		I2C_SW_Write(&TM->I2C, *dat);
		addr -= 2;
		I2C_SW_Stop(&TM->I2C);
	}
}

void TM1650_display(TM1650_Config *TM, uint16_t number) {
	uint8_t addr = 0x6E;
	uint8_t dat[4];
	uint8_t i;
	TM1650_Set_Config(TM);
	for (i = 0; i < 4; i++) {
		if (number == 0 && i > 0 && TM->meaningless_zero) dat[i] = 0;
		else { dat[i] = led_code[number % 10]; }
		number /= 10;
		TM1650_Write(TM, addr, dat[i]);
		addr -= 2;
	}
}

