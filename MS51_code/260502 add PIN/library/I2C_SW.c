#ifndef _I2C_SW_C_
#define _I2C_SW_C_
#include "I2C_SW.h"
#pragma  NOAREGS

#ifndef _DELAY_US_C_
#define _DELAY_US_C_
void delay_us(uint16_t t) {
	uint8_t i = 0;
	while (--t) {
		;
	}
}
#endif

void I2C_SW_Init(void) {
	// P1.2 and P1.3 quasi mode
	// P1_MOD_OC |= 0x0C;
	// P1_DIR_PU |= 0x0C;
	Pin_SCL = 1;
	Pin_SDA = 1;
}

void I2C_SW_Start(void) {
	Pin_SCL = 1;
	Pin_SDA = 1;
	delay_us(10);
	Pin_SDA = 0;
	delay_us(10);
	Pin_SCL = 0;
}

void I2C_SW_Restart(void) {
	delay_us(10);
	Pin_SCL = 1;
	Pin_SDA = 1;
	delay_us(10);
	Pin_SDA = 0;
	delay_us(10);
	Pin_SCL = 0;
}

void I2C_SW_Stop(void) {
	Pin_SDA = 0;
	Pin_SCL = 0;
	delay_us(10);
	Pin_SCL = 1;
	delay_us(10);
	Pin_SDA = 1;
}

bool I2C_SW_Write(uint8_t dat) {
	uint8_t i   = 0;
	bool    ack = false;
	Pin_SDA         = 1;
	for (i = 0; i < 8; i++) {
		Pin_SDA = bit_test(dat, 7 - i);
		delay_us(10);
		SCL = 1;
		delay_us(10);
		SCL = 0;
		if (i == 7) Pin_SDA = 1;
	}
	delay_us(20);
	SCL = 1;
	delay_us(25);
	ack = !Pin_SDA;
	delay_us(25);
	SCL = 0;
	return ack;
}

uint8_t I2C_SW_Read(bool ack) {
	uint8_t i   = 0;
	uint8_t dat = 0;
    Pin_SDA = 1;
	for (i = 0; i < 8; i++) {
		delay_us(10);
		SCL = 1;
		delay_us(7);
		dat = dat * 2;
		if (Pin_SDA) dat = dat + 1;
		delay_us(3);
        SCL = 0;
	}
	delay_us(10);
    Pin_SDA = !ack;
	delay_us(10);
    SCL = 1;
	delay_us(50);
    SCL = 0;
	return dat;
}
#endif