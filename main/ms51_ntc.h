#pragma once
/* 3.3V -> 10k fixed resistor -> IO1 -> 10k B3950 NTC -> GND. */
const char *ms51_ntc_temperature(int millivolts, int raw, int *centi_celsius);
