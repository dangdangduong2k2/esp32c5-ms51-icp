#ifndef _NTC_10K_H_
#define _NTC_10K_H_
#include <MATH.H>

code float NTC_10K_A = 1.009249522e-03;
code float NTC_10K_B = 2.378405444e-04;
code float NTC_10K_C = 2.019202697e-07;

float Thermistor(uint16_t Vo, uint16_t Vcc);

float Thermistor_RT(uint16_t rt);

#endif
