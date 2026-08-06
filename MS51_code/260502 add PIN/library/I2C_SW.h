#ifndef _I2C_SW_H_
#define _I2C_SW_H_
#include "MS51.h"
#include "Define_Variable.h"
#include "bit.h"
#include "Config_GPIO.h"

sbit Pin_SCL = P1 ^ 2;
sbit Pin_SDA = P1 ^ 3;

void I2C_SW_Init(void);
void I2C_SW_Start(void);
void I2C_SW_Restart(void);
void I2C_SW_Stop(void);
bool I2C_SW_Write(uint8_t dat);
uint8_t I2C_SW_Read(bool ack);

#ifndef _DELAY_US_H_
#define _DELAY_US_H_
void delay_us(uint16_t t);
#endif
#endif
