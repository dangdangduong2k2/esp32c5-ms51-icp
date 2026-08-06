/*  TM1650 SINGLE Library
    Version: 1.4
    Date modified: 241216
    Author: Tran-Luyen
    Author Github: https://github.com/Tran-Luyen
    Github Link: https://github.com/Tran-Luyen/Code-Lib/blob/main/Common-lib/TM1650/TM1650-SINGLE
    File path: file:///D:\Works\Github_Projects\Code-Lib\Common-lib/TM1650/TM1650-SINGLE/TM1650.h
*/

#ifndef _TM1650_H_
#define _TM1650_H_

#include "gpio_manager.h"
#include "delay.h"
#include "common.h"
#include "MS51_GPIO_Macro.h"
#include <STDARG.H>
#include "seg_map.h"
#include "header.h"

// #define TM1650_READ_ENB

#ifndef TM_DL
#define TM_DL(t) delay_us(t)
#endif

#ifndef TM1650_SCL_PIN
#define TM1650_SCL_PIN SCL1_PIN
#endif

#ifndef TM1650_SDA_PIN
#define TM1650_SDA_PIN SDA1_PIN
#endif

#define TM1650_SCL PIN(TM1650_SCL_PIN)
#define TM1650_SDA PIN(TM1650_SDA_PIN)

#ifndef TM1650_DIG
#define TM1650_DIG 4
#endif

// 68 6a 6c 6e
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

#ifndef TM1650_default_config
#define TM1650_default_config (TM1650_BRN_LV8 | 0x01)
#endif

void TM1650_init(void);
void TM1650_update(void);
void TM1650_Set_Brightness(TM1650_BRN_t brightness);
void TM1650_write_n_bytes(uint8_t idx, ...);

#endif