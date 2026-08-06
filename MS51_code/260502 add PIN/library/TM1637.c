#include "TM1637.h"

#ifndef TM1637_DIG
#define TM1637_DIG 6
#endif

// #ifndef TM1637_KEY_COM
// #define TM1637_KEY_COM 2
// #endif
#ifndef TM1637_FIRST_ADDRESS
#define TM1637_FIRST_ADDRESS TM1637_ADDR_GRID1
#endif

#ifndef TM1637_default_config
#define TM1637_default_config (TM1637_BRN_LV8 | TM1637_DP_ON)
#endif

#ifdef TM1637_READ_ENB
extern uint8_t TM1637_reg_r;
#endif

extern uint8_t       TM1637_data[TM1637_DIG];
extern const uint8_t seg_code[];

uint8_t TM1637_cfg_w           = TM1637_default_config;
uint8_t TM1637_cfg             = TM1637_default_config;
bool    TM1637_init_successful = false;
uint8_t TM1637_reg_w[TM1637_DIG];

void TM1637_start(void) {
	TM1637_SCL = 1;
	TM1637_SDA = 1;
	TM_DL(10);
	TM1637_SDA = 0;
	TM_DL(10);
	TM1637_SCL = 0;
}

void TM1637_stop(void) {
	TM1637_SCL = 0;
	TM1637_SDA = 0;

	TM_DL(10);
	TM1637_SCL = 1;

	TM_DL(10);
	TM1637_SDA = 1;
}

bool TM1637_write_bit(bool _bit) {
	bool tm_data = 0;
	TM1637_SDA   = _bit;
	TM1637_SCL   = 1;
	TM_DL(10);
	tm_data    = TM1637_SDA;
	TM1637_SCL = 0;
	TM_DL(10);

	return tm_data;
}

// return ACK
uint8_t TM1637_read_byte(void) {
	uint8_t tm_data = 0;
	uint8_t i;
	for (i = 0; i < 8; i++) {
		tm_data <<= 1;
		tm_data |= TM1637_write_bit(1);
	}
	TM1637_write_bit(1); // read ACK bit
	return tm_data;
}

// return ACK
bool TM1637_write_byte(uint8_t tm_data) {
	bool    ack = 0;
	uint8_t i;
	for (i = 0; i < 8; i++) {
		TM1637_write_bit((tm_data >> 7) & 1);
		tm_data <<= 1;
	}
	ack = TM1637_write_bit(1); // read ACK bit
	return ack;
}

void TM1637_write(void) {
	uint8_t i;

	TM1637_start();
	TM1637_write_byte(TM1637_CMD_WRITE_ADDR_AUTO);
	TM1637_stop();

	TM1637_start();
	TM1637_write_byte(TM1637_FIRST_ADDRESS);
	for (i = 0; i < TM1637_DIG; i++) {
		TM1637_write_byte(TM1637_reg_w[i]);
	}
	TM1637_stop();
}

#ifdef TM1637_READ_ENB
uint8_t TM1637_Read(void) {
	TM1637_reg_r = 0;
	TM1637_start();
	if (TM1637_write_byte(TM1637_CMD_READ_ADDR_AUTO) == 0) {
		TM1637_init_successful = true;
		TM1637_reg_r           = TM1637_read_byte();
	}
	else { TM1637_init_successful = false; }
	TM1637_stop();
	return TM1637_reg_r;
}

uint8_t TM1637_get_key(void) { return TM1637_reg_r; }

#endif

// void TM1637_write_4byte(uint8_t b0, uint8_t b1, uint8_t b2, uint8_t b3) {
// 	TM1637_data[0] = b0;
// 	TM1637_data[1] = b1;
// 	TM1637_data[2] = b2;
// 	TM1637_data[3] = b3;
// }

void TM1637_config(void) {
	TM1637_start();
	if (TM1637_write_byte(0xf1) == 0) { // command write
		TM1637_init_successful = true;
	}
	else { TM1637_init_successful = false; }
	TM1637_stop();
}
// BRIGHTNESS_LV1 - BRIGHTNESS_LV8
void TM1637_Set_Brightness(TM1637_BRN_t brightness) {
	TM1637_cfg &= 0x1F;
	TM1637_cfg |= brightness;
}

void TM1637_LED_ENABLE(void) { TM1637_cfg |= TM1637_DP_ON; }
void TM1637_LED_DISABLE(void) { TM1637_cfg &= ~TM1637_DP_ON; }

void TM1637_cfg_update(void) {
	if (TM1637_cfg_w == TM1637_cfg) return;
	TM1637_cfg_w = TM1637_cfg;
	TM1637_config();
}

void TM1637_init(void) {
	// Initialize TM1637 display
	TM1637_SDA = 1;
	TM1637_SCL = 1;
	// PIN_input_PU(TM1637_SDA_PIN);
	PIN_output(TM1637_SCL_PIN);

	my_memset(&TM1637_reg_w, 0x00, TM1637_DIG);
	my_memset(&TM1637_data, 0x00, TM1637_DIG);
#ifdef TM1637_READ_ENB
	my_memset(&TM1637_reg_r, 0x00, sizeof(TM1637_reg_r)); // Clear register
#endif
	TM1637_config();
	TM1637_reg_w[0] = 0xff;
	TM1637_Set_Brightness(TM1637_BRN_LV8);
	TM1637_cfg_update();
}

void TM1637_write_update(void) {
	uint8_t i;
	bool    update = false;
	for (i = 0; i < TM1637_DIG; i++) {
		if (TM1637_reg_w[i] != TM1637_data[i]) {
			TM1637_reg_w[i] = TM1637_data[i];
			update          = true;
		}
	}
	if (!update) return;
	TM1637_write();
}

void TM1637_update(void) {
	if (TM1637_init_successful) {
#ifdef TM1637_READ_ENB
		TM1637_Read();
#endif
		TM1637_cfg_update();
		TM1637_write_update();
	}
	else {
		TM1637_config();
		if (TM1637_init_successful) TM1637_write();
	}
}

bool TM1637_available(void) { return TM1637_init_successful; }

/*
example
// include
#define TM1637_DIG 3
#define TM1637_FIRST_ADDRESS 0x6c // // 68 6a 6c 6e
#define TM1637_default_config (TM1637_BRN_LV8 | TM1637_DP_ON)

#include "TM1637.c"

// global_variables
#ifdef TM1637_READ_ENB
uint8_t TM1637_reg_r;
#endif
uint8_t TM1637_data[TM1637_DIG];
void display(uint8_t value) {
    uint8_t i;
    for (u8i = 0; i < TM1637_DIG; i++) {
        TM1637_data[i] = seg_map[value % 10];
        value /= 10;
    }
}

void display_init(void){
    TM1637_init();
    TM1637_Set_Brightness(TM1637_BRN_LV8);
    display(123);
    TM1637_update();
}

// call TM1637_update(); in loop functions


*/
