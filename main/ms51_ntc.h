#pragma once
/* 3.3 V -> 10k pullup -> ADC -> B3950 NTC -> GND. */
const char *ms51_ntc_temperature(int millivolts, int raw, int nominal_ohms, int *centi_celsius);
