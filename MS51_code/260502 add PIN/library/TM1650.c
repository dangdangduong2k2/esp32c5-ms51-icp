#include "TM1650.h"

#ifdef TM1650_READ_ENB
uint8_t TM1650_reg_r;
#endif
extern const uint8_t seg_code[];

uint8_t TM1650_cfg_w           = TM1650_default_config;
uint8_t TM1650_cfg             = TM1650_default_config;
bool    TM1650_init_successful = false;
uint8_t TM1650_reg_w[TM1650_DIG];
uint8_t TM1650_data[TM1650_DIG];

void TM1650_start(void) {
	TM1650_SCL = 1;
	TM1650_SDA = 1;
	TM_DL(10);
	TM1650_SDA = 0;
	TM_DL(10);
	TM1650_SCL = 0;
}

void TM1650_stop(void) {
	TM1650_SCL = 0;
	TM1650_SDA = 0;

	TM_DL(10);
	TM1650_SCL = 1;

	TM_DL(10);
	TM1650_SDA = 1;
}

bool TM1650_write_bit(bool _bit) {
	bool tm_data = 0;
	TM1650_SDA   = _bit;
	TM1650_SCL   = 1;
	TM_DL(10);
	tm_data    = TM1650_SDA;
	TM1650_SCL = 0;
	TM_DL(10);

	return tm_data;
}

// return ACK
uint8_t TM1650_read_byte(void) {
	uint8_t tm_data = 0;
	uint8_t i;
	for (i = 0; i < 8; i++) {
		tm_data <<= 1;
		tm_data |= TM1650_write_bit(1);
	}
	TM1650_write_bit(1); // read ACK bit
	return tm_data;
}

// return ACK
bool TM1650_write_byte(uint8_t tm_data) {
	bool    ack = 0;
	uint8_t i;
	for (i = 0; i < 8; i++) {
		TM1650_write_bit((tm_data >> 7) & 1);
		tm_data <<= 1;
	}
	ack = TM1650_write_bit(1); // read ACK bit
	return ack;
}

void TM1650_write(void) {
	uint8_t addr = TM1650_FIRST_ADDRESS;
	uint8_t i;
	for (i = 0; i < TM1650_DIG; i++) {
		TM1650_start();
		TM1650_write_byte(addr);
		TM1650_write_byte(TM1650_reg_w[i]);
		addr -= 2;
		TM1650_stop();
	}
}

void TM1650_write_n_bytes(uint8_t idx, ...) {
	va_list args;
	uint8_t i;
	va_start(args, idx);
	for (i = idx; i < TM1650_DIG; i++) {
		TM1650_data[i] = va_arg(args, uint8_t);
	}
	va_end(args);
}

void TM1650_config(void) {
	TM1650_start();
	if (TM1650_write_byte(0x48) == 0) { // command write
		TM1650_write_byte(TM1650_cfg);  // Config
		TM1650_init_successful = true;
	}
	else { TM1650_init_successful = false; }
	TM1650_stop();
}
// BRIGHTNESS_LV1 - BRIGHTNESS_LV8
void TM1650_Set_Brightness(TM1650_BRN_t brightness) {
	TM1650_cfg &= 0x0F;
	TM1650_cfg |= brightness;
}

void TM1650_init(void) {
	// Initialize TM1650 display
	TM1650_SDA = 1;
	TM1650_SCL = 1;
	PIN_input_PU(TM1650_SDA_PIN);
	PIN_output(TM1650_SCL_PIN);

	my_memset(&TM1650_reg_w, 0x00, TM1650_DIG);

#ifdef TM1650_READ_ENB
	TM1650_reg_r = 0x00; // Clear register
#endif
	TM1650_config();
}

#ifdef TM1650_READ_ENB
uint8_t TM1650_Read(void) {
	TM1650_reg_r = 0;
	TM1650_start();
	if (TM1650_write_byte(0x49) == 0) {
		TM1650_init_successful = true;
		TM1650_reg_r           = TM1650_read_byte();
		//
	}
	else { TM1650_init_successful = false; }
	TM1650_stop();
	return TM1650_reg_r;
}

uint8_t TM1650_get_key(void) { return TM1650_reg_r; }

#endif

bool TM1650_available(void) { return TM1650_init_successful; }

void TM1650_write_update(void) {
	uint8_t i;
	bool    update = false;
	for (i = 0; i < TM1650_DIG; i++) {
		if (TM1650_reg_w[i] != TM1650_data[i]) {
			TM1650_reg_w[i] = TM1650_data[i];
			update          = true;
		}
	}
	if (!update) return;
	TM1650_write();
}

void TM1650_cfg_update(void) {
	if (TM1650_cfg_w == TM1650_cfg) return;
	TM1650_cfg_w = TM1650_cfg;
	TM1650_config();
}

void TM1650_update(void) {
	if (TM1650_init_successful) {
#ifdef TM1650_READ_ENB
		TM1650_Read();
#endif
		TM1650_cfg_update();
		TM1650_write_update();
		// wite byte
	}
	else {
		TM1650_config();
		if (TM1650_init_successful) TM1650_write();
	}
}

void TM1650_check_plug_out(void) {
	TM1650_start();
	if (TM1650_write_byte(0x48) == 0) { TM1650_init_successful = true; }
	else { TM1650_init_successful = false; }
	TM1650_stop();
}

/*
example
// include
#define TM1650_DIG 3
#define TM1650_FIRST_ADDRESS 0x6c // // 68 6a 6c 6e
#define TM1650_default_config (TM1650_BRN_LV8 | 0x01) // LV1-LV8, 0x01 -> enable

#include "TM1650.c"

// global_variables
void display(uint8_t value) {
    uint8_t i;
    for (i = 0; i < TM1650_DIG; i++) {
        TM1650_data[i] = seg_map[value % 10];
        value /= 10;
    }
}

void display_init(void){
    TM1650_init();
    TM1650_Set_Brightness(BRIGHTNESS_LV3);
    display(179);
    TM1650_update();
}

// call TM1650_update(); in loop functions


*/
