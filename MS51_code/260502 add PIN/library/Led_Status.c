#ifndef LED_STATUS_C_
#define LED_STATUS_C_
#include "Led_Status.h"
#include "bit.c"

bool enable_Led = false;

void Led_Status_Run(void) {
	if (enable_Led) {
		if (++delay_led > set_delay_led) {
			if (++index_led > 9) index_led = 0;
			delay_led      = 0;
			LED_Status_Pin = !bit_test(data_led, index_led);
		}

		if (led_time_uot > 0) {
			led_time_uot--;
			if (led_time_uot == 0) {
				data_led      = 5;
				set_delay_led = 49;
			}
		}
		enable_Led = false;
	}
}

void Led_Send_Data(void) {
	data_led      = 0xAAAA;
	set_delay_led = 19;
	led_time_uot  = 250;
}

#endif
