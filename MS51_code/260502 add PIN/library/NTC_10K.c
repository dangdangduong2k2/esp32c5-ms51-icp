#ifndef _NTC_10K_C_
#define _NTC_10K_C_
#include "NTC_10K.h"

float Thermistor(uint16_t Vo, uint16_t Vcc) {
	float    Temp;
	uint32_t vx = Vo; // Vr
	uint32_t rt = 0;

	vx = vx * Vcc;
	vx = vx / 4095;

	rt = vx * 10000;
	rt = rt / (Vcc - vx);

	Temp = log(rt);
	// Temp = (1.0 / (NTC_10K_A + NTC_10K_B * logRt + NTC_10K_C * logRt * logRt * logRt));
	// We get the temperature value in Kelvin from this Stein-Hart equation
	Temp = (1.0 / (NTC_10K_A + NTC_10K_B * Temp + NTC_10K_C * Temp * Temp * Temp));
	// We get the temperature value in Kelvin from this Stein-Hart equation
	Temp = Temp - 273.15; // Convert Kelvin to Celcius
	// Tf    = (Tc * 1.8) + 32.0;   // Convert Kelvin to Fahrenheit
	return Temp;
}

float Thermistor_RT(uint16_t rt) {
	float tmp = log(rt);
	tmp = (1.0 / (NTC_10K_A + NTC_10K_B * tmp + NTC_10K_C * tmp * tmp * tmp));
	tmp = tmp - 273.15; // Convert Kelvin to Celcius
	// Tf    = (Tc * 1.8) + 32.0;   // Convert Kelvin to Fahrenheit
	return tmp;
}

#endif
