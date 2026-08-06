/*  MS51 & N76E003 GPIO macro
    Version: 1.4
    Date modified: 241024
    Author: Tran-Luyen
    Github: https://github.com/Tran-Luyen
    Source git: https://github.com/Tran-Luyen/Code-Lib/blob/main/NUVOTON/Library/MS51_GPIO_Macro.h
    File path: file:///D:\Works\Github_Projects\Code-Lib\NUVOTON\Library\MS51_GPIO_Macro.h
*/

#ifndef _MS51_GPIO_MACRO_H_
#define _MS51_GPIO_MACRO_H_
#include "MS51_32K.h"

// interrupt vector number
#define INT_NO_INT0  0
#define INT_NO_TMR0  1
#define INT_NO_INT1  2
#define INT_NO_TMR1  3
#define INT_NO_UART0 4
#define INT_NO_TMR2  5
#define INT_NO_I2C   6
#define INT_NO_GPIO  7 // PIN interrupt
#define INT_NO_BRO   8 // brown-out detection
#define INT_NO_SPI   9
#define INT_NO_WDT   10
#define INT_NO_ADC   11
#define INT_NO_CAP   12 // Input Capture
#define INT_NO_PWM   13
#define INT_NO_FB    14 // Fault brake interrupt
#define INT_NO_UART1 15
#define INT_NO_TMR3  16 // Timer 3
#define INT_NO_WKT   17 // Self Wake-up Timer

// port 0
sbit PIN_IO00 = P0 ^ 0;
sbit PIN_IO01 = P0 ^ 1;
sbit PIN_IO02 = P0 ^ 2;
sbit PIN_IO03 = P0 ^ 3;
sbit PIN_IO04 = P0 ^ 4;
sbit PIN_IO05 = P0 ^ 5;
sbit PIN_IO06 = P0 ^ 6;
sbit PIN_IO07 = P0 ^ 7;

// port 1
sbit PIN_IO10 = P1 ^ 0;
sbit PIN_IO11 = P1 ^ 1;
sbit PIN_IO12 = P1 ^ 2;
sbit PIN_IO13 = P1 ^ 3;
sbit PIN_IO14 = P1 ^ 4;
sbit PIN_IO15 = P1 ^ 5;
sbit PIN_IO16 = P1 ^ 6;
sbit PIN_IO17 = P1 ^ 7;

// port 2
sbit PIN_IO20 = P2 ^ 0;

// port 3
sbit PIN_IO30 = P3 ^ 0;

typedef enum IO_define {
	// port 0
	IO00 = 0x80,
	IO01,
	IO02,
	IO03,
	IO04,
	IO05,
	IO06,
	IO07,
	// port 1
	IO10 = 0x90,
	IO11,
	IO12,
	IO13,
	IO14,
	IO15,
	IO16,
	IO17,
	// port 2
	IO20 = 0x20, // RST_PIN, if that setting is INPUT then CONFIG0.2 = 0 must be set
	// port 3
	IO30 = 0x30
} IO_define;

// 2nd-stage glue defines
#define PIN_h_s(PIN) PIN_##PIN

#define sbit_PIN_low(PIN)        PIN_h_s(PIN) = 0             // set pin to LOW
#define sbit_PIN_high(PIN)       PIN_h_s(PIN) = 1             // set pin to HIGH
#define sbit_PIN_toggle(PIN)     PIN_h_s(PIN) = !PIN_h_s(PIN) // TOGGLE pin
#define sbit_PIN_read(PIN)       (PIN_h_s(PIN))               // READ pin
#define sbit_PIN_write(PIN, val) PIN_h_s(PIN) = val           // WRITE pin value
#define sbit_PIN(PIN)            PIN_h_s(PIN)
#define sbit_PIN_set(PIN)        PIN_h_s(PIN)

#define PIN_low(PIN)        PIN_h_s(PIN) = 0             // set pin to LOW
#define PIN_high(PIN)       PIN_h_s(PIN) = 1             // set pin to HIGH
#define PIN_toggle(PIN)     PIN_h_s(PIN) = !PIN_h_s(PIN) // TOGGLE pin
#define PIN_read(PIN)       (PIN_h_s(PIN))               // READ pin
#define PIN_write(PIN, val) PIN_h_s(PIN) = val           // WRITE pin value
#define PIN(PIN)            PIN_h_s(PIN)
#define PIN_set(PIN)        PIN_h_s(PIN)

// Set PIN as INPUT (high impedance, no pullup)
#define PIN_input(PIN)                                                                                                                                                                                 \
	((PIN >= IO00) && (PIN <= IO07)                                                                                                                                                                    \
	     ? (P0M1 |= (1 << (PIN & 7)), P0M2 &= ~(1 << (PIN & 7)))                                                                                                                                       \
	     : ((PIN >= IO10) && (PIN <= IO17) ? (P1M1 |= (1 << (PIN & 7)), P1M2 &= ~(1 << (PIN & 7))) : ((PIN == IO30) ? (P3M1 |= (1 << (PIN & 7)), P3M2 &= ~(1 << (PIN & 7))) : (0))))

// Set PIN as INPUT with internal PULLUP resistor
#define PIN_input_PU(PIN)                                                                                                                                                                              \
	((PIN >= IO00) && (PIN <= IO07)                                                                                                                                                                    \
	     ? (P0M1 &= ~(1 << (PIN & 7)), P0M2 &= ~(1 << (PIN & 7)))                                                                                                                                      \
	     : ((PIN >= IO10) && (PIN <= IO17) ? (P1M1 &= ~(1 << (PIN & 7)), P1M2 &= ~(1 << (PIN & 7))) : ((PIN == IO30) ? (P3M1 &= ~(1 << (PIN & 7)), P3M2 &= ~(1 << (PIN & 7))) : (0))))

// Set PIN as OUTPUT (push-pull)
#define PIN_output(PIN)                                                                                                                                                                                \
	((PIN >= IO00) && (PIN <= IO07)                                                                                                                                                                    \
	     ? (P0M1 &= ~(1 << (PIN & 7)), P0M2 |= (1 << (PIN & 7)))                                                                                                                                       \
	     : ((PIN >= IO10) && (PIN <= IO17) ? (P1M1 &= ~(1 << (PIN & 7)), P1M2 |= (1 << (PIN & 7))) : ((PIN == IO30) ? (P3M1 &= ~(1 << (PIN & 7)), P3M2 |= (1 << (PIN & 7))) : (0))))

// Set PIN as OUTPUT (push-pull)
#define PIN_output_PP PIN_output

// Set PIN as OPEN-DRAIN OUTPUT (also high-impedance input, no pullup)
#define PIN_output_OD(PIN)                                                                                                                                                                             \
	((PIN >= IO00) && (PIN <= IO07)                                                                                                                                                                    \
	     ? (P0M1 |= (1 << (PIN & 7)), P0M2 |= (1 << (PIN & 7)))                                                                                                                                        \
	     : ((PIN >= IO10) && (PIN <= IO17) ? (P1M1 |= (1 << (PIN & 7)), P1M2 |= (1 << (PIN & 7))) : ((PIN == IO30) ? (P3M1 |= (1 << (PIN & 7)), P3M2 |= (1 << (PIN & 7))) : (0))))

#endif