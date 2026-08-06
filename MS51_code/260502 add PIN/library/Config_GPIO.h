#ifndef _Config_GPIO_H_
#define _Config_GPIO_H_
#include "stdbool.h"

#define Pin_P00 0x80 // MOSI, PWM out channel 3, Input capture channel 3, External count in to Timer/Counter 1 or its toggle out.
#define Pin_P01 0x81 // MISO, PWM out channel 4, Input capture channel 4
#define Pin_P02 0x82 // RXD1, ICP clock in, OCD clock in, I2C SCL
#define Pin_P03 0x83 // PWM out channel 5, Input capture channel 5, ADC in channel 6
#define Pin_P04 0x84 // ADC in channel 5, External start ADC trigger, PWM out channel 3, Input capture channel 3
#define Pin_P05 0x85 // PWM out channel 2, In capture channel 6, External count in to Timer/Counter 0 or its toggle out, ADC in channel 4
#define Pin_P06 0x86 // TXD0 or RXD0, ADC in channel 3
#define Pin_P07 0x87 // RXD0 or TXD0, ADC in channel 2

#define Pin_P10 0x90 // SPI Clock, PWM out channel 2, Input capture channel 2
#define Pin_P11 0x91 // PWM out channel 1, Input capture channel 1, ADC in channel 7, System clock out
#define Pin_P12 0x92 // PWM out channel 0, Input capture channel 0
#define Pin_P13 0x93 // I2C SCL, External start ADC trigger
#define Pin_P14 0x94 // I2C SDA, Fault Brake in, PWM out channel 1
#define Pin_P15 0x95 // SPI slave select in, PWM out channel 5, Input capture channel 7
#define Pin_P16 0x96 // TXD1, ICP data in or out, OCD data in or out, I2C SDA
#define Pin_P17 0x97 // INT1, ADC in channel 0

#define Pin_P20 0xA0 // RST or in only
#define Pin_P21 0xA1
#define Pin_P22 0xA2
#define Pin_P23 0xA3
#define Pin_P24 0xA4
#define Pin_P25 0xA5
#define Pin_P26 0xA6
#define Pin_P27 0xA7

#define Pin_P30 0xB0 // INT0, ADC in channel 1
#define Pin_P31 0xB1
#define Pin_P32 0xB2
#define Pin_P33 0xB3
#define Pin_P34 0xB4
#define Pin_P35 0xB5
#define Pin_P36 0xB6
#define Pin_P37 0xB7

#define Quasi      0 // Quasi-bidirectional
#define Push_Pull  1 // Push-Pull
#define Input_Only 2 // Input-only
#define Open_Drain 3 // Open-Drain

#define output_bit(pin, state)  Output_Pin(pin, state)

#define input(pin) Input(pin)

void Config_GPIO(uint8_t pin, uint8_t mode);
void Output_Pin(uint8_t pin, bool state);

void output_high(uint8_t pin);

void output_low(uint8_t pin);

void output_toggle(uint8_t pin);

bool Input(uint8_t pin);


#endif
