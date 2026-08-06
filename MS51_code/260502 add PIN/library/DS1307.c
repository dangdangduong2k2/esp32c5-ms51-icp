#ifndef _DS1307_C_
#define _DS1307_C_
#include "DS1307.h"
#include "I2C_SW.c"

uint8_t BCD2HEX(uint8_t value) {
	uint8_t result;
	result = ((value / 16) * 10);
	result += (value & 0x0F);
	return (result & 0xFF);
}

uint8_t HEX2BCD(uint8_t value) {
	uint8_t result;
	result = (value / 10 * 16);
	result |= (value % 10);
	return (result & 0xFF);
}

void DS1307_Read_Data(uint8_t *ptr, uint8_t address, uint8_t length) {
	uint8_t i = 0;

	I2C_SW_Start();        /* Start i2c bus */
	I2C_SW_Write(0xD0);    /* Connect to DS1307 */
	I2C_SW_Write(address); /* Request RAM address on DS1307 */
	I2C_SW_Restart();      /* Start i2c bus */
	I2C_SW_Write(0XD1);    /* Connect to DS1307 for Read */

	for (i = 0; i < length; i++, ptr++) {
		if (i == length - 1) *ptr = BCD2HEX(I2C_SW_Read(0));
		else
			*ptr = BCD2HEX(I2C_SW_Read(1));
	}
	I2C_SW_Stop();
}

void DS1307_Read_RAM(uint8_t *ptr, uint8_t address, uint8_t length) {
	uint8_t i = 0;

	I2C_SW_Start();        /* Start i2c bus */
	I2C_SW_Write(0xD0);    /* Connect to DS1307 */
	I2C_SW_Write(address); /* Request RAM address on DS1307 */
	I2C_SW_Restart();      /* Start i2c bus */
	I2C_SW_Write(0XD1);    /* Connect to DS1307 for Read */

	for (i = 0; i < length; i++, ptr++) {
		if (i == length - 1) *ptr = I2C_SW_Read(0);
		else
			*ptr = I2C_SW_Read(1);
	}
	I2C_SW_Stop();
}

void DS1307_Read_Time(DS1307_Time_str *_time) {
	uint8_t *ptr = (uint8_t *)_time;
	DS1307_Read_Data(ptr, SEC, sizeof(DS1307_Time_str));
}

void DS1307_Write_Data(uint8_t *ptr, uint8_t address, uint8_t length) {
	uint8_t i = 0;

	I2C_SW_Start();
	I2C_SW_Write(0XD0);
	I2C_SW_Write(address);
	for (i = 0; i < length; i++, ptr++) {
		I2C_SW_Write(HEX2BCD(*ptr));
	}
	I2C_SW_Stop();
}

void DS1307_Write_RAM(uint8_t *ptr, uint8_t address, uint8_t length) {
	uint8_t i = 0;

	I2C_SW_Start();
	I2C_SW_Write(0XD0);
	I2C_SW_Write(address);
	for (i = 0; i < length; i++, ptr++) {
		I2C_SW_Write(*ptr);
	}
	I2C_SW_Stop();
}

void DS1307_Write_Time(DS1307_Time_str *_time) {
	uint8_t *ptr = (uint8_t *)_time;
	DS1307_Write_Data(ptr, SEC, sizeof(DS1307_Time_str));
}

bool Check_DS1307_Online(void) {
	bool state = false;
	I2C_SW_Start();
	state = I2C_SW_Write(0XD0);
	I2C_SW_Stop();
	DS1307_Online = state;
	return state;
}

#ifdef __DS1307_READ_WRITE_ONE_BYTE_
uint8_t DS1307_read(uint8_t addr) {
	uint8_t ret = 0;
	I2C_SW_Start();       /* Start i2c bus */
	I2C_SW_Write(0xD0);   /* Connect to DS1307 */
	I2C_SW_Write(addr);   /* Request RAM address on DS1307 */
	I2C_SW_Restart();     /* Start i2c bus */
	I2C_SW_Write(0XD1);   /* Connect to DS1307 for Read */
	ret = I2C_SW_Read(0); /* Receive data */
	I2C_SW_Stop();
	return BCD2HEX(ret);
}

void DS1307_Write(uint8_t addr, uint8_t dat) {
	dat = HEX2BCD(dat);
	I2C_SW_Start();
	I2C_SW_Write(0XD0);
	I2C_SW_Write(addr);
	I2C_SW_Write(dat);
	I2C_SW_Stop();
}
#endif

void DS1307_Out_1Hz() {
	I2C_SW_Start();
	I2C_SW_Write(0xD0);
	I2C_SW_Write(DS1307_Control);
	I2C_SW_Write(0x10);
	I2C_SW_Stop();
}
#endif
