#include "TM1650_multi.h"

void TM1650_start(TM1650_class *tm) {
	tm->set_SCL(1);
	tm->set_SDA(1);
	TM_DL(10);
	tm->set_SDA(0);
	TM_DL(10);
	tm->set_SCL(0);
}

void TM1650_stop(TM1650_class *tm) {
	tm->set_SCL(0);
	tm->set_SDA(0);

	TM_DL(10);
	tm->set_SCL(1);

	TM_DL(10);
	tm->set_SDA(1);
}

uint8_t TM1650_write_bit(TM1650_class *tm, uint8_t _bit) {
	uint8_t tm_data = 0;
	tm->set_SDA(_bit);
	tm->set_SCL(1);
	TM_DL(10);
	tm_data = tm->read_SDA();
	tm->set_SCL(0);
	TM_DL(10);
	return tm_data;
}

// return ACK
uint8_t TM1650_read_byte(TM1650_class *tm) {
	uint8_t tm_data = 0;
	uint8_t i;
	for (i = 0; i < 8; i++) {
		tm_data <<= 1;
		tm_data |= TM1650_write_bit(tm, 1);
	}
	TM1650_write_bit(tm, 1); // read ACK bit
	return tm_data;
}

// return ACK
bool TM1650_write_byte(TM1650_class *tm, uint8_t tm_data) {
	bool    ack = 0;
	uint8_t i;
	for (i = 0; i < 8; i++) {
		TM1650_write_bit(tm, (tm_data >> 7) & 1);
		tm_data <<= 1;
	}
	ack = TM1650_write_bit(tm, 1); // read ACK bit
	return ack;
}

void TM1650_write(TM1650_class *tm) {
	uint8_t addr = tm->info.first_address;
	uint8_t i;
	for (i = 0; i < tm->info.DIG; i++) {
		TM1650_start(tm);
		TM1650_write_byte(tm, addr);
		TM1650_write_byte(tm, tm->dt_write[i]);
		addr -= 2;
		TM1650_stop(tm);
	}
}

void TM1650_write_n_bytes_fixed(TM1650_class *tm, uint8_t total, ...) {
	va_list args;
	uint8_t i;
	va_start(args, total);
	for (i = 0; i < total; i++) {
		tm->dt_write[i] = va_arg(args, uint8_t);
	}
	va_end(args);

	tm->info.cfg |= TM1650_DP_ON;
	TM1650_config(tm);
	TM1650_write(tm);
}

void TM1650_write_n_bytes(TM1650_class *tm, uint8_t total, ...) {
	va_list args;
	uint8_t i;
	va_start(args, total);
	for (i = 0; i < total; i++) {
		tm->dt_write_new[i] = va_arg(args, uint8_t);
	}
	va_end(args);
}

void TM1650_set_dot(TM1650_class *tm, uint8_t idx) { tm->dt_write_new[idx] |= 0x80; }

void TM1650_string(TM1650_class *tm, uint8_t *s) {
	int8_t idx = tm->info.DIG - 1;
	while (*s && idx >= 0) {
		tm->dt_write_new[idx--] = seg_chr(*s);
		s++;
	}

	while (idx >= 0) {
		tm->dt_write_new[idx--] = sMap(OFF);
	}
}

void TM1650_config(TM1650_class *tm) {
	TM1650_start(tm);
	if (TM1650_write_byte(tm, 0x48) == 0) {  // command write
		TM1650_write_byte(tm, tm->info.cfg); // Config
		tm->info.init = true;
	}
	else { tm->info.init = false; }
	TM1650_stop(tm);
}
// TM1650_BRN_LV1 - TM1650_BRN_LV8
void TM1650_Set_Brightness(TM1650_class *tm, TM1650_BRN_t brightness) {
	tm->info.cfg_new &= 0x0F;
	tm->info.cfg_new |= brightness;
}

void TM1650_LED_ENABLE(TM1650_class *tm) { tm->info.cfg_new |= TM1650_DP_ON; }

void TM1650_LED_DISABLE(TM1650_class *tm) { tm->info.cfg_new &= ~TM1650_DP_ON; }

void TM1650_SET_ENABLE(TM1650_class *tm, uint8_t state) {
	tm->info.cfg_new &= CLR_BIT0;
	tm->info.cfg_new |= state;
}

// address is : 0x68 0x6a 0x6c 0x6e
void TM1650_init(TM1650_class *tm, uint8_t DIG, uint8_t first_address) {
	// Initialize TM1650 display
	// tm->set_SDA(1);
	// tm->set_SCL(1);

	// PIN_input_PU(TM1650_SDA_PIN);
	// PIN_output(TM1650_SCL_PIN);

	memset(tm->dt_write, 0, sizeof(tm->dt_write));
	memset(tm->dt_write_new, sMap(ON), sizeof(tm->dt_write_new));
	memset(&tm->info, 0, sizeof(TM1650_INFO_t));
	tm->info.DIG           = DIG;
	tm->info.first_address = first_address;
	tm->info.cfg = tm->info.cfg_new = TM1650_default_config;
	TM1650_config(tm);
}

uint8_t TM1650_Read(TM1650_class *tm) {
	tm->dt_read = 0;
	TM1650_start(tm);
	if (TM1650_write_byte(tm, 0x49) == 0) {
		tm->info.init = true;
		tm->dt_read   = TM1650_read_byte(tm);
	}
	else { tm->info.init = false; }
	TM1650_stop(tm);
	return tm->dt_read;
}

uint8_t TM1650_get_key(TM1650_class *tm) { return tm->dt_read; }

bool TM1650_available(TM1650_class *tm) { return tm->info.init; }

void TM1650_write_update(TM1650_class *tm) {
	uint8_t i;
	bool    update = false;
	for (i = 0; i < tm->info.DIG; i++) {
		if (tm->dt_write[i] != tm->dt_write_new[i]) {
			tm->dt_write[i] = tm->dt_write_new[i];
			update          = true;
		}
	}
	if (!update) return;
	TM1650_write(tm);
}

void TM1650_cfg_update(TM1650_class *tm) {
	if (tm->info.cfg == tm->info.cfg_new) return;
	tm->info.cfg = tm->info.cfg_new;
	TM1650_config(tm);
}

void TM1650_update(TM1650_class *tm) {
	if (tm->info.init) {
		TM1650_Read(tm);
		TM1650_cfg_update(tm);
		TM1650_write_update(tm);
	}
	else {
		TM1650_config(tm);
		if (tm->info.init) TM1650_write(tm);
	}
}

void TM1650_number(TM1650_class *tm, uint16_t value) {
	uint8_t i = 0;
	do {
		tm->dt_write_new[i++] = seg_map[value % 10];
		value /= 10;
	} while (i < tm->info.DIG && value);

	while (i < tm->info.DIG) {
		tm->dt_write_new[i++] = sMap(OFF);
	}
}