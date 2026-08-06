/*  TM1650 SINGLE Library
    Version: 1.3
    Date modified: 240925
    Author: Tran-Luyen
    Author Github: https://github.com/Tran-Luyen
    Github Link: https://github.com/Tran-Luyen/Code-Lib/blob/main/Common-lib/TM1650/TM1650-MULTI
    File path: file:///D:\Works\Github_Projects\Code-Lib\Common-lib/TM1650/TM1650-MULTI/TM1650_multi.h
*/

#ifndef _TM1650_multi_H_
#define _TM1650_multi_H_

#include "gpio_manager.h"
#include "delay.h"
#include "common.h"
#include "MS51_GPIO_Macro.h"
#include "seg_map.h"
#include "header.h"
#include <STDARG.H>
#include <STRING.H>

#ifndef TM_DL
#define TM_DL(t) delay_us(t)
#endif

//
#ifndef TM1650_FIRST_ADDRESS
#define TM1650_FIRST_ADDRESS (0x66 + 2 * TM1650_DIG)
#endif

typedef enum TM1650_BRN_t {
	TM1650_BRN_LV8 = 0x00,
	TM1650_BRN_LV7 = 0x70,
	TM1650_BRN_LV6 = 0x60,
	TM1650_BRN_LV5 = 0x50,
	TM1650_BRN_LV4 = 0x40,
	TM1650_BRN_LV3 = 0x30,
	TM1650_BRN_LV2 = 0x20,
	TM1650_BRN_LV1 = 0x10
} TM1650_BRN_t;
uint8_t TM1650_BRN[8]={0x10,0x20,0x30,0x40,0x50,0x60,0x70,0x00};

typedef enum TM1650_DP_MODE_t { TM1650_DP_OFF = 0x00, TM1650_DP_ON = 0x01 } TM1650_DP_MODE_t;

#ifndef TM1650_default_config
#define TM1650_default_config (TM1650_BRN_LV8 | TM1650_DP_ON)
#endif

typedef struct TM1650_INFO_s {
	uint8_t init;
	uint8_t cfg;
	uint8_t cfg_new;
	uint8_t DIG;
	uint8_t first_address;
} TM1650_INFO_t;

typedef struct sTM1650_class {
	void (*set_SCL)(uint8_t);
	void (*set_SDA)(uint8_t);
	uint8_t (*read_SDA)(void);

	TM1650_INFO_t info;
	uint8_t       dt_read;
	uint8_t       dt_write[4];
	uint8_t       dt_write_new[4];
} TM1650_class;

void    TM1650_start(TM1650_class *tm);
void    TM1650_stop(TM1650_class *tm);
uint8_t TM1650_write_bit(TM1650_class *tm, uint8_t _bit);
uint8_t TM1650_read_byte(TM1650_class *tm);
bool    TM1650_write_byte(TM1650_class *tm, uint8_t tm_data);
void    TM1650_write(TM1650_class *tm);
void    TM1650_write_n_bytes(TM1650_class *tm, uint8_t total, ...);
void    TM1650_string(TM1650_class *tm, uint8_t *s);
void    TM1650_config(TM1650_class *tm);
void    TM1650_Set_Brightness(TM1650_class *tm, TM1650_BRN_t brightness);
void    TM1650_LED_ENABLE(TM1650_class *tm);
void    TM1650_LED_DISABLE(TM1650_class *tm);
void    TM1650_init(TM1650_class *tm, uint8_t DIG, uint8_t first_address);
uint8_t TM1650_Read(TM1650_class *tm);
uint8_t TM1650_get_key(TM1650_class *tm);
bool    TM1650_available(TM1650_class *tm);
void    TM1650_write_update(TM1650_class *tm);
void    TM1650_cfg_update(TM1650_class *tm);
void    TM1650_update(TM1650_class *tm);
void    TM1650_number(TM1650_class *tm, uint16_t value);

#endif

/*
// example:
void    TM1_set_SCL(uint8_t value) { PIN_write(SCL1_PIN, value); }
void    TM1_set_SDA(uint8_t value) { PIN_write(SDA1_PIN, value); }
uint8_t TM1_read_SDA(void) { return PIN_read(SDA1_PIN); }

TM1650_class TM1 = {TM1_set_SCL, TM1_set_SDA, TM1_read_SDA};

void display_init(void) {
    TM1650_init(&TM1, 3, 0x6c);
    TM1650_Set_Brightness(&TM1, TM1650_BRN_LV5);
    // TM1650_write_n_bytes(&TM1, 3, sMap(1), sMap(2), sMap(9));
    TM1650_update(&TM1);
    delay_ms(1000);
    TM1650_number(&TM1, 123);
    TM1650_update(&TM1);
}

// call display_init() in setup function
// call TM1650_update(&TM1); in loop function


*/