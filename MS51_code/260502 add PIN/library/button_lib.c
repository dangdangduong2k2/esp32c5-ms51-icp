// call setFuncReadBtn(&readButtonState) in setup() function
// call func button_init in setup() function
// call func button_update in loop
#ifndef __BUTTON_LIB_C__
#define __BUTTON_LIB_C__
#include "button_lib.h"

bool readBtn(uint8_t btn) { return (*btnFuncPtr)(btn); }

void setFuncReadBtn(func_int fun) { btnFuncPtr = fun; }

#ifdef BUTTON_DEBOUNCE
void button_debounce_check(void) {
	static uint8_t debounce_count[btnTotal];
	uint8_t        i;
	bool           state = 0;
	for (i = 0; i < btnTotal; i++) {
		state = readBtn(i);
		if (state != button_debounce_state[i]) {
			if (++debounce_count[i] >= debounce_const[state]) {
				button_debounce_state[i] = state;
				debounce_count[i]        = 0;
			}
		}
		else
			debounce_count[i] = 0;
	}
}
#endif

void button_update(void) {
	uint8_t b;
	bool    timeout;
// read button state
#ifdef BUTTON_DEBOUNCE
	button_debounce_check();
#endif
	timeout = tick_timeout(&btn_tick, 50);
	for (b = 0; b < btnTotal; b++) {
		if (timeout) {
			if (btn[b].lastPress < b_rf_timeout) btn[b].lastPress++;
			if (btn[b].active && btn[b].holdTime < btn_hold_time_max) btn[b].holdTime++;
		}
		if (button_read(b)) {
			if (btn[b].lastPress == b_rf_timeout) {
				btn[b].active = 1;
				btn[b].event  = PRESSED;
			}
			btn_Refresh(b);
		}
		else {
			btn[b].holdTime = 0;
			if (btn[b].active) {
				btn_active_clear(b);
				btn[b].event = RELEASE;
				btn_Refresh(b);
			}
		}
	}
}

bool button_pressed(uint8_t b) {
	if (btn[b].event == PRESSED) {
		btn[b].event = EMPTY;
		return true;
	}
	return false;
}

bool button_released(uint8_t b) {
	if (btn[b].event == RELEASE) {
		btn[b].event = EMPTY;
		return true;
	}
	return false;
}
// time *50ms
bool button_hold(uint8_t b, uint8_t time) {
	if (btn[b].holdTime >= time) return true;
	return false;
}

void button_reset(uint8_t b) { // if btn < BTN_TOTAL: reset only btn, else reset all button
	if (b < btnTotal) my_memset(&btn[b], 0, sizeof(btn[0]));
	else { my_memset(&btn, 0, sizeof(btn)); }
}

bool button_active(uint8_t b) { return button_read(b) & 0x01; }

uint8_t button_active_total(void) {
	uint8_t result = 0;
	uint8_t b;
	for (b = 0; b < btnTotal; b++) {
		if (btn[b].active) result++;
	}
	return result;
}

bool function_null(uint8_t btn) { return btn; }

void neverCall(void) { setFuncReadBtn(&function_null); }

void button_init(void) { //
	my_memset(&btn, 0, sizeof(btn));
#ifdef BUTTON_DEBOUNCE
	memset_User(&button_debounce_state, 0, sizeof(button_debounce_state));
#endif
}

#endif
