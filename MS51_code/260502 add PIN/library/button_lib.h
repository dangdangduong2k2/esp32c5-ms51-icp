#ifndef __BUTTON_LIB_H__
#define __BUTTON_LIB_H__

#ifndef btnTotal
#define btnTotal 1
#endif

// enum button_name { btnTEST, btnTotal };
// const uint8_t btn_pin[btnTotal] = {1};
#define btn_Refresh(b)           btn[b].lastPress = 0
#define btn_active_clear(b)      btn[b].active = 0
#define btn_hold_time_refresh(b) btn[b].holdTime = 0;
#define btn_hold_time_max        255 // *50ms = 12750ms
#define btn_timeout              50  // 50ms
#define b_rf_timeout             2   // 4*50 = 200ms // button refresh timeout

enum button_stt { EMPTY, PRESSED, RELEASE };

typedef struct button_str {
	uint8_t active;
	uint8_t lastPress;
	uint8_t holdTime;
	uint8_t event;
} button_str;
uint32_t  btn_tick;
button_str btn[btnTotal];

bool (*btnFuncPtr)(uint8_t btn);
typedef bool (*func_int)(uint8_t btn);

#define BUTTON_DEBOUNCE
#ifndef BUTTON_DEBOUNCE
// #define button_read(pin) !input(btn_pin[pin])
#define button_read(pin) readBtn(pin)
// #define button_read(btn) readButtonState(btn)
#else
uint8_t       button_debounce_state[btnTotal];
const uint8_t debounce_const[2] = {30 , 30}; // press/ release
// const uint16_t debounce_const[2] = {1000, 1000}; // press/ release
#define button_read(pin) button_debounce_state[pin]
#endif

// function Declare
bool    readBtn(uint8_t btn);
void    setFuncReadBtn(func_int fun);
void    button_update(void);
bool    button_pressed(uint8_t b);
bool    button_released(uint8_t b);
bool    button_hold(uint8_t b, uint8_t time);
void    button_reset(uint8_t b);
bool    button_active(uint8_t b);
uint8_t button_active_total(void);
void    button_init(void);

#endif
