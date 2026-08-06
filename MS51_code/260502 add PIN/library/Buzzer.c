#ifndef BUZZER_C_
#define BUZZER_C_
#include "Buzzer.h"

void Buzzer_Run() {
	if (enable_Buzzer) {
		if (En_buzzer) {
			if (data_buzzer > 0) {
				if (++index_buzzer > 49) {
					data_buzzer--;
					index_buzzer = 0;
					Buzzer_Pin ^= 1;
					if (data_buzzer == 0) Buzzer_Pin = 0;
				}
			}
		}
		else {
			Buzzer_Pin   = 0;
			index_buzzer = 0;
			data_buzzer  = 0;
		}
		enable_Buzzer = false;
	}
}

void Buzzer_Set(uint8_t par) {
	Buzzer_Pin  = 1;
	data_buzzer = par;
}

void Buzzer_Set_Run(uint8_t par, uint16_t delay) {
	uint8_t _delay = delay / 50;
	Buzzer_Pin     = 1;
	data_buzzer    = par;
	while (data_buzzer > 0) {
		Buzzer_Run();
		delay_ms(_delay);
		Clear_WDT();
	}
}
#endif
