#ifndef _24C32_C_
#define _24C32_C_
#include "24C32.h"
#include "I2C_SW.c"

bool Check_24C32_Online(void) {
	bool result = false;
	I2C_SW_Start();
	result = I2C_SW_Write(_24C32_Address);
	I2C_SW_Stop();
	_24C32_Online = result;
	return result;
}
#endif
