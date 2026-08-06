/*  Common header file
    Version: 1.2
    Date modified: 241024
    Author: Tran-Luyen
    Github: https://github.com/Tran-Luyen
    Source git: https://github.com/Tran-Luyen/Code-Lib/blob/main/Common-lib/common.h
    File path file:///D:\Works\Github_Projects\Code-Lib\Common-lib/common.h
*/

#ifndef _COMMON_H_
#define _COMMON_H_
#include "bit.h"

#define KEIL_C_COMPILER

#if defined(CCS_C_COMPILER)
#define PIN_low(PIN)        output_low(PIN)      // set pin to LOW
#define PIN_high(PIN)       output_high(PIN)     // set pin to HIGH
#define PIN_toggle(PIN)     output_toggle(PIN)   // TOGGLE pin
#define PIN_read(PIN)       input(PIN)           // READ pin
#define PIN_write(PIN, val) output_bit(PIN, val) // WRITE pin value

#define PIN_output(PIN) output_drive(PIN) // Set pin as OUTPUT
#define PIN_input(PIN)  output_float(PIN) // Set pin as INPUT

#elif defined(KEIL_C_COMPILER)
// #define _USE_CONFIG_GPIO_

#ifdef _USE_CONFIG_GPIO_
#include "Config_GPIO.h"

#define PIN_low(PIN)        Output_Pin(PIN, 0)           // set pin to LOW
#define PIN_high(PIN)       Output_Pin(PIN, 1)           // set pin to HIGH
#define PIN_toggle(PIN)     Output_Pin(PIN, !Input(PIN)) // TOGGLE pin
#define PIN_read(PIN)       Input(PIN)                   // READ pin
#define PIN_write(PIN, val) Output_Pin(PIN, val)         // WRITE pin value

// Set PIN as INPUT (high impedance, no pullup)
#define PIN_input(PIN)      Config_GPIO(PIN, Input_Only) // Set PIN as INPUT

// Set PIN as INPUT with internal PULLUP resistor
#define PIN_input_PU(PIN)   Config_GPIO(PIN, Quasi)

// Set PIN as OUTPUT (push-pull)
#define PIN_output(PIN)     Config_GPIO(PIN, Push_Pull) // Set PIN as OUTPUT

// Set PIN as OPEN-DRAIN OUTPUT (also high-impedance input, no pullup)
#define PIN_output_OD(PIN)  Config_GPIO(PIN, Open_Drain)

// Set PIN as OUTPUT (push-pull)
#define PIN_output_PP       PIN_output

#else

#define MS51FB9AE

#if defined(MS51FB9AE) || defined(N76E003)
#include "MS51_GPIO_Macro.h"

#else
#error "Must define MS51FB9AE or N76E003 or ..."
#endif

#endif

#elif defined(Arduino_COMPILER)

#include <Arduino.h>

#define output_low(PIN)      digitalWrite(PIN, LOW)
#define output_high(PIN)     digitalWrite(PIN, HIGH)
#define output_bit(PIN, val) digitalWrite(PIN, val)
#define output_toggle(PIN)   digitalWrite(PIN, !digitalRead(PIN))
#define output_float(PIN)    pinMode(PIN, INPUT)
#define output_drive(PIN)    pinMode(PIN, OUTPUT)
#define input(PIN)           digitalRead(PIN)

#define delay_us(val) delayMicroseconds(val)
#define delay_ms(val) delay(val)

#define PIN_low(PIN)        digitalWrite(PIN, LOW)               // set PIN to LOW
#define PIN_high(PIN)       digitalWrite(PIN, HIGH)              // set PIN to HIGH
#define PIN_toggle(PIN)     digitalWrite(PIN, !digitalRead(PIN)) // TOGGLE PIN
#define PIN_read(PIN)       digitalRead(PIN)                     // READ PIN
#define PIN_write(PIN, val) digitalWrite(PIN, val)               // WRITE PIN value

// Set PIN as INPUT (high impedance, no pullup)
#define PIN_input(PIN)      pinMode(PIN, INPUT)                  // Set PIN as INPUT

// Set PIN as INPUT with internal PULLUP resistor
#define PIN_input_PU(PIN)   pinMode(PIN, INPUT_PULLUP)

// Set PIN as OUTPUT (push-pull)
#define PIN_output(PIN)     pinMode(PIN, OUTPUT) // Set PIN as OUTPUT

// Set PIN as OPEN-DRAIN OUTPUT (also high-impedance input, no pullup)
#define PIN_output_OD(PIN)  pinMode(PIN, OUTPUT_OPEN_DRAIN)

#define PIN_output_PP PIN_output

#else
#error "Define CCS_C_COMPILER or KEIL_C_COMPILER or Arduino_COMPILER"
#endif

void my_memset(void *destination, uint8_t value, uint8_t len) {
	uint8_t  i = 0;
	uint8_t *p = (uint8_t *)destination;
	for (i = 0; i < len; i++, p++) {
		*p = value;
	}
}

void my_memcpy(void *destination, void *source, uint8_t len) {
	uint8_t  i = 0;
	uint8_t *p = (uint8_t *)destination;
	uint8_t *s = (uint8_t *)source;
	for (i = 0; i < len; i++, p++, s++) {
		*p = *s;
	}
}

void my_memset_u16(void *destination, uint8_t value, uint8_t len) {
	uint8_t   i = 0;
	uint16_t *p = (uint16_t *)destination;
	for (i = 0; i < len; i++, p++) {
		*p = value;
	}
}

#endif